package com.v2ray.ang.handler

import android.util.Base64
import java.security.SecureRandom

/**
 * Ephemeral credentials for the local SOCKS/HTTP inbounds. Regenerated for each VPN/proxy session
 * so other apps cannot reuse a fixed localhost password.
 */
object LocalInboundAuth {
    private val secureRandom = SecureRandom()

    @Volatile
    private var user: String? = null

    @Volatile
    private var pass: String? = null

    fun regenerateSession() {
        user = randomToken(12)
        pass = randomToken(18)
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

    private fun randomToken(numBytes: Int): String {
        val buf = ByteArray(numBytes)
        secureRandom.nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
