package com.v2ray.ang.util

import android.util.Log
import com.v2ray.ang.AppConfig
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Minimal helper for running shell commands as root via the `su` binary.
 *
 * Only used by [com.v2ray.ang.service.V2RayRootService] to install and remove
 * iptables rules for transparent TCP redirection. Kept deliberately tiny so we
 * don't pull in libsu as a dependency.
 */
object RootShell {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /**
     * Execute the given shell script inside a single `su -` session.
     * Lines are fed one-by-one to the root shell, then the session is closed
     * and we wait for it to exit. Returns the combined stdout/stderr and the
     * exit code of the `su` process itself.
     */
    fun exec(script: String): Result {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su"))
            val stdin = DataOutputStream(process.outputStream)
            stdin.writeBytes(script)
            if (!script.endsWith("\n")) stdin.writeBytes("\n")
            stdin.writeBytes("exit\n")
            stdin.flush()
            stdin.close()

            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
            val code = process.waitFor()
            Result(code, stdout, stderr)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "RootShell exec failed: ${e.message}")
            Result(-1, "", e.message.orEmpty())
        } finally {
            process?.destroy()
        }
    }

    /**
     * Quick probe for root availability. Runs `id` and looks for uid=0.
     * Returns false on any failure (missing `su`, denied prompt, etc.).
     */
    fun isAvailable(): Boolean {
        val result = exec("id")
        return result.ok && result.stdout.contains("uid=0")
    }
}
