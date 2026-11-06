package com.rushi.modulesmanager.data

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Installs through a root shell. This is the only fully silent path for fresh installs when
 * Shizuku is not running; the root manager asks for permission the first time.
 */
object RootInstaller {

    /**
     * Opens a root shell with a hard timeout, then reports whether access was granted.
     * The process is destroyed on timeout so a stuck root manager cannot block the
     * installer selection forever.
     */
    suspend fun isAvailable(timeoutMs: Long = ROOT_CHECK_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true)
                    .start()
                try {
                    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        return@runCatching false
                    }
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    process.exitValue() == 0 && output.contains("uid=0")
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }.getOrDefault(false)
        }

    /** Returns null on success, or the shell output describing the failure. */
    suspend fun install(apk: File, installerPackage: String): String? = withContext(Dispatchers.IO) {
        val size = apk.length()
        if (size <= 0L) return@withContext "APK is empty"

        // Pipe the APK into pm so the shell never needs a readable path to app-private storage.
        // -i records this app as the installer, which keeps later updates promptless.
        val command = "cat ${apk.absolutePath.shellQuoted()} | " +
            "pm install --user current -i ${installerPackage.shellQuoted()} -t -r -S $size"
        val result = runCatching { Shell.cmd(command).exec() }.getOrElse {
            return@withContext it.message ?: "Could not run the root install"
        }
        val output = (result.out + result.err).joinToString("\n").trim()
        if (result.isSuccess && output.contains("Success", ignoreCase = true)) {
            null
        } else {
            output.ifBlank { "Root install failed" }
        }
    }

    /** Returns null on success, or the shell output describing the failure. */
    suspend fun uninstall(packageName: String): String? = withContext(Dispatchers.IO) {
        val result = runCatching {
            Shell.cmd("pm uninstall --user current ${packageName.shellQuoted()}").exec()
        }.getOrElse {
            return@withContext it.message ?: "Could not run the root uninstall"
        }
        val output = (result.out + result.err).joinToString("\n").trim()
        if (result.isSuccess && output.contains("Success", ignoreCase = true)) {
            null
        } else {
            output.ifBlank { "Root uninstall failed" }
        }
    }

    private fun String.shellQuoted(): String = "'" + replace("'", "'\\''") + "'"

    private const val ROOT_CHECK_TIMEOUT_MS = 15_000L
}
