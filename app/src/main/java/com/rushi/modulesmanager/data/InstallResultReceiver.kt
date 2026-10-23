package com.rushi.modulesmanager.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/**
 * Declared in the manifest so the installer result still arrives after the activity or process
 * has been killed. The confirmation screen is launched from here as well.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_INTENT,
                Intent::class.java,
            )
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirmation) }
                    .onFailure {
                        ApkInstaller.deliverResult(context, PackageInstaller.STATUS_FAILURE, it.message)
                    }
            } else {
                ApkInstaller.deliverResult(
                    context,
                    PackageInstaller.STATUS_FAILURE,
                    "No installer confirmation was provided",
                )
            }
            return
        }

        ApkInstaller.deliverResult(context, status, message)
    }
}
