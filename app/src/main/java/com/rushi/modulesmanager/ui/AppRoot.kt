package com.rushi.modulesmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rushi.modulesmanager.AppViewModel
import com.rushi.modulesmanager.data.formatBytes
import com.rushi.modulesmanager.ui.components.DownloadBanner
import com.rushi.modulesmanager.ui.screens.InstalledScreen
import com.rushi.modulesmanager.ui.screens.ModuleDetailScreen
import com.rushi.modulesmanager.ui.screens.RepoListScreen
import com.rushi.modulesmanager.ui.screens.SettingsScreen

private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    MODULES("modules", "Modules", Icons.Outlined.Extension),
    INSTALLED("installed", "Installed", Icons.Outlined.Apps),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings),
}

@Composable
fun AppRoot(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val download by viewModel.downloads.collectAsStateWithLifecycle()
    val installed by viewModel.allInstalled.collectAsStateWithLifecycle()
    val variantChoice by viewModel.variantChoice.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != null && !currentRoute.startsWith("module/")

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.rescan()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                download?.let { state ->
                    DownloadBanner(state = state, onCancel = viewModel::cancelDownload)
                }
                if (showBottomBar) {
                    NavigationBar {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = currentRoute == destination.route,
                                onClick = { navController.navigateTopLevel(destination.route) },
                                icon = {
                                    if (destination == Destination.INSTALLED && installed.isNotEmpty()) {
                                        BadgedBox(
                                            badge = {
                                                Badge(
                                                    containerColor = MaterialTheme.colorScheme.primary,
                                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                                ) {
                                                    Text(installed.size.toString())
                                                }
                                            },
                                        ) {
                                            Icon(destination.icon, contentDescription = null)
                                        }
                                    } else {
                                        Icon(destination.icon, contentDescription = null)
                                    }
                                },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.MODULES.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.MODULES.route) {
                RepoListScreen(
                    viewModel = viewModel,
                    onOpenModule = { packageName -> navController.navigate("module/$packageName") },
                )
            }
            composable(Destination.INSTALLED.route) {
                InstalledScreen(
                    viewModel = viewModel,
                    onOpenModule = { packageName -> navController.navigate("module/$packageName") },
                )
            }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(viewModel)
            }
            composable("module/{packageName}") { entry ->
                val packageName = entry.arguments?.getString("packageName").orEmpty()
                ModuleDetailScreen(
                    viewModel = viewModel,
                    packageName = packageName,
                    onBack = {
                        viewModel.closeModule()
                        navController.popBackStack()
                    },
                )
            }
        }
    }

    variantChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = viewModel::cancelVariantChoice,
            title = { Text("Choose an APK") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${choice.module.displayName}\nSeveral APKs fit this device equally well.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    choice.assets.forEach { asset ->
                        TextButton(
                            onClick = { viewModel.installVariant(asset) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = asset.name,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (asset.size > 0) {
                                    Text(
                                        text = formatBytes(asset.size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::cancelVariantChoice) { Text("Cancel") }
            },
        )
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
