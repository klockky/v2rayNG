package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VPN-level packet firewall that enforces split-tunneling exclusions at the raw-packet layer.
 *
 * Problem: some apps call bindSocket() on the VPN's tun0 interface, bypassing
 * [android.net.VpnService.Builder.addDisallowedApplication]. This class detects those packets
 * by querying [ConnectivityManager.getConnectionOwnerUid] (API 29+) and silently drops them.
 *
 * Architecture — two threads bridge between the real TUN fd and the Xray / HEV core:
 *
 *   outgoingLoop:  real TUN ──read──► UID-check ──write (if allowed)──► bridgeSocketFd ──► coreSocketFd (core reads here)
 *   incomingLoop:  real TUN ◄─write──────────────────────────────────── bridgeSocketFd ◄── coreSocketFd (core writes here)
 *
 * [coreSocketFd] is handed to the Xray / HEV native layer as a drop-in replacement for the
 * original TUN fd.  Both sides are AF_UNIX / SOCK_DGRAM, which preserves IP-packet boundaries.
 *
 * UID lookups are cached by source port in a [ConcurrentHashMap] with a 30-second TTL to keep
 * CPU overhead negligible.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class TunPacketFilter(
    /** The original TUN interface created by [android.net.VpnService.Builder.establish]. */
    private val tunInterface: ParcelFileDescriptor,
    private val connectivityManager: ConnectivityManager,
    /** UIDs whose outgoing packets must be silently dropped. Must not be empty. */
    val blockedUids: Set<Int>,
    /** UID → package name mapping for blocked UIDs (used for firewall log display). */
    private val uidToPackage: Map<Int, String> = emptyMap(),
    private val mtu: Int = DEFAULT_MTU,
) {
    companion object {
        private const val TAG = AppConfig.TAG
        private const val DEFAULT_MTU = 1500

        /** How long (ms) a source-port → UID mapping is kept before re-querying. */
        private const val CACHE_TTL_MS = 30_000L

        // Linux socket constants not exposed in android.system.OsConstants public API.
        // On Android/Linux EWOULDBLOCK == EAGAIN == 11; MSG_DONTWAIT == 0x40.
        private const val MSG_DONTWAIT = 0x40
    }

    // ── socket pair ───────────────────────────────────────────────────────────

    /** socket[0]: core end — given to Xray / HEV native code as the TUN fd replacement. */
    private val coreSocketFd = FileDescriptor()

    /** socket[1]: bridge end — our threads forward / filter packets through this side. */
    private val bridgeSocketFd = FileDescriptor()

    /**
     * Raw integer fd for [coreSocketFd].
     * Pass this to [ParcelFileDescriptor.fromFd] to get the PFD that replaces the original TUN
     * interface for core libraries.
     */
    val filteredFd: Int

    // ── UID cache ─────────────────────────────────────────────────────────────

    /** source-port → (uid, timestamp-ms) */
    private val uidCache = ConcurrentHashMap<Int, Pair<Int, Long>>()

    // ── lifecycle ─────────────────────────────────────────────────────────────

    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "TunPacketFilter").apply { isDaemon = true }
    }

    // ── init ──────────────────────────────────────────────────────────────────

    init {
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, coreSocketFd, bridgeSocketFd)

        // Enlarge socket buffers so burst traffic doesn't drop datagrams.
        val bufSize = mtu * 64
        for (fd in listOf(coreSocketFd, bridgeSocketFd)) {
            try {
                Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, bufSize)
                Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, bufSize)
            } catch (_: ErrnoException) { /* best-effort */ }
        }

        filteredFd = getIntFd(coreSocketFd)
        Log.i(TAG, "TunFilter: socket pair created, blocking UIDs: $blockedUids")
    }

    // ── public API ────────────────────────────────────────────────────────────

    fun start() {
        running.set(true)
        executor.submit(::outgoingLoop) // TUN → core (with UID filter)
        executor.submit(::incomingLoop) // core → TUN (pass-through)
        Log.i(TAG, "TunFilter: bridge threads started")
    }

    fun stop() {
        running.set(false)
        executor.shutdownNow()
        silentClose(bridgeSocketFd)
        silentClose(coreSocketFd)
        uidCache.clear()
        Log.i(TAG, "TunFilter: stopped")
    }

    // ── bridge threads ────────────────────────────────────────────────────────

    /**
     * Reads raw IP packets from the real TUN, applies the UID firewall, and forwards
     * allowed packets to the core via the socket pair.
     *
     * The write to [bridgeSocketFd] uses MSG_DONTWAIT so that a slow core never stalls
     * this loop: if the socket buffer is momentarily full we drop the packet (TCP will
     * retransmit; UDP is inherently lossy) rather than blocking and starving TUN reads.
     */
    private fun outgoingLoop() {
        val buf = ByteArray(mtu + 4) // +4 for any platform-prepended header word
        val tunFd = tunInterface.fileDescriptor
        while (running.get()) {
            // ── read one IP packet from the real TUN ──────────────────────────
            val n = try {
                Os.read(tunFd, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EAGAIN) {
                    // TUN fd was temporarily set non-blocking by someone else — just retry.
                    Thread.sleep(1)
                    continue
                }
                if (running.get()) Log.e(TAG, "TunFilter: TUN read error (errno=${e.errno})", e)
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "TunFilter: TUN read error", e)
                break
            }

            if (n <= 0) continue
            if (shouldDrop(buf, n)) continue

            // ── forward to core via socket pair (non-blocking) ────────────────
            try {
                Os.sendto(bridgeSocketFd, buf, 0, n, MSG_DONTWAIT, null)
            } catch (e: ErrnoException) {
                when (e.errno) {
                    OsConstants.EAGAIN, OsConstants.EAGAIN ->
                        Log.d(TAG, "TunFilter: socket buffer full, outgoing packet dropped")
                    else -> {
                        if (running.get())
                            Log.e(TAG, "TunFilter: socket write error (errno=${e.errno})", e)
                        return
                    }
                }
            }
        }
    }

    /**
     * Reads response packets injected by the core via the socket pair and writes them
     * back to the real TUN (no filtering needed for internet → app direction).
     *
     * The read from [bridgeSocketFd] is blocking (nobody sets O_NONBLOCK on our side of the
     * pair), so this thread sleeps until the core produces a packet — zero CPU overhead.
     */
    private fun incomingLoop() {
        val buf = ByteArray(mtu + 4)
        val tunFd = tunInterface.fileDescriptor
        while (running.get()) {
            // ── read one response packet from the core ────────────────────────
            val n = try {
                Os.read(bridgeSocketFd, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EAGAIN) {
                    Thread.sleep(1)
                    continue
                }
                if (running.get()) Log.e(TAG, "TunFilter: socket read error (errno=${e.errno})", e)
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "TunFilter: socket read error", e)
                break
            }

            if (n <= 0) continue

            // ── write back to the real TUN ────────────────────────────────────
            try {
                Os.write(tunFd, buf, 0, n)
            } catch (e: ErrnoException) {
                if (running.get()) Log.e(TAG, "TunFilter: TUN write error (errno=${e.errno})", e)
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "TunFilter: TUN write error", e)
                break
            }
        }
    }

    // ── packet parsing ────────────────────────────────────────────────────────

    /** Returns true if this packet must be dropped (sender UID is in [blockedUids]). */
    private fun shouldDrop(buf: ByteArray, len: Int): Boolean {
        if (len < 20) return false
        return when ((buf[0].toInt() and 0xFF) shr 4) {
            4    -> shouldDropIPv4(buf, len)
            6    -> shouldDropIPv6(buf, len)
            else -> false
        }
    }

    private fun shouldDropIPv4(buf: ByteArray, len: Int): Boolean {
        // Internet Header Length (IHL) field gives the start of the transport header.
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (len < ihl + 4) return false

        val protocol = buf[9].toInt() and 0xFF
        if (protocol != 6 /* TCP */ && protocol != 17 /* UDP */) return false

        val srcIp  = InetAddress.getByAddress(buf.copyOfRange(12, 16))
        val dstIp  = InetAddress.getByAddress(buf.copyOfRange(16, 20))
        val srcPort = readUShort(buf, ihl)
        val dstPort = readUShort(buf, ihl + 2)

        return checkUid(protocol, srcIp, srcPort, dstIp, dstPort)
    }

    /**
     * Simplified IPv6 parse — assumes no extension headers (covers the vast majority of traffic).
     * Packets with extension headers preceding TCP/UDP are passed through unfiltered.
     */
    private fun shouldDropIPv6(buf: ByteArray, len: Int): Boolean {
        if (len < 44) return false // 40-byte fixed header + 4 bytes for port fields

        val protocol = buf[6].toInt() and 0xFF
        if (protocol != 6 /* TCP */ && protocol != 17 /* UDP */) return false

        val srcIp  = InetAddress.getByAddress(buf.copyOfRange(8, 24))
        val dstIp  = InetAddress.getByAddress(buf.copyOfRange(24, 40))
        val srcPort = readUShort(buf, 40)
        val dstPort = readUShort(buf, 42)

        return checkUid(protocol, srcIp, srcPort, dstIp, dstPort)
    }

    // ── UID lookup + cache ────────────────────────────────────────────────────

    @SuppressLint("NewApi") // class-level @RequiresApi(Q) already enforces the API level
    private fun checkUid(
        protocol: Int,
        srcIp: InetAddress,
        srcPort: Int,
        dstIp: InetAddress,
        dstPort: Int,
    ): Boolean {
        val now = System.currentTimeMillis()

        // Fast path: valid cache hit.
        uidCache[srcPort]?.let { (uid, ts) ->
            if (now - ts < CACHE_TTL_MS) {
                if (uid == Process.INVALID_UID || uid in blockedUids) {
                    return true
                }
                return false
            }
        }

        // Slow path: ask the kernel which UID owns this connection.
        var uid = try {
            connectivityManager.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(srcIp, srcPort),
                InetSocketAddress(dstIp, dstPort),
            )
        } catch (e: Exception) {
            Log.e(TAG, "TunFilter: getConnectionOwnerUid failed", e)
            return false
        }

        // Fallback: if the Android API couldn't find the socket (e.g. root process using
        // SO_BINDTODEVICE or explicit IP bind), look it up directly in /proc/net/tcp[6].
        if (uid == Process.INVALID_UID) {
            val procUid = lookupUidFromProc(protocol == 6 /* TCP */, srcPort)
            Log.d(TAG, "TunFilter: proc fallback uid=$procUid srcPort=$srcPort (was INVALID_UID)")
            uid = procUid
        }

        uidCache[srcPort] = uid to now

        Log.d(TAG, "TunFilter: lookup uid=$uid srcPort=$srcPort proto=$protocol src=$srcIp dst=$dstIp:$dstPort")

        // Drop packets from blocked UIDs OR unidentifiable sockets (uid=-1).
        // All legitimate traffic gets a valid UID; uid=-1 means the socket is hidden
        // from the system (root SO_BINDTODEVICE, SELinux-isolated namespace, etc.) —
        // exactly the bypass technique we want to block.
        if (uid == Process.INVALID_UID || uid in blockedUids) {
            val reason = if (uid == Process.INVALID_UID) "unidentifiable" else "blocked"
            Log.w(
                TAG,
                "TunFilter: DROP ($reason) uid=$uid proto=$protocol " +
                    "src=$srcIp:$srcPort dst=$dstIp:$dstPort",
            )
            TunFirewallLog.record(uid, uidToPackage[uid], dstIp.hostAddress ?: dstIp.toString(), dstPort, protocol)
            return true
        }
        return false
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Reads /proc/net/tcp (and tcp6 / udp / udp6) to find which UID owns a socket
     * whose local port is [srcPort].  Used as a fallback when [ConnectivityManager.getConnectionOwnerUid]
     * returns [Process.INVALID_UID] — this happens for root processes (UID 0) and for sockets
     * bound to a device or IP directly rather than going through Android's network routing.
     *
     * Returns the UID found, or [Process.INVALID_UID] if not found.
     */
    private fun lookupUidFromProc(isTcp: Boolean, srcPort: Int): Int {
        val portHex = "%04X".format(srcPort)
        val procFiles = if (isTcp) listOf("/proc/net/tcp", "/proc/net/tcp6")
                        else       listOf("/proc/net/udp", "/proc/net/udp6")
        for (path in procFiles) {
            try {
                java.io.File(path).useLines { lines ->
                    lines.drop(1) // skip header
                         .forEach { line ->
                             val parts = line.trim().split("\\s+".toRegex())
                             // field 1 is "localHexIP:localHexPort", field 7 is uid
                             if (parts.size >= 8 &&
                                 parts[1].substringAfterLast(':').uppercase() == portHex) {
                                 val uid = parts[7].toIntOrNull()
                                 if (uid != null) throw FoundUidException(uid)
                             }
                         }
                }
            } catch (e: FoundUidException) {
                return e.uid
            } catch (_: Exception) {}
        }
        return Process.INVALID_UID
    }

    private class FoundUidException(val uid: Int) : Exception()

    private fun readUShort(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun silentClose(fd: FileDescriptor) {
        try { Os.close(fd) } catch (_: Exception) {}
    }

    /**
     * Extracts the raw integer file descriptor from a [FileDescriptor] object.
     *
     * Primary: calls the `getInt$()` method available on API 21+ (hidden but stable).
     * Fallback: accesses the private `descriptor` field via reflection.
     * Both approaches are widely used in Android VPN implementations.
     */
    private fun getIntFd(fd: FileDescriptor): Int {
        return try {
            val m = FileDescriptor::class.java.getDeclaredMethod("getInt\$")
            m.isAccessible = true
            m.invoke(fd) as Int
        } catch (_: Exception) {
            val f = FileDescriptor::class.java.getDeclaredField("descriptor")
            f.isAccessible = true
            f.getInt(fd)
        }
    }
}
