package com.rushi.modulesmanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.rushi.modulesmanager.TranslationState
import com.rushi.modulesmanager.data.TranslationLanguages
import com.rushi.modulesmanager.ui.components.EmptyState
import com.rushi.modulesmanager.ui.components.HeaderDivider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    viewModel: AppViewModel,
    developer: String,
    onBack: () -> Unit,
    onOpenModule: (String) -> Unit,
    onOpenDeveloper: (String) -> Unit,
) {
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val manualModules by viewModel.manualModules.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val translations by viewModel.translations.collectAsStateWithLifecycle()
    val descriptionLanguages by viewModel.descriptionLanguages.collectAsStateWithLifecycle()
    val translateLanguage by viewModel.translateLanguage.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val sourceAlternatives by viewModel.sourceAlternatives.collectAsStateWithLifecycle()

    val entries = remember(modules, manualModules, developer) {
        (modules + manualModules.values)
            .distinctBy { it.packageName }
            .filter { it.developerLogin().equals(developer, ignoreCase = true) }
            .sortedBy { it.displayName.lowercase() }
    }
    val updatesByPackage = remember(updates) { updates.associateBy { it.installed.packageName } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = developer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (entries.size == 1) "1 module" else "${entries.size} modules",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back",
                            )
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
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Outlined.Extension,
                    title = "No modules",
                    body = "This developer has no other modules in the current repository.",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.packageName }) { module ->
                    val packageName = module.packageName
                    val translation = translations[packageName] ?: remember(
                        packageName,
                        module.summary,
                        translateLanguage,
                    ) {
                        viewModel.cachedTranslation(module)?.let { TranslationState(text = it) }
                    }
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
                        sourceCount = sourceAlternatives[packageName]?.size ?: 1,
                        favourite = packageName in favourites,
                        onToggleFavourite = { viewModel.toggleFavourite(packageName) },
                        onOpenDeveloper = onOpenDeveloper,
                        onClick = { onOpenModule(packageName) },
                    )
                }
            }
        }
    }
}
