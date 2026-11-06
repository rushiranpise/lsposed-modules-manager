package com.rushi.modulesmanager.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rushi.modulesmanager.BatchUpdateState
import com.rushi.modulesmanager.MainActivity
import com.rushi.modulesmanager.ModulesManagerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while an APK or a batch is downloading and mirrors
 * [DownloadCenter.State] or [BatchUpdateState] progress into the foreground notification.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val container = (application as ModulesManagerApplication).container
        startForeground(NOTIFICATION_ID, buildNotification(null, null))
        scope.launch {
            combine(container.downloads.state, container.batchUpdate) { download, batch ->
                download to batch?.takeIf { it.running }
            }.collect { (download, batch) ->
                if (download == null && batch == null) {
                    stopSelf()
                } else {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, buildNotification(download, batch))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "APK download and batch update progress"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(
        download: DownloadCenter.State?,
        batch: BatchUpdateState?,
    ): android.app.Notification {
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
                    .setContentTitle("Preparing update")
                    .setContentText("Starting the download")
                    .setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadService::class.java),
                )
            }
        }
    }
}
