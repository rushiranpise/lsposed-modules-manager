package com.rushi.modulesmanager.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rushi.modulesmanager.AppViewModel
import com.rushi.modulesmanager.BatchItemState
import com.rushi.modulesmanager.BatchPhase
import com.rushi.modulesmanager.BatchUpdateState
import com.rushi.modulesmanager.ModuleUpdate
import com.rushi.modulesmanager.data.InstalledModule
import com.rushi.modulesmanager.data.RepoModule
import com.rushi.modulesmanager.ui.components.CountedFilterChip
import com.rushi.modulesmanager.ui.components.EmptyState
import com.rushi.modulesmanager.ui.components.InstalledAvatar
import com.rushi.modulesmanager.ui.components.PackageLabel
import com.rushi.modulesmanager.ui.components.StatusPill
import com.rushi.modulesmanager.ui.components.VersionPill
import com.rushi.modulesmanager.ui.components.VerticalScrollbar
import com.rushi.modulesmanager.ui.components.shortVersion
import kotlinx.coroutines.launch

private enum class InstalledFilter { ALL, UPDATES, SKIPPED, UNTRACKED }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstalledScreen(
    viewModel: AppViewModel,
    onOpenModule: (String) -> Unit,
) {
    val installed by viewModel.allInstalled.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val batch by viewModel.batchUpdate.collectAsStateWithLifecycle()
    val download by viewModel.downloads.collectAsStateWithLifecycle()
    val ignoredUpdates by viewModel.ignoredUpdates.collectAsStateWithLifecycle()
    val preReleaseModules by viewModel.preReleaseModules.collectAsStateWithLifecycle()
    val skippedVersions by viewModel.skippedVersions.collectAsStateWithLifecycle()
    val moduleLinks by viewModel.moduleLinks.collectAsStateWithLifecycle()
    val manualModules by viewModel.manualModules.collectAsStateWithLifecycle()

    var filter by rememberSaveable { mutableStateOf(InstalledFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingUninstall by remember { mutableStateOf<InstalledModule?>(null) }
    var linkingModule by remember { mutableStateOf<InstalledModule?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val modulesByPackage = remember(modules) { modules.associateBy { it.packageName } }
    val updatesByPackage = remember(updates) { updates.associateBy { it.installed.packageName } }
    fun repoModuleFor(module: InstalledModule): RepoModule? =
        modulesByPackage[module.packageName]
            ?: manualModules[module.packageName]
            ?: moduleLinks[module.packageName]?.let { modulesByPackage[it] }

    /** Skipped versions and ignored updates share this filter, keyed by the repository package. */
    fun hiddenFor(module: InstalledModule): Boolean {
        val repoPackage = repoModuleFor(module)?.packageName ?: module.packageName
        return repoPackage in skippedVersions || repoPackage in ignoredUpdates
    }

    // Chip counts follow the search, so they describe the results the user is looking at.
    val searched = remember(installed, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            installed
        } else {
            installed.filter { module ->
                module.label.lowercase().contains(needle) ||
                    module.packageName.lowercase().contains(needle)
            }
        }
    }
    val untrackedCount = remember(
        searched,
        modulesByPackage,
        manualModules,
        moduleLinks,
    ) {
        searched.count { repoModuleFor(it) == null }
    }
    val skippedInSearch = remember(
        searched,
        skippedVersions,
        ignoredUpdates,
        modulesByPackage,
        manualModules,
        moduleLinks,
    ) {
        searched.count { hiddenFor(it) }
    }
    val updatesInSearch = remember(searched, updatesByPackage) {
        searched.count { updatesByPackage.containsKey(it.packageName) }
    }
    val visible = remember(
        searched,
        updatesByPackage,
        modulesByPackage,
        manualModules,
        moduleLinks,
        skippedVersions,
        ignoredUpdates,
        filter,
    ) {
        searched.filter { module ->
            when (filter) {
                InstalledFilter.ALL -> true
                InstalledFilter.UPDATES -> updatesByPackage.containsKey(module.packageName)
                InstalledFilter.SKIPPED -> hiddenFor(module)
                InstalledFilter.UNTRACKED -> repoModuleFor(module) == null
            }
        }.sortedWith(
            compareBy<InstalledModule> { if (updatesByPackage.containsKey(it.packageName)) 0 else 1 }
                .thenBy { it.label.lowercase() },
        )
    }

    // Skip the first run so returning from a module page keeps the scroll position.
    var filtersReady by remember { mutableStateOf(false) }
    LaunchedEffect(query, filter) {
        if (filtersReady) listState.scrollToItem(0) else filtersReady = true
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("Search installed") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
            )

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CountedFilterChip(
                    selected = filter == InstalledFilter.ALL,
                    onClick = { filter = InstalledFilter.ALL },
                    label = "All",
                    count = searched.size,
                )
                CountedFilterChip(
                    selected = filter == InstalledFilter.UPDATES,
                    onClick = { filter = InstalledFilter.UPDATES },
                    label = "Updates",
                    count = updatesInSearch,
                )
                CountedFilterChip(
                    selected = filter == InstalledFilter.SKIPPED,
                    onClick = { filter = InstalledFilter.SKIPPED },
                    label = "Skipped",
                    count = skippedInSearch,
                )
                CountedFilterChip(
                    selected = filter == InstalledFilter.UNTRACKED,
                    onClick = { filter = InstalledFilter.UNTRACKED },
                    label = "Untracked",
                    count = untrackedCount,
                )
            }

            val visibleBatch = batch
            if (visibleBatch != null && (visibleBatch.running || visibleBatch.interrupted)) {
                BatchProgressCard(
                    state = visibleBatch,
                    onCancel = viewModel::cancelBatch,
                    onDismiss = viewModel::dismissBatch,
                )
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
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                EmptyState(
                                    icon = Icons.Outlined.Apps,
                                    title = if (installed.isEmpty()) {
                                        "No installed modules found"
                                    } else {
                                        when (filter) {
                                        InstalledFilter.UPDATES -> "Nothing to update"
                                        InstalledFilter.SKIPPED -> "Nothing is skipped"
                                        InstalledFilter.UNTRACKED -> "All modules are tracked"
                                        InstalledFilter.ALL -> "No installed modules found"
                                        }
                                    },
                                    body = if (installed.isEmpty()) {
                                        "Install modules from the Modules tab, then enable them in LSPosed."
                                    } else {
                                        when (filter) {
                                        InstalledFilter.UPDATES ->
                                            "All installed modules are on their latest version."
                                        InstalledFilter.SKIPPED ->
                                            "No installed module has a skipped version or ignored updates."
                                        InstalledFilter.UNTRACKED ->
                                            "Every installed module has an entry in the daily index."
                                        InstalledFilter.ALL ->
                                                "Install modules from the Modules tab, then enable them in LSPosed."
                                        }
                                    },
                                )
                            }
                        }
                    } else {
                        items(visible, key = { it.packageName }) { module ->
                            val repoModule = repoModuleFor(module)
                            val linked = moduleLinks[module.packageName] != null
                            InstalledCard(
                                module = module,
                                repoModule = repoModule,
                                update = updatesByPackage[module.packageName],
                                batchItem = batch?.items?.get(module.packageName),
                                onCancelItem = {
                                    viewModel.cancelBatchItem(module.packageName)
                                },
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
                                onLinkModule = { linkingModule = module },
                                onUnlinkModule = { viewModel.unlinkModule(module.packageName) },
                                linked = linked,
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
                val stackAlpha by animateFloatAsState(
                    targetValue = if (download == null) 1f else 0f,
                    label = "fabStack",
                )
                if (stackAlpha > 0.01f) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .alpha(stackAlpha)
                        .navigationBarsPadding()
                        .padding(end = 16.dp, bottom = 98.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AnimatedVisibility(
                        visible = showScrollToTop,
                        enter = fadeIn() + scaleIn(initialScale = 0.8f),
                        exit = fadeOut() + scaleOut(targetScale = 0.8f),
                    ) {
                        FloatingActionButton(
                            onClick = { scope.launch { listState.animateScrollToItem(0) } },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Scroll to top")
                        }
                    }
                    FloatingActionButton(
                        onClick = {
                            scope.launch { listState.scrollToItem(0) }
                            viewModel.rescan(showMessage = true)
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Rescan installed modules",
                        )
                    }
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

    linkingModule?.let { module ->
        LinkModuleDialog(
            label = module.label,
            onDismiss = { linkingModule = null },
            onLink = { url ->
                viewModel.linkModule(module.packageName, url)
                linkingModule = null
            },
        )
    }
}

@Composable
private fun LinkModuleDialog(
    label: String,
    onDismiss: () -> Unit,
    onLink: (String) -> Unit,
) {
    var repoUrl by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link manually") },
        text = {
            Column {
                Text(
                    text = "Paste the GitHub repository for $label. Its releases will be used for updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = repoUrl,
                    onValueChange = { repoUrl = it },
                    placeholder = { Text("https://github.com/owner/repo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onLink(repoUrl) },
                enabled = repoUrl.isNotBlank(),
            ) { Text("Link") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstalledCard(
    module: InstalledModule,
    repoModule: RepoModule?,
    update: ModuleUpdate?,
    batchItem: BatchItemState?,
    onCancelItem: () -> Unit,
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
    onLinkModule: () -> Unit,
    onUnlinkModule: () -> Unit,
    linked: Boolean,
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
                            if (linked) {
                                DropdownMenuItem(
                                    text = { Text("Unlink manual repository") },
                                    onClick = {
                                        menuOpen = false
                                        onUnlinkModule()
                                    },
                                )
                            } else if (repoModule == null) {
                                DropdownMenuItem(
                                    text = { Text("Link manually") },
                                    onClick = {
                                        menuOpen = false
                                        onLinkModule()
                                    },
                                )
                            }
                            if (canTrackPreReleases) {
                                DropdownMenuItem(
                                    text = { Text("Follow pre-releases") },
                                    leadingIcon = if (preReleases) {
                                        {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = null,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onTogglePreRelease()
                                    },
                                )
                            }
                            if (repoModule != null) {
                                DropdownMenuItem(
                                    text = { Text("View module") },
                                    onClick = {
                                        menuOpen = false
                                        onOpenRepository()
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
                    val showsUpdate = update != null && !ignored && skippedVersion == null
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VersionPill(
                            text = buildString {
                                append("v${module.versionName ?: module.versionCode}")
                                if (showsUpdate && update != null) {
                                    append(" \u2192 v${shortVersion(update.version.name)}")
                                }
                            },
                        )
                        if (module.scope.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (module.scope.size == 1) {
                                    "1 target"
                                } else {
                                    "${module.scope.size} targets"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (ignored || skippedVersion != null || update == null) {
                        Spacer(Modifier.width(8.dp))
                        when {
                            ignored -> StatusPill(text = "Updates ignored")
                            skippedVersion != null -> StatusPill(
                                text = "Skipped ${shortVersion(skippedVersion.substringAfter('-'))}",
                            )
                            repoModule != null -> StatusPill(
                                text = "Up to date",
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            else -> StatusPill(text = "Untracked")
                        }
                    }
                    val batchActive = batchItem != null &&
                        (batchItem.phase == BatchPhase.QUEUED ||
                            batchItem.phase == BatchPhase.DOWNLOADING ||
                            batchItem.phase == BatchPhase.WAITING_TO_INSTALL ||
                            batchItem.phase == BatchPhase.INSTALLING)
                    if (update != null && !batchActive) {
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { onUpdate(update) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Update", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                batchItem?.let { item -> BatchItemProgress(item, onCancelItem) }
            }
        }
    }
}

/** One update's own transfer/install state, with a cancel action while it can still stop. */
@Composable
private fun BatchItemProgress(item: BatchItemState, onCancel: () -> Unit) {
    if (item.phase == BatchPhase.FAILED) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.error ?: "Update failed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val fraction = when {
        item.phase == BatchPhase.WAITING_TO_INSTALL -> 1f
        item.total > 0L -> (item.downloaded.toFloat() / item.total).coerceIn(0f, 1f)
        else -> null
    }
    val label = when (item.phase) {
        BatchPhase.QUEUED -> "Queued"
        BatchPhase.DOWNLOADING -> "Downloading"
        BatchPhase.WAITING_TO_INSTALL -> "Waiting to install"
        BatchPhase.INSTALLING -> "Installing"
        BatchPhase.CANCELLED -> "Cancelled"
        else -> return
    }
    val cancelable = item.phase == BatchPhase.QUEUED ||
        item.phase == BatchPhase.DOWNLOADING ||
        item.phase == BatchPhase.WAITING_TO_INSTALL
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (item.phase == BatchPhase.DOWNLOADING && fraction != null) {
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (cancelable) {
            IconButton(onClick = onCancel, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Cancel this update",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun BatchProgressCard(
    state: BatchUpdateState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fraction = if (state.total > 0) state.finished.toFloat() / state.total else 0f
    val failedItems = state.items.values.filter { it.phase == BatchPhase.FAILED }
    val firstFailure = failedItems.firstOrNull()
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
                        text = if (state.running) {
                            state.current?.let { "Installing $it" } ?: "Preparing updates"
                        } else {
                            "Updates interrupted"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append("${state.finished} of ${state.total} done")
                            if (failedItems.isNotEmpty()) append(" - ${failedItems.size} failed")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!state.running && firstFailure != null) {
                        Text(
                            text = "${firstFailure.title}: ${firstFailure.error.orEmpty()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (state.running) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                } else {
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
