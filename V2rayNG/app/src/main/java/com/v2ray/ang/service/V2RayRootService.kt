package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
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
        val uid = applicationInfo.uid
        val redirectPort = V2rayConfigManager.getRedirectPort()
        val chain = IPTABLES_CHAIN

        // Build a script that wipes any previous rules from a prior run,
        // creates our chain, fills it, and hooks it into OUTPUT.
        val script = buildString {
            appendLine("set -e")
            // Best-effort cleanup of any leftover rules from a previous run.
            appendLine("iptables -t nat -D OUTPUT -j $chain 2>/dev/null || true")
            appendLine("iptables -t nat -F $chain 2>/dev/null || true")
            appendLine("iptables -t nat -X $chain 2>/dev/null || true")

            appendLine("iptables -t nat -N $chain")

            // Don't loop our own traffic.
            appendLine("iptables -t nat -A $chain -m owner --uid-owner $uid -j RETURN")

            // Leave loopback traffic alone.
            appendLine("iptables -t nat -A $chain -o lo -j RETURN")

            // Skip LAN / multicast / link-local so local network still works.
            for (cidr in LAN_BYPASS_V4) {
                appendLine("iptables -t nat -A $chain -d $cidr -j RETURN")
            }

            // Redirect everything else to the dokodemo-door inbound.
            appendLine("iptables -t nat -A $chain -p tcp -j REDIRECT --to-ports $redirectPort")

            // Hook into OUTPUT.
            appendLine("iptables -t nat -I OUTPUT -j $chain")
        }

        val result = RootShell.exec(script)
        if (!result.ok) {
            Log.e(AppConfig.TAG, "StartCore-Root: iptables install failed: ${result.stderr}")
            return false
        }
        rulesInstalled = true
        Log.i(AppConfig.TAG, "StartCore-Root: iptables rules installed")
        return true
    }

    private fun removeIptablesRules() {
        if (!rulesInstalled) return
        val chain = IPTABLES_CHAIN
        val script = buildString {
            appendLine("iptables -t nat -D OUTPUT -j $chain 2>/dev/null || true")
            appendLine("iptables -t nat -F $chain 2>/dev/null || true")
            appendLine("iptables -t nat -X $chain 2>/dev/null || true")
        }
        val result = RootShell.exec(script)
        if (!result.ok) {
            Log.w(AppConfig.TAG, "StartCore-Root: iptables cleanup reported errors: ${result.stderr}")
        }
        rulesInstalled = false
    }

    companion object {
        private const val IPTABLES_CHAIN = "V2RAYNG_OUT"

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
    }
}
