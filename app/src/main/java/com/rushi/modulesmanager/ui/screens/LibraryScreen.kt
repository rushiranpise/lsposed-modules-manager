package com.rushi.modulesmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rushi.modulesmanager.AppViewModel
import com.rushi.modulesmanager.TranslationState
import com.rushi.modulesmanager.data.InstalledModule
import com.rushi.modulesmanager.data.RepoModule
import com.rushi.modulesmanager.data.TranslationLanguages
import com.rushi.modulesmanager.ui.components.EmptyState

private enum class LibraryTab(val label: String, val icon: ImageVector) {
    FAVOURITES("Favourites", Icons.Outlined.FavoriteBorder),
    RECENT("Recent", Icons.Outlined.History),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: AppViewModel,
    onOpenModule: (String) -> Unit,
) {
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val recentlyViewed by viewModel.recentlyViewed.collectAsStateWithLifecycle()
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
    // A saved index from the three-tab layout may point past the new end.
    val selectedTab = LibraryTab.entries[tab.coerceIn(0, LibraryTab.entries.lastIndex)]

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

    val packages = when (selectedTab) {
        LibraryTab.FAVOURITES -> favourites.sortedBy { label(it).lowercase() }
        LibraryTab.RECENT -> recentlyViewed
    }
    val entries = packages.mapNotNull { modulesByPackage[it] }
    val tabCounts = listOf(favourites.size, recentlyViewed.size)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                LibraryTab.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = selectedTab.ordinal == index,
                        onClick = { tab = index },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = LibraryTab.entries.size,
                        ),
                        modifier = Modifier.weight(1f),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(item.label)
                                Surface(
                                    shape = CircleShape,
                                    color = if (selectedTab.ordinal == index) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    contentColor = if (selectedTab.ordinal == index) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                ) {
                                    Text(
                                        text = tabCounts[index].toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(
                                            horizontal = 7.dp,
                                            vertical = 2.dp,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty-${selectedTab.ordinal}") {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (selectedTab) {
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
                        }
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
                    }
                }
            }
        }
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
