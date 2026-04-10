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
        val chain = IPTABLES_CHAIN
        val chain6 = IPTABLES_CHAIN_V6

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
            "StartCore-Root: perAppEnabled=$perAppEnabled bypassMode=$bypassMode uids=$selectedUids",
        )

        // --- IPv4 (nat table, MUST succeed) ---
        val ipv4Script = buildString {
            appendLine("set -e")
            // Best-effort cleanup of any leftover rules from a previous run.
            appendLine("iptables -t nat -D OUTPUT -j $chain 2>/dev/null || true")
            appendLine("iptables -t nat -F $chain 2>/dev/null || true")
            appendLine("iptables -t nat -X $chain 2>/dev/null || true")

            appendLine("iptables -t nat -N $chain")

            // Don't loop our own traffic.
            appendLine("iptables -t nat -A $chain -m owner --uid-owner $selfUid -j RETURN")

            // Leave loopback traffic alone.
            appendLine("iptables -t nat -A $chain -o lo -j RETURN")

            // Skip LAN / multicast / link-local so local network still works.
            for (cidr in LAN_BYPASS_V4) {
                appendLine("iptables -t nat -A $chain -d $cidr -j RETURN")
            }

            // Only TCP is redirected. UDP-53 DNS is intentionally NOT
            // touched: on modern Android, DNS queries for many apps are
            // proxied by netd/system uids, which our owner-based per-app
            // rules cannot attribute to the originating app. Forcing them
            // through the proxy broke bypass apps (their DNS would go
            // through the core, which may or may not forward UDP cleanly).
            // Users who want encrypted DNS should enable Android Private
            // DNS (DoT over TCP 853) — that falls under the TCP REDIRECT
            // below and tunnels through the proxy naturally.
            if (!perAppEnabled) {
                appendLine("iptables -t nat -A $chain -p tcp -j REDIRECT --to-ports $redirectPort")
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
            appendLine("ip6tables -D OUTPUT -j $chain6 2>/dev/null || true")
            appendLine("ip6tables -F $chain6 2>/dev/null || true")
            appendLine("ip6tables -X $chain6 2>/dev/null || true")

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
        if (!rulesInstalled) return
        val chain = IPTABLES_CHAIN
        val chain6 = IPTABLES_CHAIN_V6
        val script = buildString {
            // IPv4
            appendLine("iptables -t nat -D OUTPUT -j $chain 2>/dev/null || true")
            appendLine("iptables -t nat -F $chain 2>/dev/null || true")
            appendLine("iptables -t nat -X $chain 2>/dev/null || true")
            // IPv6 (no-op if ip6tables or chain is missing)
            appendLine("ip6tables -D OUTPUT -j $chain6 2>/dev/null || true")
            appendLine("ip6tables -F $chain6 2>/dev/null || true")
            appendLine("ip6tables -X $chain6 2>/dev/null || true")
        }
        val result = RootShell.exec(script)
        if (!result.ok) {
            Log.w(AppConfig.TAG, "StartCore-Root: iptables cleanup reported errors: ${result.stderr}")
        }
        rulesInstalled = false
    }

    companion object {
        private const val IPTABLES_CHAIN = "V2RAYNG_OUT"
        private const val IPTABLES_CHAIN_V6 = "V2RAYNG_OUT6"

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
