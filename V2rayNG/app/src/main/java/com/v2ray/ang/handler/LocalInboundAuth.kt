package com.v2ray.ang.handler

import android.util.Base64
import com.v2ray.ang.AppConfig
import java.security.SecureRandom

/**
 * Local SOCKS/HTTP inbound credentials.
 *
 * - **VPN mode:** new random user/password on each core start (memory only).
 * - **Proxy-only mode:** stable user/password stored in settings until the user switches back to VPN mode.
 */
object LocalInboundAuth {
    private val secureRandom = SecureRandom()

    @Volatile
    private var user: String? = null

    @Volatile
    private var pass: String? = null

    /**
     * Call before building config for a new core run.
     */
    fun prepareForCoreStart() {
        if (SettingsManager.isVpnMode()) {
            user = randomToken(12)
            pass = randomToken(18)
        } else {
            ensureProxyOnlyCredentialsInMmkv()
            user = MmkvManager.decodeSettingsString(AppConfig.PREF_LOCAL_PROXY_AUTH_USER)
            pass = MmkvManager.decodeSettingsString(AppConfig.PREF_LOCAL_PROXY_AUTH_PASS)
        }
    }

    /**
     * Creates proxy-only login/password in MMKV if missing. Does not check current mode — call only when
     * mode has already been stored as proxy-only (e.g. from [MmkvPreferenceDataStore.putString]).
     */
    fun ensureProxyOnlyCredentialsInMmkv() {
        val u = MmkvManager.decodeSettingsString(AppConfig.PREF_LOCAL_PROXY_AUTH_USER)
        val p = MmkvManager.decodeSettingsString(AppConfig.PREF_LOCAL_PROXY_AUTH_PASS)
        if (u.isNullOrEmpty() || p.isNullOrEmpty()) {
            MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_PROXY_AUTH_USER, randomToken(12))
            MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_PROXY_AUTH_PASS, randomToken(18))
        }
    }

    /**
     * Ensures proxy-only credentials exist when opening Settings while already in proxy-only mode.
     */
    fun ensurePersistentProxyCredentialsInStorage() {
        if (SettingsManager.isVpnMode()) return
        ensureProxyOnlyCredentialsInMmkv()
    }

    /**
     * Clears stored proxy-only credentials (call when switching to VPN mode).
     * Does not clear in-memory session of a running core.
     */
    fun clearPersistentProxyCredentials() {
        MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_PROXY_AUTH_USER, null)
        MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_PROXY_AUTH_PASS, null)
    }

    fun clearSession() {
        user = null
        pass = null
    }

    fun sessionCredentials(): Pair<String, String>? {
        val u = user ?: return null
        val p = pass ?: return null
        return u to p
    }

    /**
     * Reads stored proxy-only credentials (no isVpnMode guard: in VPN mode keys are cleared when switching).
     */
    fun persistentProxyCredentials(): Pair<String, String>? {
        val u = MmkvManager.decodeSettingsString(AppConfig.PREF_LOCAL_PROXY_AUTH_USER) ?: return null
        val p = MmkvManager.decodeSettingsString(AppConfig.PREF_LOCAL_PROXY_AUTH_PASS) ?: return null
        if (u.isEmpty() || p.isEmpty()) return null
        return u to p
    }

    private fun randomToken(numBytes: Int): String {
        val buf = ByteArray(numBytes)
        secureRandom.nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
