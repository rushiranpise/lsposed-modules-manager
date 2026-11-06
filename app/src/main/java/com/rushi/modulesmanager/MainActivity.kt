package com.rushi.modulesmanager

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rushi.modulesmanager.data.AppTheme
import com.rushi.modulesmanager.data.Settings
import com.rushi.modulesmanager.ui.AppRoot
import com.rushi.modulesmanager.ui.theme.ModulesManagerTheme

class MainActivity : ComponentActivity() {

    private val installedAppsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ModulesManagerApplication).container
        requestInstalledAppsPermission(container.settings)
        setContent {
            val systemDark = isSystemInDarkTheme()
            val theme by container.settings.theme.collectAsStateWithLifecycle()
            val amoled by container.settings.amoled.collectAsStateWithLifecycle()
            val systemColors by container.settings.systemColors.collectAsStateWithLifecycle()
            val darkTheme = when (theme) {
                AppTheme.SYSTEM -> systemDark
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }
            ModulesManagerTheme(
                darkTheme = darkTheme,
                dynamicColor = systemColors,
                amoled = amoled,
            ) {
                val viewModel: AppViewModel = viewModel(factory = AppViewModel.factory(container))
                AppRoot(viewModel)
            }
        }
    }

    /**
     * HyperOS, ColorOS and similar ROMs also gate the installed package list behind this
     * runtime permission. It does not exist on stock Android, where nothing is asked.
     */
    private fun requestInstalledAppsPermission(settings: Settings) {
        val supported = runCatching {
            packageManager.getPermissionInfo(GET_INSTALLED_APPS_PERMISSION, 0)
        }.isSuccess
        if (!supported) return
        if (checkSelfPermission(GET_INSTALLED_APPS_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (settings.installedAppsPermissionAsked()) return
        settings.markInstalledAppsPermissionAsked()
        installedAppsPermission.launch(GET_INSTALLED_APPS_PERMISSION)
    }

    private companion object {
        const val GET_INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
    }
}
