package com.rushi.modulesmanager

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import com.rushi.modulesmanager.ui.AppRoot
import com.rushi.modulesmanager.ui.theme.ModulesManagerTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (application as ModulesManagerApplication).container
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
}
