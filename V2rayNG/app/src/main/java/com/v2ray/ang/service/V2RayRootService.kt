package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.content.pm.PackageManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.RootShell
import java.lang.ref.SoftReference

/**
 * Foreground service for "Root" mode.
 *
 * Unlike [V2RayVpnService] this service does not create a VPN interface or
 * run tun2socks. Instead it:
 *
 * 1. Launches the v2ray core with a dokodemo-door "redirect" inbound in
 *    addition to the authenticated SOCKS5 inbound.
 * 2. Installs iptables NAT rules via `su` that transparently REDIRECT all
 *    outbound TCP traffic from non-system apps into that inbound.
 *
 * The core still listens on the password-protected SOCKS port, but no
 * tun2socks layer is involved — TCP packets flow straight from the kernel
 * into the v2ray redirect inbound.
 */
class V2RayRootService : Service(), ServiceControl {

    private var rulesInstalled: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.i(AppConfig.TAG, "StartCore-Root: Service created")
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(AppConfig.TAG, "StartCore-Root: Service command received")

        // Bring up the core synchronously. startCoreLoop() calls
        // NotificationManager.showNotification() which in turn calls
        // startForeground() — we MUST hit that within the 5-second
        // onStartCommand window or Android will kill us.
        val coreStarted = V2RayServiceManager.startCoreLoop(null)
        if (!coreStarted) {
            Log.e(AppConfig.TAG, "StartCore-Root: core failed to start")
            stopSelf()
            return START_NOT_STICKY
        }

        // `su` may block on a Magisk / SuperUser prompt, so push the root
        // probe and iptables install off the main thread.
        Thread({
            try {
                if (!RootShell.isAvailable()) {
                    Log.e(AppConfig.TAG, "StartCore-Root: su not available / denied")
                    MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, "")
                    V2RayServiceManager.stopCoreLoop()
                    stopSelf()
                    return@Thread
                }
                if (!installIptablesRules()) {
                    Log.e(AppConfig.TAG, "StartCore-Root: iptables install failed")
                    MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, "")
                    V2RayServiceManager.stopCoreLoop()
                    stopSelf()
                    return@Thread
                }
                Log.i(AppConfig.TAG, "StartCore-Root: Root session ready")
            } catch (t: Throwable) {
                Log.e(AppConfig.TAG, "StartCore-Root: unexpected failure", t)
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, "")
                V2RayServiceManager.stopCoreLoop()
                stopSelf()
            }
        }, "V2RayNG-RootInit").start()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeIptablesRules()
        V2RayServiceManager.stopCoreLoop()
    }

    override fun getService(): Service = this

    override fun startService() {
        // no-op, startCoreLoop() is invoked in onStartCommand
    }

    override fun stopService() {
        stopSelf()
    }

    /**
     * Root mode does not run a VpnService so there is nothing to protect:
     * outbound sockets from the v2ray core run as the app's uid, and our
     * iptables chain explicitly skips that uid to avoid routing loops.
     */
    override fun vpnProtect(socket: Int): Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let { MyContextWrapper.wrap(it, SettingsManager.getLocale()) }
        super.attachBaseContext(context)
    }

    private fun installIptablesRules(): Boolean {
        val selfUid = applicationInfo.uid
        val redirectPort = V2rayConfigManager.getRedirectPort()
        val dnsRedirectPort = V2rayConfigManager.getDnsRedirectPort()
        val chain = IPTABLES_CHAIN
        val chain6 = IPTABLES_CHAIN_V6
        val chainUdp = IPTABLES_CHAIN_UDP
        val chainPre = IPTABLES_CHAIN_PRE
        val chainInput = IPTABLES_CHAIN_INPUT
        val chainFwd = IPTABLES_CHAIN_FWD
        val chainFwd6 = IPTABLES_CHAIN_FWD6

        val tetherSharing = SettingsManager.isRootTetherSharingEnabled()
        val perAppEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)
        val bypassMode = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false)
        val selectedUids: List<Int> = if (perAppEnabled) {
            val pkgs = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET).orEmpty()
            resolveUidsForPackages(pkgs)
        } else {
            emptyList()
        }

        Log.i(
            AppConfig.TAG,
            "StartCore-Root: perAppEnabled=$perAppEnabled bypassMode=$bypassMode tetherSharing=$tetherSharing uids=$selectedUids",
        )

        // Unconditional pre-flight purge. If the previous session crashed,
        // was OOM-killed, or the user toggled tether sharing off between
        // runs, stale chains may still be hooked in the kernel with
        // now-invalid REDIRECT ports (we pick a fresh ephemeral port on
        // every start). Flush every chain we might ever have installed
        // before building fresh ones, so we never leave a tether-PRE
        // REDIRECT pointing at a port that no longer has a listener.
        val purge = RootShell.exec(buildPurgeScript())
        if (!purge.ok) {
            Log.w(
                AppConfig.TAG,
                "StartCore-Root: pre-flight iptables purge reported errors: ${purge.stderr}",
            )
        }

        // --- IPv4 (nat table, MUST succeed) ---
        val ipv4Script = buildString {
            appendLine("set -e")
            appendLine("iptables -t nat -N $chain")

            // Don't loop our own traffic.
            appendLine("iptables -t nat -A $chain -m owner --uid-owner $selfUid -j RETURN")

            // Leave loopback traffic alone.
            appendLine("iptables -t nat -A $chain -o lo -j RETURN")

            // Skip LAN / multicast / link-local so local network still works.
            for (cidr in LAN_BYPASS_V4) {
                appendLine("iptables -t nat -A $chain -d $cidr -j RETURN")
            }

            // TCP is always redirected. UDP/53 (DNS) is also redirected
            // when per-app proxy is disabled, so that DNS queries flow
            // through xray's dns-out outbound instead of leaking to the
            // ISP resolver via netd. Without this, apps like Telegram see
            // a multi-second delay on every new connection in censored
            // networks because DNS resolution is slow or hijacked.
            //
            // For per-app modes we keep the old "UDP/53 untouched"
            // behaviour: netd runs as its own system uid and we cannot
            // attribute DNS packets back to the originating app, so
            // redirecting DNS there would either tunnel bypass apps' DNS
            // (violating bypass intent) or tunnel non-selected apps' DNS
            // (inconsistent with "proxy only selected").
            if (!perAppEnabled) {
                appendLine("iptables -t nat -A $chain -p tcp -j REDIRECT --to-ports $redirectPort")
                appendLine("iptables -t nat -A $chain -p udp --dport 53 -j REDIRECT --to-ports $dnsRedirectPort")
            } else if (bypassMode) {
                // "Bypass selected" — let the listed apps skip the proxy,
                // redirect everyone else's TCP.
                for (uid in selectedUids) {
                    appendLine("iptables -t nat -A $chain -m owner --uid-owner $uid -j RETURN")
                }
                appendLine("iptables -t nat -A $chain -p tcp -j REDIRECT --to-ports $redirectPort")
            } else {
                // "Proxy only selected" — redirect just the listed uids,
                // leave everyone else untouched.
                for (uid in selectedUids) {
                    appendLine(
                        "iptables -t nat -A $chain -p tcp -m owner --uid-owner $uid -j REDIRECT --to-ports $redirectPort"
                    )
                }
            }

            // Hook into OUTPUT.
            appendLine("iptables -t nat -I OUTPUT -j $chain")
        }

        val ipv4 = RootShell.exec(ipv4Script)
        if (!ipv4.ok) {
            Log.e(AppConfig.TAG, "StartCore-Root: iptables install failed: ${ipv4.stderr}")
            return false
        }
        rulesInstalled = true
        Log.i(AppConfig.TAG, "StartCore-Root: iptables (v4) rules installed")

        // --- IPv6 (filter table, best effort) ---
        // We can't REDIRECT IPv6 to the proxy (xray has no NAT66 path), so
        // the safe default is to REJECT outbound v6 so apps immediately
        // fall back to IPv4 via Happy Eyeballs. Using DROP here made apps
        // wait the full TCP connect timeout before retrying — which the
        // user saw as "the app takes a long time before anything works".
        // TCP gets `--reject-with tcp-reset` so the stack aborts instantly;
        // everything else gets the default icmp6-port-unreachable.
        val ipv6Script = buildString {
            appendLine("ip6tables -N $chain6 || exit 1")

            appendLine("ip6tables -A $chain6 -m owner --uid-owner $selfUid -j RETURN")
            appendLine("ip6tables -A $chain6 -o lo -j RETURN")
            for (cidr in LAN_BYPASS_V6) {
                appendLine("ip6tables -A $chain6 -d $cidr -j RETURN")
            }

            if (!perAppEnabled) {
                appendLine("ip6tables -A $chain6 -p tcp -j REJECT --reject-with tcp-reset")
                appendLine("ip6tables -A $chain6 -j REJECT")
            } else if (bypassMode) {
                // Listed apps skip both the v4 proxy and the v6 rejection.
                for (uid in selectedUids) {
                    appendLine("ip6tables -A $chain6 -m owner --uid-owner $uid -j RETURN")
                }
                appendLine("ip6tables -A $chain6 -p tcp -j REJECT --reject-with tcp-reset")
                appendLine("ip6tables -A $chain6 -j REJECT")
            } else {
                // Only the listed apps are proxied on v4 — their v6
                // traffic must be rejected to prevent a leak. Everything
                // else keeps its normal v6 path.
                for (uid in selectedUids) {
                    appendLine(
                        "ip6tables -A $chain6 -p tcp -m owner --uid-owner $uid -j REJECT --reject-with tcp-reset"
                    )
                    appendLine(
                        "ip6tables -A $chain6 -m owner --uid-owner $uid -j REJECT"
                    )
                }
            }

            appendLine("ip6tables -I OUTPUT -j $chain6")
        }

        val ipv6 = RootShell.exec(ipv6Script)
        if (!ipv6.ok) {
            Log.w(
                AppConfig.TAG,
                "StartCore-Root: ip6tables install failed, continuing without v6 block: ${ipv6.stderr}",
            )
        } else {
            Log.i(AppConfig.TAG, "StartCore-Root: ip6tables (v6) rules installed")
        }

        // --- IPv4 UDP leak block (filter table, MUST succeed) ---
        // The nat-table REDIRECT only touches TCP, which means Chrome /
        // Google services happily fall back to QUIC over UDP/443 and
        // reach the internet with the real source IP. WebRTC, gQUIC,
        // MASQUE and similar UDP transports have the same problem. We
        // REJECT every UDP flow except DNS (already caught by the
        // UDP/53 REDIRECT above) and loopback / LAN traffic, so apps
        // instantly fall back to their TCP transport — which is then
        // picked up by the nat REDIRECT as usual. Without this, the
        // user sees their real IP on `myip` / Google search.
        val ipv4UdpScript = buildString {
            appendLine("set -e")
            appendLine("iptables -N $chainUdp")

            // Xray's own outbound sockets (may include UDP to the
            // proxy server — e.g. QUIC upstream) must escape the
            // filter.
            appendLine("iptables -A $chainUdp -m owner --uid-owner $selfUid -j RETURN")
            appendLine("iptables -A $chainUdp -o lo -j RETURN")
            for (cidr in LAN_BYPASS_V4) {
                appendLine("iptables -A $chainUdp -d $cidr -j RETURN")
            }
            // DNS was already REDIRECTed in nat (when applicable) and
            // then rewritten to loopback, so it would already have been
            // RETURNed by the `-o lo` rule above. But apps that didn't
            // hit the REDIRECT (e.g. per-app modes) still need UDP/53
            // to reach the system resolver — let it through explicitly.
            appendLine("iptables -A $chainUdp -p udp --dport 53 -j RETURN")

            if (!perAppEnabled) {
                appendLine("iptables -A $chainUdp -p udp -j REJECT --reject-with icmp-port-unreachable")
            } else if (bypassMode) {
                // "Bypass selected" — let the listed apps' UDP escape,
                // block everyone else.
                for (uid in selectedUids) {
                    appendLine("iptables -A $chainUdp -m owner --uid-owner $uid -j RETURN")
                }
                appendLine("iptables -A $chainUdp -p udp -j REJECT --reject-with icmp-port-unreachable")
            } else {
                // "Proxy only selected" — block UDP for those uids so
                // their traffic can't leak around the TCP-only REDIRECT.
                for (uid in selectedUids) {
                    appendLine(
                        "iptables -A $chainUdp -p udp -m owner --uid-owner $uid -j REJECT --reject-with icmp-port-unreachable"
                    )
                }
            }

            appendLine("iptables -I OUTPUT -j $chainUdp")
        }
        val ipv4Udp = RootShell.exec(ipv4UdpScript)
        if (!ipv4Udp.ok) {
            Log.e(
                AppConfig.TAG,
                "StartCore-Root: UDP leak block install failed: ${ipv4Udp.stderr}",
            )
            return false
        }
        Log.i(AppConfig.TAG, "StartCore-Root: UDP leak block installed")

        // --- Tether sharing (best effort) ---
        // When the user opts in, also redirect TCP and UDP/53 from
        // tethered / forwarded clients into the same dokodemo-door
        // inbounds. PREROUTING is the right hook because it sees packets
        // before the routing decision; iptables NAT REDIRECT rewrites the
        // destination to the AP gateway IP, where the inbounds are bound
        // because root-mode tether sharing flips the listen address to
        // 0.0.0.0 in V2rayConfigManager.
        if (tetherSharing) {
            val preScript = buildString {
                appendLine("set -e")
                appendLine("iptables -t nat -N $chainPre")

                // Skip traffic coming from loopback.
                appendLine("iptables -t nat -A $chainPre -i lo -j RETURN")

                // DNS MUST be redirected BEFORE the LAN bypass below.
                // Tethered clients receive the phone's hotspot IP as
                // their DNS server via DHCP (e.g. 192.168.43.1), so
                // their DNS queries are destined to a local RFC1918
                // address. If LAN_BYPASS_V4 RETURNs first, DNS lands in
                // Android's built-in dnsmasq and never touches the
                // proxy — which is exactly the "tether sharing doesn't
                // work" symptom. Forcing UDP/53 through REDIRECT here
                // routes the query into our dokodemo-door DNS inbound
                // and then through xray's dns-out outbound.
                appendLine("iptables -t nat -A $chainPre -p udp --dport 53 -j REDIRECT --to-ports $dnsRedirectPort")

                // Skip LAN / multicast / link-local destinations so
                // intra-hotspot services (ARP, mDNS, hotspot gateway
                // itself for non-DNS traffic) keep working. Note that
                // `-m addrtype` was intentionally dropped: the module
                // is not always built into stock Android kernels and
                // `set -e` would otherwise abort the whole install.
                for (cidr in LAN_BYPASS_V4) {
                    appendLine("iptables -t nat -A $chainPre -d $cidr -j RETURN")
                }
                appendLine("iptables -t nat -A $chainPre -p tcp -j REDIRECT --to-ports $redirectPort")

                appendLine("iptables -t nat -I PREROUTING -j $chainPre")
            }
            val pre = RootShell.exec(preScript)
            if (!pre.ok) {
                Log.w(
                    AppConfig.TAG,
                    "StartCore-Root: tether PREROUTING install failed: ${pre.stderr}",
                )
            } else {
                Log.i(AppConfig.TAG, "StartCore-Root: tether PREROUTING rules installed")
            }

            // Some Android builds leave the filter INPUT chain with
            // bw_INPUT / fw_INPUT sub-chains that quietly DROP traffic
            // targeted at non-loopback high ports. After REDIRECT the
            // packet is destined to the AP-interface IP on our inbound
            // port, so explicitly ACCEPT it to bypass those restrictions
            // — best-effort, unhooked on stop.
            val inputScript = buildString {
                appendLine("iptables -N $chainInput || exit 1")
                appendLine("iptables -A $chainInput -p tcp --dport $redirectPort -j ACCEPT")
                appendLine("iptables -A $chainInput -p udp --dport $dnsRedirectPort -j ACCEPT")
                appendLine("iptables -I INPUT -j $chainInput")
            }
            val inp = RootShell.exec(inputScript)
            if (!inp.ok) {
                Log.w(
                    AppConfig.TAG,
                    "StartCore-Root: tether INPUT accept failed: ${inp.stderr}",
                )
            } else {
                Log.i(AppConfig.TAG, "StartCore-Root: tether INPUT accept installed")
            }

            // Same QUIC/WebRTC leak fix as on the phone itself, but
            // applied to forwarded packets from tethered clients. After
            // the PREROUTING DNS REDIRECT the packet is delivered
            // locally and no longer traverses FORWARD; TCP is likewise
            // consumed by the TCP REDIRECT in PREROUTING. Everything
            // that still reaches FORWARD is UDP-other-than-DNS, which
            // we cannot proxy — so we REJECT it to force tethered
            // clients to retry over TCP (which *is* proxied).
            val fwd4Script = buildString {
                appendLine("iptables -N $chainFwd || exit 1")
                appendLine("iptables -A $chainFwd -o lo -j RETURN")
                for (cidr in LAN_BYPASS_V4) {
                    appendLine("iptables -A $chainFwd -d $cidr -j RETURN")
                }
                appendLine("iptables -A $chainFwd -p udp --dport 53 -j RETURN")
                appendLine("iptables -A $chainFwd -p udp -j REJECT --reject-with icmp-port-unreachable")
                appendLine("iptables -I FORWARD -j $chainFwd")
            }
            val fwd4 = RootShell.exec(fwd4Script)
            if (!fwd4.ok) {
                Log.w(
                    AppConfig.TAG,
                    "StartCore-Root: tether FORWARD v4 UDP reject failed: ${fwd4.stderr}",
                )
            } else {
                Log.i(AppConfig.TAG, "StartCore-Root: tether FORWARD v4 UDP reject installed")
            }

            // IPv6 has no NAT66 path, so we cannot transparently proxy
            // forwarded v6 traffic. To prevent tethered clients from
            // leaking via Happy Eyeballs we reject forwarded v6 outright.
            // Scoped to a dedicated chain hooked into FORWARD only when
            // tether sharing is on, so it does not affect ordinary
            // (non-shared) tethering when the feature is disabled.
            val fwd6Script = buildString {
                appendLine("ip6tables -N $chainFwd6 || exit 1")
                appendLine("ip6tables -A $chainFwd6 -p tcp -j REJECT --reject-with tcp-reset")
                appendLine("ip6tables -A $chainFwd6 -j REJECT")
                appendLine("ip6tables -I FORWARD -j $chainFwd6")
            }
            val fwd6 = RootShell.exec(fwd6Script)
            if (!fwd6.ok) {
                Log.w(
                    AppConfig.TAG,
                    "StartCore-Root: tether FORWARD v6 reject failed: ${fwd6.stderr}",
                )
            } else {
                Log.i(AppConfig.TAG, "StartCore-Root: tether FORWARD v6 reject installed")
            }
        }

        return true
    }

    /**
     * Translate a set of package names into the kernel-level UIDs they run
     * as. Unknown packages are silently dropped. Duplicate UIDs (e.g. an
     * app with multiple split APKs) are de-duplicated.
     */
    private fun resolveUidsForPackages(packages: Set<String>): List<Int> {
        if (packages.isEmpty()) return emptyList()
        val pm = packageManager
        val uids = LinkedHashSet<Int>()
        for (pkg in packages) {
            try {
                uids.add(pm.getApplicationInfo(pkg, 0).uid)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(AppConfig.TAG, "StartCore-Root: package not installed, skipping: $pkg")
            }
        }
        return uids.toList()
    }

    private fun removeIptablesRules() {
        // No rulesInstalled guard: every step of the purge script is
        // `-D ... || true` so it is idempotent, and running it
        // unconditionally lets us clean up even when install() aborted
        // mid-script. The same script is also used as a pre-flight purge
        // at start so leftover chains from a crash or OOM-kill of the
        // previous session are guaranteed to be removed.
        val result = RootShell.exec(buildPurgeScript())
        if (!result.ok) {
            Log.w(AppConfig.TAG, "StartCore-Root: iptables cleanup reported errors: ${result.stderr}")
        }
        rulesInstalled = false
    }

    /**
     * Shell script that unhooks and deletes every iptables / ip6tables
     * chain this service may ever have installed: the OUTPUT NAT chains
     * (both v4 and v6), the tether PREROUTING/INPUT chains (v4), and the
     * tether FORWARD reject chain (v6). Every step is a silent no-op if
     * the chain or hook isn't present, so the script is safe to run from
     * both stop and pre-start paths without any "was it installed?"
     * bookkeeping.
     */
    private fun buildPurgeScript(): String {
        val chain = IPTABLES_CHAIN
        val chain6 = IPTABLES_CHAIN_V6
        val chainUdp = IPTABLES_CHAIN_UDP
        val chainPre = IPTABLES_CHAIN_PRE
        val chainInput = IPTABLES_CHAIN_INPUT
        val chainFwd = IPTABLES_CHAIN_FWD
        val chainFwd6 = IPTABLES_CHAIN_FWD6
        return buildString {
            // IPv4 nat OUTPUT
            appendLine("iptables -t nat -D OUTPUT -j $chain 2>/dev/null || true")
            appendLine("iptables -t nat -F $chain 2>/dev/null || true")
            appendLine("iptables -t nat -X $chain 2>/dev/null || true")
            // IPv4 nat PREROUTING (tether sharing)
            appendLine("iptables -t nat -D PREROUTING -j $chainPre 2>/dev/null || true")
            appendLine("iptables -t nat -F $chainPre 2>/dev/null || true")
            appendLine("iptables -t nat -X $chainPre 2>/dev/null || true")
            // IPv4 filter OUTPUT (UDP leak block)
            appendLine("iptables -D OUTPUT -j $chainUdp 2>/dev/null || true")
            appendLine("iptables -F $chainUdp 2>/dev/null || true")
            appendLine("iptables -X $chainUdp 2>/dev/null || true")
            // IPv4 filter INPUT accept chain (tether sharing)
            appendLine("iptables -D INPUT -j $chainInput 2>/dev/null || true")
            appendLine("iptables -F $chainInput 2>/dev/null || true")
            appendLine("iptables -X $chainInput 2>/dev/null || true")
            // IPv4 filter FORWARD reject chain (tether sharing)
            appendLine("iptables -D FORWARD -j $chainFwd 2>/dev/null || true")
            appendLine("iptables -F $chainFwd 2>/dev/null || true")
            appendLine("iptables -X $chainFwd 2>/dev/null || true")
            // IPv6 filter OUTPUT
            appendLine("ip6tables -D OUTPUT -j $chain6 2>/dev/null || true")
            appendLine("ip6tables -F $chain6 2>/dev/null || true")
            appendLine("ip6tables -X $chain6 2>/dev/null || true")
            // IPv6 filter FORWARD (tether sharing)
            appendLine("ip6tables -D FORWARD -j $chainFwd6 2>/dev/null || true")
            appendLine("ip6tables -F $chainFwd6 2>/dev/null || true")
            appendLine("ip6tables -X $chainFwd6 2>/dev/null || true")
        }
    }

    companion object {
        private const val IPTABLES_CHAIN = "V2RAYNG_OUT"
        private const val IPTABLES_CHAIN_V6 = "V2RAYNG_OUT6"
        private const val IPTABLES_CHAIN_UDP = "V2RAYNG_UDP"
        private const val IPTABLES_CHAIN_PRE = "V2RAYNG_PRE"
        private const val IPTABLES_CHAIN_INPUT = "V2RAYNG_IN"
        private const val IPTABLES_CHAIN_FWD = "V2RAYNG_FWD"
        private const val IPTABLES_CHAIN_FWD6 = "V2RAYNG_FWD6"

        private val LAN_BYPASS_V4 = listOf(
            "0.0.0.0/8",
            "10.0.0.0/8",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "224.0.0.0/4",
            "240.0.0.0/4",
        )

        private val LAN_BYPASS_V6 = listOf(
            "::1/128",        // loopback
            "fe80::/10",      // link-local
            "fc00::/7",       // unique local
            "ff00::/8",       // multicast
        )
    }
}
