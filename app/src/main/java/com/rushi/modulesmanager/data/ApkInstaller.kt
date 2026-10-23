package com.rushi.modulesmanager.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object ApkInstaller {
    const val ACTION_INSTALL_RESULT = "com.rushi.modulesmanager.INSTALL_RESULT"

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

    /** Streams the APK into a PackageInstaller session and lets the system confirm it. */
    fun installSession(context: Context, apk: File, onResult: (status: Int, message: String?) -> Unit) {
        val appContext = context.applicationContext
        var receiver: BroadcastReceiver? = null
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

            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }

                receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

                        // The installer asks us to open its confirmation screen before it can continue.
                        // The final result arrives in a second broadcast, so keep listening.
                        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            val confirmation = IntentCompat.getParcelableExtra(
                                intent,
                                Intent.EXTRA_INTENT,
                                Intent::class.java,
                            )
                            if (confirmation != null) {
                                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { appContext.startActivity(confirmation) }
                                    .onFailure { onResult(PackageInstaller.STATUS_FAILURE, it.message) }
                            } else {
                                onResult(PackageInstaller.STATUS_FAILURE, "No installer confirmation was provided")
                            }
                            return
                        }

                        runCatching { appContext.unregisterReceiver(this) }
                        onResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                    }
                }
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    IntentFilter(ACTION_INSTALL_RESULT),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )

                val resultIntent = Intent(ACTION_INSTALL_RESULT).setPackage(appContext.packageName)
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    resultIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
            onResult(PackageInstaller.STATUS_FAILURE, e.message)
        }
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
