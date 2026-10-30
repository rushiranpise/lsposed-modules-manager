package com.rushi.modulesmanager.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rushi.modulesmanager.AppViewModel
import com.rushi.modulesmanager.ui.components.HeaderDivider
import com.rushi.modulesmanager.ui.components.PackageLabel

@Composable
fun LibraryScreen(
    viewModel: AppViewModel,
    onOpenModule: (String) -> Unit,
) {
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val recentlyViewed by viewModel.recentlyViewed.collectAsStateWithLifecycle()
    val skipped by viewModel.skippedVersions.collectAsStateWithLifecycle()
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val manualModules by viewModel.manualModules.collectAsStateWithLifecycle()
    val installed by viewModel.allInstalled.collectAsStateWithLifecycle()

    val byPackage = remember(modules, manualModules) {
        (modules + manualModules.values).associateBy { it.packageName }
    }
    val installedByPackage = remember(installed) { installed.associateBy { it.packageName } }
    fun labelFor(packageName: String): String =
        byPackage[packageName]?.displayName
            ?: installedByPackage[packageName]?.label
            ?: packageName

    val favouritePackages = remember(favourites, byPackage, installedByPackage) {
        favourites.sortedBy { labelFor(it).lowercase() }
    }
    val recentPackages = remember(recentlyViewed) { recentlyViewed }
    val skippedPackages = remember(skipped) { skipped.keys.sorted() }

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
                HeaderDivider()
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "favourites-title") {
                SectionTitle("Favourites")
            }
            if (favouritePackages.isEmpty()) {
                item(key = "favourites-empty") {
                    EmptyHint("Star a module on its page to keep it here.")
                }
            } else {
                items(favouritePackages, key = { "fav-$it" }) { packageName ->
                    LibraryRow(
                        label = labelFor(packageName),
                        packageName = packageName,
                        onClick = { onOpenModule(packageName) },
                        action = {
                            IconButton(onClick = { viewModel.toggleFavourite(packageName) }) {
                                Icon(
                                    Icons.Outlined.Star,
                                    contentDescription = "Remove from favourites",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
            }

            item(key = "recent-title") {
                SectionTitle("Recently viewed")
            }
            if (recentPackages.isEmpty()) {
                item(key = "recent-empty") {
                    EmptyHint("Modules you open show up here.")
                }
            } else {
                items(recentPackages.take(10), key = { "recent-$it" }) { packageName ->
                    LibraryRow(
                        label = labelFor(packageName),
                        packageName = packageName,
                        onClick = { onOpenModule(packageName) },
                    )
                }
            }

            item(key = "skipped-title") {
                SectionTitle("Skipped updates")
            }
            if (skippedPackages.isEmpty()) {
                item(key = "skipped-empty") {
                    EmptyHint("Skip a version from an update row to hide it.")
                }
            } else {
                items(skippedPackages, key = { "skipped-$it" }) { packageName ->
                    val module = byPackage[packageName]
                    LibraryRow(
                        label = labelFor(packageName),
                        packageName = packageName,
                        onClick = { onOpenModule(packageName) },
                        action = {
                            if (module != null) {
                                TextButton(onClick = { viewModel.clearSkippedVersion(module) }) {
                                    Text("Clear")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun LibraryRow(
    label: String,
    packageName: String,
    onClick: () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PackageLabel(packageName)
            }
            action?.invoke()
        }
    }
}
