package com.rushi.modulesmanager.data

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs through a root shell. This is the only fully silent path for fresh installs when
 * Shizuku is not running; the root manager asks for permission the first time.
 */
object RootInstaller {

    /** Opens the shell so the root manager can ask for access, then reports whether it was granted. */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Shell.getCachedShell() ?: Shell.getShell()
            Shell.isAppGrantedRoot() == true
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

    private fun String.shellQuoted(): String = "'" + replace("'", "'\\''") + "'"
}
