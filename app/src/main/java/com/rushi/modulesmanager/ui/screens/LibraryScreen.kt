package com.rushi.modulesmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rushi.modulesmanager.AppViewModel
import com.rushi.modulesmanager.TranslationState
import com.rushi.modulesmanager.data.InstalledModule
import com.rushi.modulesmanager.data.RepoModule
import com.rushi.modulesmanager.data.TranslationLanguages
import com.rushi.modulesmanager.ui.components.EmptyState

private enum class LibraryTab(val label: String) {
    FAVOURITES("Favourites"),
    RECENT("Recent"),
    HIDDEN("Skipped"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: AppViewModel,
    onOpenModule: (String) -> Unit,
) {
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val recentlyViewed by viewModel.recentlyViewed.collectAsStateWithLifecycle()
    val skipped by viewModel.skippedVersions.collectAsStateWithLifecycle()
    val ignored by viewModel.ignoredUpdates.collectAsStateWithLifecycle()
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val manualModules by viewModel.manualModules.collectAsStateWithLifecycle()
    val installed by viewModel.allInstalled.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val translations by viewModel.translations.collectAsStateWithLifecycle()
    val descriptionLanguages by viewModel.descriptionLanguages.collectAsStateWithLifecycle()
    val translateLanguage by viewModel.translateLanguage.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }

    val installedByPackage = remember(installed) { installed.associateBy { it.packageName } }
    val installedAsModules = remember(installed) {
        installed.associate { it.packageName to it.asRepoModule() }
    }
    val modulesByPackage = remember(modules, manualModules, installedAsModules) {
        buildMap {
            putAll(installedAsModules)
            (modules + manualModules.values).forEach { put(it.packageName, it) }
        }
    }
    val updatesByPackage = remember(updates) { updates.associateBy { it.installed.packageName } }

    fun label(packageName: String): String =
        modulesByPackage[packageName]?.displayName
            ?: installedByPackage[packageName]?.label
            ?: packageName

    val packages = when (LibraryTab.entries[tab]) {
        LibraryTab.FAVOURITES -> favourites.sortedBy { label(it).lowercase() }
        LibraryTab.RECENT -> recentlyViewed
        LibraryTab.HIDDEN -> (skipped.keys + ignored).distinct().sortedBy { label(it).lowercase() }
    }
    val entries = packages.mapNotNull { modulesByPackage[it] }
    val hiddenCount = (skipped.keys + ignored).distinct().size
    val tabCounts = listOf(favourites.size, recentlyViewed.size, hiddenCount)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Library") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                SecondaryTabRow(selectedTabIndex = tab) {
                    LibraryTab.entries.forEachIndexed { index, item ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = {
                                TabLabel(
                                    text = item.label,
                                    count = tabCounts[index],
                                    selected = tab == index,
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty-${tab}") {
                    when (LibraryTab.entries[tab]) {
                        LibraryTab.FAVOURITES -> EmptyState(
                            icon = Icons.Outlined.FavoriteBorder,
                            title = "No favourites yet",
                            body = "Tap the heart on a module card or module page to keep it here.",
                        )
                        LibraryTab.RECENT -> EmptyState(
                            icon = Icons.Outlined.History,
                            title = "Nothing viewed yet",
                            body = "Modules you open show up here.",
                        )
                        LibraryTab.HIDDEN -> EmptyState(
                            icon = Icons.Outlined.VisibilityOff,
                            title = "Nothing is hidden",
                            body = "Skip a version or ignore updates from an installed module's menu.",
                        )
                    }
                }
            } else {
                items(entries, key = { it.packageName }) { module ->
                    val packageName = module.packageName
                    val translation = translations[packageName] ?: remember(
                        packageName,
                        module.summary,
                        translateLanguage,
                    ) {
                        viewModel.cachedTranslation(module)?.let { TranslationState(text = it) }
                    }
                    Column {
                        ModuleCard(
                            module = module,
                            latest = module.latest(channel),
                            installed = installedVersions[packageName],
                            update = updatesByPackage[packageName],
                            channel = channel,
                            translation = translation,
                            showTranslate = !TranslationLanguages.matches(
                                descriptionLanguages[packageName],
                                translateLanguage,
                            ),
                            onTranslate = { viewModel.translate(module) },
                            onShowOriginal = { viewModel.showOriginal(module) },
                            favourite = packageName in favourites,
                            onToggleFavourite = { viewModel.toggleFavourite(packageName) },
                            onClick = { onOpenModule(packageName) },
                        )
                        if (LibraryTab.entries[tab] == LibraryTab.HIDDEN) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (packageName in skipped) {
                                    TextButton(
                                        onClick = { viewModel.clearSkippedVersion(module) },
                                    ) { Text("Clear skipped version") }
                                }
                                if (packageName in ignored) {
                                    TextButton(
                                        onClick = { viewModel.toggleIgnoreUpdates(module) },
                                    ) { Text("Restore updates") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, count: Int, selected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text)
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** A minimal repository module for an installed package the index does not list. */
private fun InstalledModule.asRepoModule(): RepoModule = RepoModule(
    packageName = packageName,
    displayName = label,
    summary = description,
    url = null,
    homepageUrl = null,
    sourceUrl = null,
    latestRelease = versionName,
    latestReleaseTime = null,
    latestBetaRelease = null,
    latestBetaReleaseTime = null,
    latestSnapshotRelease = null,
    latestSnapshotReleaseTime = null,
    releases = emptyList(),
    betaReleases = emptyList(),
    snapshotReleases = emptyList(),
    readme = null,
    readmeHtml = null,
    collaborators = emptyList(),
    scope = scope,
    hide = false,
    updatedAt = null,
    stargazerCount = 0,
)
