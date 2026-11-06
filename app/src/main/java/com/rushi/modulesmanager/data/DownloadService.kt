package com.rushi.modulesmanager.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rushi.modulesmanager.AppContainer
import com.rushi.modulesmanager.BatchUpdateState
import com.rushi.modulesmanager.MainActivity
import com.rushi.modulesmanager.ModulesManagerApplication
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps APK transfers alive in the background and mirrors their progress into
 * the foreground notification. Manual APK saves run in this service's scope;
 * installs and batches continue to run elsewhere and are only observed here.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private var saveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        container = (application as ModulesManagerApplication).container
        startForeground(NOTIFICATION_ID, buildNotification(null, null, null))
        scope.launch {
            combine(
                container.downloads.state,
                container.batchUpdate,
                container.apkSaveCenter.state,
            ) { download, batch, save ->
                Triple(download, batch?.takeIf { it.running }, save)
            }.collect { (download, batch, save) ->
                if (download == null && batch == null && save == null) {
                    stopSelf()
                } else {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, buildNotification(download, batch, save))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SAVE -> launchSave()
            ACTION_CANCEL_SAVE -> cancelSave()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        container.apkSaveCenter.cancel()
        saveJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun launchSave() {
        val request = container.apkSaveCenter.currentRequest() ?: return
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            var result: String? = null
            var cancelled = false
            try {
                result = executeSave(request)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Exception) {
                result = e.userMessage()
            } finally {
                request.destination.delete()
                File(request.destination.path + ".part").delete()
                // Release the job before the state so a new save can start cleanly.
                saveJob = null
                if (cancelled) {
                    container.apkSaveCenter.cancel()
                } else {
                    container.apkSaveCenter.finish(result ?: "Could not save ${request.label}")
                }
            }
        }
    }

    private suspend fun executeSave(request: ApkSaveCenter.Request): String {
        val file = container.downloadSlots.withSlot {
            container.apkSaveCenter.phase(ApkSaveCenter.State.Phase.DOWNLOADING)
            container.api.download(
                urls = listOfNotNull(request.downloadUrl, request.originalDownloadUrl),
                destination = request.destination,
                onProgress = { downloaded, total ->
                    container.apkSaveCenter.progress(downloaded, total)
                },
            )
        }
        ApkInspector.validateDownloaded(this, file, request.packageName)
        container.apkSaveCenter.phase(ApkSaveCenter.State.Phase.SAVING)
        return if (container.apkExportStore.export(file, request.exportName)) {
            "Saved to Download/ModulesManager"
        } else {
            "Could not save ${request.label}"
        }
    }

    private fun cancelSave() {
        val job = saveJob
        if (job != null && job.isActive) {
            job.cancel()
        } else {
            container.apkSaveCenter.cancel()
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "APK download, save and batch update progress"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(
        download: DownloadCenter.State?,
        batch: BatchUpdateState?,
        save: ApkSaveCenter.State?,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        when {
            save != null -> {
                builder
                    .setContentTitle("Saving ${save.label}")
                    .setContentText(
                        when (save.phase) {
                            ApkSaveCenter.State.Phase.PREPARING -> "Preparing the download"
                            ApkSaveCenter.State.Phase.DOWNLOADING -> {
                                val total = if (save.total > 0L) {
                                    formatBytes(save.total)
                                } else {
                                    "unknown size"
                                }
                                "${formatBytes(save.downloaded)} of $total"
                            }
                            ApkSaveCenter.State.Phase.SAVING -> "Saving to Download/ModulesManager"
                        },
                    )
                val fraction = save.fraction
                when {
                    save.phase == ApkSaveCenter.State.Phase.SAVING ->
                        builder.setProgress(100, 100, false)
                    save.phase == ApkSaveCenter.State.Phase.DOWNLOADING && fraction != null ->
                        builder.setProgress(100, (fraction * 100).toInt(), false)
                    else -> builder.setProgress(0, 0, true)
                }
                if (save.phase != ApkSaveCenter.State.Phase.SAVING) {
                    builder.addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Cancel",
                        cancelSavePendingIntent(),
                    )
                }
            }
            download != null -> {
                builder
                    .setContentTitle(download.label)
                    .setContentText(
                        if (download.paused) {
                            "Paused - ${download.fileName}"
                        } else {
                            download.fileName
                        },
                    )
                val fraction = download.fraction
                if (fraction != null) {
                    builder.setProgress(100, (fraction * 100).toInt(), false)
                } else {
                    builder.setProgress(0, 0, true)
                }
            }
            batch != null -> {
                val fraction = if (batch.total > 0) {
                    batch.finished.toFloat() / batch.total
                } else {
                    0f
                }
                builder
                    .setContentTitle(batch.current?.let { "Updating $it" } ?: "Updating modules")
                    .setContentText("${batch.finished} of ${batch.total} finished")
                    .setProgress(100, (fraction * 100).toInt(), false)
            }
            else -> {
                builder
                    .setContentTitle("Preparing download")
                    .setContentText("Starting the transfer")
                    .setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    private fun cancelSavePendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_CANCEL_SAVE,
        Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_SAVE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 42
        private const val REQUEST_CANCEL_SAVE = 1
        private const val ACTION_SAVE = "com.rushi.modulesmanager.action.SAVE_APK"
        private const val ACTION_CANCEL_SAVE = "com.rushi.modulesmanager.action.CANCEL_SAVE_APK"

        fun start(context: Context): Boolean = start(
            context,
            Intent(context, DownloadService::class.java),
        )

        fun startSave(context: Context): Boolean = start(
            context,
            Intent(context, DownloadService::class.java).setAction(ACTION_SAVE),
        )

        fun cancelSave(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL_SAVE),
                )
            }
        }

        private fun start(context: Context, intent: Intent): Boolean = runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.isSuccess
    }
}
