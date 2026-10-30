package com.rushi.modulesmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import com.rushi.modulesmanager.data.ApkVariants
import com.rushi.modulesmanager.data.formatBytes
import com.rushi.modulesmanager.ui.components.DownloadBanner
import com.rushi.modulesmanager.ui.screens.DiagnosticsScreen
import com.rushi.modulesmanager.ui.screens.InstalledScreen
import com.rushi.modulesmanager.ui.screens.LibraryScreen
import com.rushi.modulesmanager.ui.screens.ModuleDetailScreen
import com.rushi.modulesmanager.ui.screens.RepoListScreen
import com.rushi.modulesmanager.ui.screens.SettingsScreen

private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    MODULES("modules", "Modules", Icons.Outlined.Extension),
    INSTALLED("installed", "Installed", Icons.Outlined.Apps),
    LIBRARY("library", "Library", Icons.Outlined.Bookmarks),
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
    val showBottomBar = currentRoute != null &&
        currentRoute != "diagnostics" &&
        !currentRoute.startsWith("module/")

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.rescanIfStale()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BoxWithConstraints {
        // A rail on tablets and other wide windows; phones keep the bottom bar.
        val wide = maxWidth >= 600.dp
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!wide) {
                    Column {
                        download?.let { state ->
                            DownloadBanner(
                                state = state,
                                onCancel = viewModel::cancelDownload,
                                onPause = viewModel::pauseDownload,
                                onResume = viewModel::resumeDownload,
                            )
                        }
                        if (showBottomBar) {
                            NavigationBar {
                                Destination.entries.forEach { destination ->
                                    NavigationBarItem(
                                        selected = currentRoute == destination.route,
                                        onClick = {
                                            navController.navigateTopLevel(destination.route)
                                        },
                                        icon = { DestinationIcon(destination, installed.size) },
                                        label = { Text(destination.label) },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            if (wide) {
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                ) {
                    download?.let { state ->
                        DownloadBanner(
                            state = state,
                            onCancel = viewModel::cancelDownload,
                            onPause = viewModel::pauseDownload,
                            onResume = viewModel::resumeDownload,
                        )
                    }
                    Row(modifier = Modifier.weight(1f)) {
                        if (showBottomBar) {
                            NavigationRail {
                                Destination.entries.forEach { destination ->
                                    NavigationRailItem(
                                        selected = currentRoute == destination.route,
                                        onClick = {
                                            navController.navigateTopLevel(destination.route)
                                        },
                                        icon = { DestinationIcon(destination, installed.size) },
                                        label = { Text(destination.label) },
                                    )
                                }
                            }
                        }
                        AppNavHost(
                            navController = navController,
                            viewModel = viewModel,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                AppNavHost(
                    navController = navController,
                    viewModel = viewModel,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    variantChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = viewModel::cancelVariantChoice,
            title = { Text("Choose an APK") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
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
                                val details = buildList {
                                    addAll(ApkVariants.hints(asset))
                                    if (asset.size > 0) add(formatBytes(asset.size))
                                    if (asset.downloadCount > 0) {
                                        add("${asset.downloadCount} downloads")
                                    }
                                }.joinToString(" - ")
                                if (details.isNotEmpty()) {
                                    Text(
                                        text = details,
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

@Composable
private fun DestinationIcon(destination: Destination, installedCount: Int) {
    if (destination == Destination.INSTALLED && installedCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(installedCount.toString())
                }
            },
        ) {
            Icon(destination.icon, contentDescription = null)
        }
    } else {
        Icon(destination.icon, contentDescription = null)
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    viewModel: AppViewModel,
    modifier: Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Destination.MODULES.route,
        modifier = modifier,
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
        composable(Destination.LIBRARY.route) {
            LibraryScreen(
                viewModel = viewModel,
                onOpenModule = { packageName -> navController.navigate("module/$packageName") },
            )
        }
        composable(Destination.SETTINGS.route) {
            SettingsScreen(
                viewModel = viewModel,
                onOpenDiagnostics = { navController.navigate("diagnostics") },
            )
        }
        composable("diagnostics") {
            DiagnosticsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
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

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
