package com.rushi.modulesmanager.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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
import com.rushi.modulesmanager.ui.screens.DeveloperScreen
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
        !currentRoute.startsWith("module/") &&
        !currentRoute.startsWith("developer/")

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
        // A rail on tablets and other wide windows; phones float a pill over the content.
        val wide = maxWidth >= 600.dp
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.padding(bottom = if (wide) 0.dp else 96.dp),
                    )
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
            if (!wide) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    if (!showBottomBar) {
                        download?.let { state ->
                            Box(modifier = Modifier.navigationBarsPadding()) {
                                DownloadBanner(
                                    state = state,
                                    onCancel = viewModel::cancelDownload,
                                    onPause = viewModel::pauseDownload,
                                    onResume = viewModel::resumeDownload,
                                )
                            }
                        }
                    } else {
                        download?.let { state ->
                            DownloadBanner(
                                state = state,
                                onCancel = viewModel::cancelDownload,
                                onPause = viewModel::pauseDownload,
                                onResume = viewModel::resumeDownload,
                            )
                        }
                        FloatingNavBar(
                            currentRoute = currentRoute,
                            installedCount = installed.size,
                            onSelect = { destination ->
                                navController.navigateTopLevel(destination.route)
                            },
                        )
                    }
                }
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
private fun FloatingNavBar(
    currentRoute: String?,
    installedCount: Int,
    onSelect: (Destination) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Drawn before the insets so the fade reaches the bottom of the screen, not the top
            // of the gesture area. The page under the pill fades into the background instead of
            // ending on a hard edge.
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .selectableGroup()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Destination.entries.forEach { destination ->
                    FloatingNavItem(
                        destination = destination,
                        selected = currentRoute == destination.route,
                        installedCount = installedCount,
                        onClick = { onSelect(destination) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    destination: Destination,
    selected: Boolean,
    installedCount: Int,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        label = "navItemContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "navItemContent",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The expanded label leaves no room for a badge; show it only on the icon-only tabs.
        DestinationIcon(
            destination = destination,
            installedCount = if (selected) 0 else installedCount,
            tint = content,
        )
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut(),
        ) {
            Text(
                text = destination.label,
                color = content,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun DestinationIcon(
    destination: Destination,
    installedCount: Int,
    tint: Color? = null,
) {
    val iconTint = tint ?: LocalContentColor.current
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
            Icon(destination.icon, contentDescription = null, tint = iconTint)
        }
    } else {
        Icon(destination.icon, contentDescription = null, tint = iconTint)
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
        composable("developer/{developer}") { entry ->
            val developer = entry.arguments?.getString("developer").orEmpty()
            DeveloperScreen(
                viewModel = viewModel,
                developer = developer,
                onBack = { navController.popBackStack() },
                onOpenModule = { packageName -> navController.navigate("module/$packageName") },
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
                onOpenDeveloper = { developer ->
                    navController.navigate("developer/${Uri.encode(developer)}")
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
