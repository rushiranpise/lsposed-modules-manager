package com.rushi.modulesmanager.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

object ApkInstaller {
    const val EXTRA_PACKAGE_NAME = "package_name"

    private const val SESSION_PREFS = "install_session"
    private const val KEY_SESSION_ID = "session_id"

    /** The view model currently waiting for the result, if the process is still alive. */
    private var pendingResult: ((status: Int, message: String?) -> Unit)? = null

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Opens the APK in the system package installer. */
    fun installSystem(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * Streams the APK into a PackageInstaller session and commits it to the manifest receiver.
     * The receiver survives this process, so the result is not lost while the installer UI is up.
     */
    suspend fun installSession(
        context: Context,
        apk: File,
        packageName: String,
        onResult: (status: Int, message: String?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        abandonPendingSession(appContext)
        pendingResult = onResult
        try {
            val installer = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply {
                    setSize(apk.length())
                    // Android 12+ skips the confirmation for updates to apps this installer
                    // installed. Fresh installs still go through STATUS_PENDING_USER_ACTION.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        setRequestUpdateOwnership(true)
                    }
                }
            val sessionId = installer.createSession(params)
            storeSessionId(appContext, sessionId)

            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }

                val resultIntent = Intent(appContext, InstallResultReceiver::class.java)
                    .putExtra(EXTRA_PACKAGE_NAME, packageName)
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    resultIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            pendingResult = null
            clearSessionId(appContext)
            onResult(PackageInstaller.STATUS_FAILURE, e.message)
        }
    }

    /** Called by [InstallResultReceiver] once the session reaches a final status. */
    fun deliverResult(context: Context, status: Int, message: String?) {
        clearSessionId(context)
        val callback = pendingResult
        pendingResult = null
        callback?.invoke(status, message)
    }

    /** Abandons a session left behind by a previous install, so it cannot block a new one. */
    fun abandonPendingSession(context: Context) {
        val sessionId = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SESSION_ID, -1)
        if (sessionId != -1) {
            runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            clearSessionId(context)
        }
        pendingResult = null
    }

    private fun storeSessionId(context: Context, sessionId: Int) {
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SESSION_ID, sessionId).apply()
    }

    private fun clearSessionId(context: Context) {
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_SESSION_ID).apply()
    }
}

/** Shared, single-slot download status so any screen can show what is currently downloading. */
class DownloadCenter {
    data class State(
        val packageName: String,
        val label: String,
        val fileName: String,
        val downloaded: Long,
        val total: Long,
        val phase: Phase,
    ) {
        val fraction: Float?
            get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
    }

    enum class Phase { DOWNLOADING, VERIFYING, INSTALLING }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    fun update(state: State?) {
        _state.value = state
    }

    fun progress(downloaded: Long, total: Long) {
        _state.value = _state.value?.copy(downloaded = downloaded, total = total)
    }

    fun phase(phase: Phase) {
        _state.value = _state.value?.copy(phase = phase)
    }
}
