package com.rushi.modulesmanager.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rushi.modulesmanager.AppViewModel
import com.rushi.modulesmanager.ModuleUpdate
import com.rushi.modulesmanager.TranslationState
import com.rushi.modulesmanager.data.InstalledPackageVersion
import com.rushi.modulesmanager.data.ModuleVersion
import com.rushi.modulesmanager.data.RepoModule
import com.rushi.modulesmanager.data.SortMode
import com.rushi.modulesmanager.data.TranslationLanguages
import com.rushi.modulesmanager.data.UpdateChannel
import com.rushi.modulesmanager.data.formatDate
import com.rushi.modulesmanager.data.formatRelativeTime
import com.rushi.modulesmanager.data.parseInstant
import com.rushi.modulesmanager.ui.components.CountedFilterChip
import com.rushi.modulesmanager.ui.components.EmptyState
import com.rushi.modulesmanager.ui.components.ErrorBanner
import com.rushi.modulesmanager.ui.components.ForkBadge
import com.rushi.modulesmanager.ui.components.HeaderDivider
import com.rushi.modulesmanager.ui.components.LoadingState
import com.rushi.modulesmanager.ui.components.ModuleAvatar
import com.rushi.modulesmanager.ui.components.PackageLabel
import com.rushi.modulesmanager.ui.components.StarBadge
import com.rushi.modulesmanager.ui.components.StatusPill
import com.rushi.modulesmanager.ui.components.VerticalScrollbar
import com.rushi.modulesmanager.ui.components.shortVersion
import kotlinx.coroutines.launch
import java.time.Instant

private enum class RepoFilter(val label: String) {
    ALL("All"),
    UPDATES("Updates"),
    INSTALLED("Installed"),
    NOT_INSTALLED("Not installed"),
}

@Composable
fun RepoListScreen(
    viewModel: AppViewModel,
    onOpenModule: (String) -> Unit,
) {
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val updatesFirst by viewModel.updatesFirst.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val translations by viewModel.translations.collectAsStateWithLifecycle()
    val translateLanguage by viewModel.translateLanguage.collectAsStateWithLifecycle()
    val descriptionLanguages by viewModel.descriptionLanguages.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val dataUpdatedAt by viewModel.dataUpdatedAt.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(RepoFilter.ALL) }
    var showSortMenu by remember { mutableStateOf(false) }

    val installedByPackage = installedVersions
    val updatesByPackage = remember(updates) { updates.associateBy { it.installed.packageName } }
    // Chip counts follow the search, so they describe the results the user is looking at.
    val searched = remember(modules, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            modules
        } else {
            modules.filter { module ->
                module.displayName.lowercase().contains(needle) ||
                    module.packageName.lowercase().contains(needle) ||
                    module.summary?.lowercase()?.contains(needle) == true
            }
        }
    }
    val installedInRepo = remember(searched, installedByPackage) {
        searched.count { installedByPackage.containsKey(it.packageName) }
    }
    val updatesInSearch = remember(searched, updatesByPackage) {
        searched.count { updatesByPackage.containsKey(it.packageName) }
    }
    val notInstalledInRepo = searched.size - installedInRepo
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val filtered = remember(searched, filter, installedByPackage, updatesByPackage, sortMode, updatesFirst, channel) {
        val matched = when (filter) {
            RepoFilter.ALL -> searched
            RepoFilter.UPDATES -> searched.filter { updatesByPackage.containsKey(it.packageName) }
            RepoFilter.INSTALLED -> searched.filter { installedByPackage.containsKey(it.packageName) }
            RepoFilter.NOT_INSTALLED -> searched.filter { !installedByPackage.containsKey(it.packageName) }
        }
        matched.sortedWith(
            compareBy<RepoModule> {
                if (updatesFirst && updatesByPackage.containsKey(it.packageName)) 0 else 1
            }.then(
                when (sortMode) {
                    SortMode.NAME -> compareBy<RepoModule> { it.displayName.lowercase() }
                    SortMode.ADDED -> compareByDescending<RepoModule> {
                        parseInstant(it.addedAt) ?: Instant.EPOCH
                    }.thenBy { it.displayName.lowercase() }
                    SortMode.STARS -> compareByDescending<RepoModule> { it.stargazerCount }
                        .thenBy { it.displayName.lowercase() }
                    SortMode.UPDATED -> compareByDescending<RepoModule> {
                        parseInstant(it.latestTime(channel)) ?: Instant.EPOCH
                    }
                },
            ),
        )
    }


    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Modules")
                            dataUpdatedAt?.let { timestamp ->
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Updated ${formatRelativeTime(timestamp)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    actions = {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Sort by name") },
                                    leadingIcon = {
                                        if (sortMode == SortMode.NAME) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp))
                                    },
                                    onClick = {
                                        viewModel.setSortMode(SortMode.NAME)
                                        showSortMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by last update") },
                                    leadingIcon = {
                                        if (sortMode == SortMode.UPDATED) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp))
                                    },
                                    onClick = {
                                        viewModel.setSortMode(SortMode.UPDATED)
                                        showSortMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by recently added") },
                                    leadingIcon = {
                                        if (sortMode == SortMode.ADDED) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp))
                                    },
                                    onClick = {
                                        viewModel.setSortMode(SortMode.ADDED)
                                        showSortMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by stars") },
                                    leadingIcon = {
                                        if (sortMode == SortMode.STARS) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp))
                                    },
                                    onClick = {
                                        viewModel.setSortMode(SortMode.STARS)
                                        showSortMenu = false
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Updates first") },
                                    leadingIcon = {
                                        Checkbox(
                                            checked = updatesFirst,
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = { viewModel.setUpdatesFirst(!updatesFirst) },
                                )
                            }
                        }
                    },
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search modules") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                CountedFilterChip(
                    selected = filter == RepoFilter.ALL,
                    onClick = { filter = RepoFilter.ALL },
                    label = "All",
                    count = searched.size,
                )
                CountedFilterChip(
                    selected = filter == RepoFilter.UPDATES,
                    onClick = { filter = RepoFilter.UPDATES },
                    label = "Updates",
                    count = updatesInSearch,
                )
                CountedFilterChip(
                    selected = filter == RepoFilter.INSTALLED,
                    onClick = { filter = RepoFilter.INSTALLED },
                    label = "Installed",
                    count = installedInRepo,
                )
                CountedFilterChip(
                    selected = filter == RepoFilter.NOT_INSTALLED,
                    onClick = { filter = RepoFilter.NOT_INSTALLED },
                    label = "Not installed",
                    count = notInstalledInRepo,
                )
            }

            error?.let { message ->
                ErrorBanner(message = message, onRetry = viewModel::refresh)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when {
                        loading && modules.isEmpty() -> LoadingState("Loading modules...")
                        filtered.isEmpty() -> {
                            if (modules.isEmpty()) {
                                EmptyState(
                                    icon = Icons.Outlined.ErrorOutline,
                                    title = "Repository unavailable",
                                    body = error ?: "No modules were found in the repository.",
                                    actionLabel = "Retry",
                                    onAction = viewModel::refresh,
                                )
                            } else {
                                EmptyState(
                                    icon = Icons.Outlined.Extension,
                                    title = "Nothing here",
                                    body = when (filter) {
                                        RepoFilter.UPDATES -> "Every installed module is up to date."
                                        RepoFilter.INSTALLED -> "None of your installed modules are in this repository."
                                        RepoFilter.NOT_INSTALLED -> "Every module in this repository is installed."
                                        RepoFilter.ALL -> "No module matches \"$query\"."
                                    },
                                )
                            }
                        }
                        else -> LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(filtered, key = { it.packageName }) { module ->
                                ModuleCard(
                                    module = module,
                                    latest = module.latest(channel),
                                    installed = installedByPackage[module.packageName],
                                    update = updatesByPackage[module.packageName],
                                    channel = channel,
                                    translation = translations[module.packageName] ?: remember(
                                        module.packageName,
                                        module.summary,
                                        translateLanguage,
                                    ) {
                                        viewModel.cachedTranslation(module)?.let { TranslationState(text = it) }
                                    },
                                    showTranslate = !TranslationLanguages.matches(
                                        descriptionLanguages[module.packageName],
                                        translateLanguage,
                                    ),
                                    onTranslate = { viewModel.translate(module) },
                                    onShowOriginal = { viewModel.showOriginal(module) },
                                    onClick = { onOpenModule(module.packageName) },
                                )
                            }
                        }
                    }
                }

                VerticalScrollbar(
                    state = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(top = 8.dp, bottom = 80.dp),
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

}

/** The description line, swapped for its translation while one is showing. */
@Composable
private fun DescriptionBlock(summary: String, translation: TranslationState?) {
    val translated = translation?.text
    Spacer(Modifier.height(6.dp))
    Text(
        text = translated ?: summary,
        style = MaterialTheme.typography.bodySmall,
        color = if (translated != null) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The translate toggle shown beside the date. */
@Composable
private fun TranslateAction(
    translated: Boolean,
    loading: Boolean,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clickable(enabled = !loading) {
                if (translated) onShowOriginal() else onTranslate()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = Icons.Outlined.Translate,
                contentDescription = if (translated) "Show original" else "Translate",
                tint = if (translated) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ModuleCard(
    module: RepoModule,
    latest: ModuleVersion?,
    installed: InstalledPackageVersion?,
    update: ModuleUpdate?,
    channel: UpdateChannel,
    translation: TranslationState?,
    showTranslate: Boolean,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ModuleAvatar(module, size = 54.dp)
                Spacer(Modifier.height(6.dp))
                StarBadge(module.stargazerCount)
                Spacer(Modifier.height(4.dp))
                ForkBadge(module.forkCount)
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                PackageLabel(module.packageName)
                module.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    DescriptionBlock(summary = summary, translation = translation)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            update != null -> StatusPill(
                                text = "v${shortVersion(update.installed.versionName)} \u2192 v${shortVersion(update.version.name)}",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            installed != null -> StatusPill(
                                text = "Installed ${shortVersion(installed.versionName)}".trim(),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            latest != null -> StatusPill(text = "v${shortVersion(latest.name)}")
                        }
                    }
                    val translated = translation?.text != null
                    if (showTranslate || translated) {
                        Spacer(Modifier.width(8.dp))
                        TranslateAction(
                            translated = translated,
                            loading = translation?.loading == true,
                            onTranslate = onTranslate,
                            onShowOriginal = onShowOriginal,
                        )
                    }
                    val date = formatDate(module.latestTime(channel) ?: module.updatedAt)
                    if (date != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = date,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
