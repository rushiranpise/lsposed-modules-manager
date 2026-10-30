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
import com.rushi.modulesmanager.MainActivity
import com.rushi.modulesmanager.ModulesManagerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while an APK is downloading and shows its progress. The transfer
 * itself lives in [RepoApi], so this only mirrors [DownloadCenter.State] into a notification.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val container = (application as ModulesManagerApplication).container
        startForeground(NOTIFICATION_ID, buildNotification(null))
        scope.launch {
            container.downloads.state.collect { state ->
                if (state == null) {
                    stopSelf()
                } else {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, buildNotification(state))
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
                description = "APK download progress"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(state: DownloadCenter.State?): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(state?.label ?: "Preparing download")
            .setContentText(
                if (state?.paused == true) "Paused - ${state.fileName}" else state?.fileName.orEmpty(),
            )
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        val fraction = state?.fraction
        if (fraction != null) {
            builder.setProgress(100, (fraction * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
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
