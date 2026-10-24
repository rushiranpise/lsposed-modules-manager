package com.rushi.modulesmanager.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rushi.modulesmanager.AppViewModel
import com.rushi.modulesmanager.BatchPhase
import com.rushi.modulesmanager.BatchUpdateState
import com.rushi.modulesmanager.ModuleUpdate
import com.rushi.modulesmanager.data.InstalledModule
import com.rushi.modulesmanager.data.RepoModule
import com.rushi.modulesmanager.ui.components.CountedFilterChip
import com.rushi.modulesmanager.ui.components.EmptyState
import com.rushi.modulesmanager.ui.components.HeaderDivider
import com.rushi.modulesmanager.ui.components.InstalledAvatar
import com.rushi.modulesmanager.ui.components.PackageLabel
import com.rushi.modulesmanager.ui.components.StatusPill
import com.rushi.modulesmanager.ui.components.VerticalScrollbar
import com.rushi.modulesmanager.ui.components.shortVersion
import kotlinx.coroutines.launch

private enum class InstalledFilter { ALL, UPDATES, NOT_IN_REPO }

@Composable
fun InstalledScreen(
    viewModel: AppViewModel,
    onOpenModule: (String) -> Unit,
) {
    val installed by viewModel.allInstalled.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val batch by viewModel.batchUpdate.collectAsStateWithLifecycle()
    val ignoredUpdates by viewModel.ignoredUpdates.collectAsStateWithLifecycle()
    val preReleaseModules by viewModel.preReleaseModules.collectAsStateWithLifecycle()
    val skippedVersions by viewModel.skippedVersions.collectAsStateWithLifecycle()

    var filter by rememberSaveable { mutableStateOf(InstalledFilter.ALL) }
    var pendingUninstall by remember { mutableStateOf<InstalledModule?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val modulesByPackage = remember(modules) { modules.associateBy { it.packageName } }
    val updatesByPackage = remember(updates) { updates.associateBy { it.installed.packageName } }
    val notInRepoCount = remember(installed, modulesByPackage) {
        installed.count { it.packageName !in modulesByPackage }
    }
    val visible = remember(installed, updatesByPackage, modulesByPackage, filter) {
        installed.filter { module ->
            when (filter) {
                InstalledFilter.ALL -> true
                InstalledFilter.UPDATES -> updatesByPackage.containsKey(module.packageName)
                InstalledFilter.NOT_IN_REPO -> module.packageName !in modulesByPackage
            }
        }.sortedWith(
            compareBy<InstalledModule> { if (updatesByPackage.containsKey(it.packageName)) 0 else 1 }
                .thenBy { it.label.lowercase() },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Installed") },
                    actions = {
                        IconButton(onClick = { viewModel.rescan(showMessage = true) }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Rescan installed modules")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                HeaderDivider()
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CountedFilterChip(
                    selected = filter == InstalledFilter.ALL,
                    onClick = { filter = InstalledFilter.ALL },
                    label = "All",
                    count = installed.size,
                )
                CountedFilterChip(
                    selected = filter == InstalledFilter.UPDATES,
                    onClick = { filter = InstalledFilter.UPDATES },
                    label = "Updates",
                    count = updates.size,
                )
                CountedFilterChip(
                    selected = filter == InstalledFilter.NOT_IN_REPO,
                    onClick = { filter = InstalledFilter.NOT_IN_REPO },
                    label = "Not in repo",
                    count = notInRepoCount,
                )
            }

            if (batch?.running == true) {
                BatchProgressCard(state = batch!!, onCancel = viewModel::cancelBatch)
            } else if (updates.isNotEmpty()) {
                FilledTonalButton(
                    onClick = viewModel::updateAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Update all (${updates.size})")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                icon = Icons.Outlined.Apps,
                                title = if (installed.isEmpty()) {
                                    "No installed modules found"
                                } else {
                                    when (filter) {
                                        InstalledFilter.UPDATES -> "Nothing to update"
                                        InstalledFilter.NOT_IN_REPO -> "All modules are in the repository"
                                        InstalledFilter.ALL -> "No installed modules found"
                                    }
                                },
                                body = if (installed.isEmpty()) {
                                    "Install modules from the Modules tab, then enable them in LSPosed."
                                } else {
                                    when (filter) {
                                        InstalledFilter.UPDATES ->
                                            "All installed modules are on their latest version."
                                        InstalledFilter.NOT_IN_REPO ->
                                            "Every installed module has an entry in the daily index."
                                        InstalledFilter.ALL ->
                                            "Install modules from the Modules tab, then enable them in LSPosed."
                                    }
                                },
                            )
                        }
                    } else {
                        items(visible, key = { it.packageName }) { module ->
                            val repoModule = modulesByPackage[module.packageName]
                            InstalledCard(
                                module = module,
                                repoModule = repoModule,
                                update = updatesByPackage[module.packageName],
                                ignored = module.packageName in ignoredUpdates ||
                                    (repoModule != null && repoModule.packageName in ignoredUpdates),
                                skippedVersion = repoModule?.let { skippedVersions[it.packageName] },
                                preReleases = repoModule != null &&
                                    repoModule.packageName in preReleaseModules,
                                canTrackPreReleases = repoModule != null &&
                                    viewModel.canTrackPreReleases(repoModule),
                                onOpenRepository = {
                                    onOpenModule(repoModule?.packageName ?: module.packageName)
                                },
                                onOpenApp = { viewModel.openInstalledModule(module.packageName) },
                                onUpdate = { update -> viewModel.installLatest(update.module) },
                                onSkipVersion = { repoModule?.let(viewModel::skipVersion) },
                                onClearSkipped = { repoModule?.let(viewModel::clearSkippedVersion) },
                                onToggleIgnore = { repoModule?.let(viewModel::toggleIgnoreUpdates) },
                                onTogglePreRelease = { repoModule?.let(viewModel::togglePreReleases) },
                                onAppInfo = { viewModel.openAppInfo(module.packageName) },
                                onUninstall = { pendingUninstall = module },
                            )
                        }
                    }
                }

                VerticalScrollbar(
                    state = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(top = 4.dp, bottom = 80.dp),
                )

                val showScrollToTop by remember {
                    derivedStateOf { listState.firstVisibleItemIndex > 0 }
                }
                if (showScrollToTop) {
                    FloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Scroll to top")
                    }
                }
            }
        }
    }

    pendingUninstall?.let { module ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("Uninstall ${module.label}?") },
            text = {
                Text("This removes the app from the device. You can install it again from the Modules tab.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUninstall = null
                        viewModel.uninstall(module.packageName)
                    },
                ) { Text("Uninstall") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstalledCard(
    module: InstalledModule,
    repoModule: RepoModule?,
    update: ModuleUpdate?,
    ignored: Boolean,
    skippedVersion: String?,
    preReleases: Boolean,
    canTrackPreReleases: Boolean,
    onOpenRepository: () -> Unit,
    onOpenApp: () -> Unit,
    onUpdate: (ModuleUpdate) -> Unit,
    onSkipVersion: () -> Unit,
    onClearSkipped: () -> Unit,
    onToggleIgnore: () -> Unit,
    onTogglePreRelease: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (module.hasLauncher) onOpenApp()
                    else if (repoModule != null) onOpenRepository()
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onUninstall()
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InstalledAvatar(module, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = module.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (update != null) {
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { onUpdate(update) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Install", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "More actions",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            if (update != null) {
                                DropdownMenuItem(
                                    text = { Text("Skip this version") },
                                    onClick = {
                                        menuOpen = false
                                        onSkipVersion()
                                    },
                                )
                            }
                            if (skippedVersion != null) {
                                DropdownMenuItem(
                                    text = { Text("Clear skipped version") },
                                    onClick = {
                                        menuOpen = false
                                        onClearSkipped()
                                    },
                                )
                            }
                            if (repoModule != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(if (ignored) "Restore updates" else "Ignore updates")
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onToggleIgnore()
                                    },
                                )
                            }
                            if (canTrackPreReleases) {
                                DropdownMenuItem(
                                    text = { Text("Follow pre-releases") },
                                    leadingIcon = {
                                        if (preReleases) {
                                            Icon(Icons.Outlined.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onTogglePreRelease()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("App info") },
                                onClick = {
                                    menuOpen = false
                                    onAppInfo()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Uninstall") },
                                onClick = {
                                    menuOpen = false
                                    onUninstall()
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(1.dp))
                PackageLabel(module.packageName)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = buildString {
                            append("v${module.versionName ?: module.versionCode}")
                            if (module.scope.isNotEmpty()) append(" - ${module.scope.size} targets")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    when {
                        ignored -> StatusPill(text = "Updates ignored")
                        skippedVersion != null -> StatusPill(
                            text = "Skipped ${shortVersion(skippedVersion.substringAfter('-'))}",
                        )
                        update != null -> StatusPill(
                            text = "Update ${shortVersion(update.version.name)}",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        repoModule != null -> StatusPill(
                            text = "Up to date",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        else -> StatusPill(text = "Not in repository")
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchProgressCard(state: BatchUpdateState, onCancel: () -> Unit) {
    val fraction = if (state.total > 0) state.finished.toFloat() / state.total else 0f
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.current?.let { "Installing $it" } ?: "Preparing updates",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${state.finished} of ${state.total} done",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
