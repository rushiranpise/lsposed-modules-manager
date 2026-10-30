package com.rushi.modulesmanager.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rushi.modulesmanager.AppViewModel
import com.rushi.modulesmanager.BuildConfig
import com.rushi.modulesmanager.data.AppTheme
import com.rushi.modulesmanager.data.InstallerType
import com.rushi.modulesmanager.data.ShizukuInstaller
import com.rushi.modulesmanager.data.TranslationLanguages
import com.rushi.modulesmanager.data.UpdateChannel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onOpenDiagnostics: () -> Unit = {},
) {
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val installer by viewModel.installer.collectAsStateWithLifecycle()
    val shizukuStatus by viewModel.shizukuStatus.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val amoled by viewModel.amoled.collectAsStateWithLifecycle()
    val systemColors by viewModel.systemColors.collectAsStateWithLifecycle()
    val autoTranslate by viewModel.autoTranslate.collectAsStateWithLifecycle()
    val translateLanguage by viewModel.translateLanguage.collectAsStateWithLifecycle()
    val download by viewModel.downloads.collectAsStateWithLifecycle()
    val concurrentDownloads by viewModel.concurrentDownloads.collectAsStateWithLifecycle()

    var showChannelDialog by remember { mutableStateOf(false) }
    var showInstallerDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDownloadsDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(showInstallerDialog) {
        if (showInstallerDialog) viewModel.refreshShizukuStatus()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(bottom = 120.dp),
        ) {
            SectionTitle("Appearance")
            SettingsSwitchRow(
                icon = Icons.Outlined.DarkMode,
                title = "AMOLED mode",
                subtitle = "Pitch black backgrounds when the dark theme is active",
                checked = amoled,
                onCheckedChange = viewModel::setAmoled,
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.ColorLens,
                title = "System colors",
                subtitle = "Material You colors on Android 12+",
                checked = systemColors,
                onCheckedChange = viewModel::setSystemColors,
            )
            SettingsRow(
                icon = Icons.Outlined.Palette,
                title = "Theme",
                subtitle = "${theme.label} - ${theme.description}",
                onClick = { showThemeDialog = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Translation")
            SettingsSwitchRow(
                icon = Icons.Outlined.Translate,
                title = "Translate automatically",
                subtitle = "Translate module descriptions and READMEs when they load",
                checked = autoTranslate,
                onCheckedChange = viewModel::setAutoTranslate,
            )
            SettingsRow(
                icon = Icons.Outlined.Language,
                title = "Language",
                subtitle = if (translateLanguage == null) {
                    "${TranslationLanguages.selectedLabel(null)} (device language)"
                } else {
                    TranslationLanguages.selectedLabel(translateLanguage)
                },
                onClick = { showLanguageDialog = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Updates")
            SettingsRow(
                icon = Icons.Outlined.Refresh,
                title = "Check for updates",
                onClick = viewModel::refresh,
            )
            SettingsRow(
                icon = Icons.Outlined.InstallMobile,
                title = "Installer",
                subtitle = installer.label,
                onClick = { showInstallerDialog = true },
            )
            SettingsRow(
                icon = Icons.Outlined.Download,
                title = "Update channel",
                subtitle = channel.label,
                onClick = { showChannelDialog = true },
            )
            SettingsRow(
                icon = Icons.Outlined.Speed,
                title = "Concurrent downloads",
                subtitle = "$concurrentDownloads at a time",
                onClick = { showDownloadsDialog = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("About")
            SettingsRow(
                icon = Icons.Outlined.LocalCafe,
                title = "Buy me a coffee",
                subtitle = "buymeacoffee.com/rushiranpise",
                onClick = { viewModel.openLink("https://buymeacoffee.com/rushiranpise") },
            )
            SettingsRow(
                icon = Icons.Outlined.Info,
                title = "Modules Manager",
                subtitle = "Version ${BuildConfig.VERSION_NAME}",
            )
            SettingsRow(
                icon = Icons.Outlined.BugReport,
                title = "Diagnostics",
                subtitle = "Device, build and cache details",
                onClick = onOpenDiagnostics,
            )
            SettingsRow(
                icon = Icons.Outlined.Code,
                title = "View on GitHub",
                subtitle = "github.com/rushiranpise/lsposed-modules-manager",
                onClick = { viewModel.openLink("https://github.com/rushiranpise/lsposed-modules-manager") },
            )

        }
        val showScrollToTop by remember {
            derivedStateOf { scrollState.value > 0 }
        }
        val fabAlpha by animateFloatAsState(
            targetValue = if (download == null && showScrollToTop) 1f else 0f,
            label = "scrollToTop",
        )
        if (fabAlpha > 0.01f) {
            FloatingActionButton(
                onClick = { scope.launch { scrollState.animateScrollTo(0) } },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .graphicsLayer {
                        alpha = fabAlpha
                        scaleX = 0.8f + 0.2f * fabAlpha
                        scaleY = 0.8f + 0.2f * fabAlpha
                    }
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 98.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = "Scroll to top",
                )
            }
        }
        }
    }

    if (showChannelDialog) {
        AlertDialog(
            onDismissRequest = { showChannelDialog = false },
            title = { Text("Update channel") },
            text = {
                Column {
                    UpdateChannel.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setChannel(option)
                                    showChannelDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = channel == option,
                                onClick = {
                                    viewModel.setChannel(option)
                                    showChannelDialog = false
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChannelDialog = false }) { Text("Close") }
            },
        )
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Theme") },
            text = {
                Column {
                    AppTheme.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setTheme(option)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = theme == option,
                                onClick = {
                                    viewModel.setTheme(option)
                                    showThemeDialog = false
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Close") }
            },
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("Translation language") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    LanguageOption(
                        label = "Device language",
                        selected = translateLanguage == null,
                        onClick = {
                            viewModel.setTranslateLanguage(null)
                            showLanguageDialog = false
                        },
                    )
                    TranslationLanguages.all().forEach { (tag, name) ->
                        LanguageOption(
                            label = name,
                            selected = translateLanguage == tag,
                            onClick = {
                                viewModel.setTranslateLanguage(tag)
                                showLanguageDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text("Close") }
            },
        )
    }

    if (showDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadsDialog = false },
            title = { Text("Concurrent downloads") },
            text = {
                Column {
                    (1..5).forEach { count ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setConcurrentDownloads(count)
                                    showDownloadsDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = concurrentDownloads == count,
                                onClick = {
                                    viewModel.setConcurrentDownloads(count)
                                    showDownloadsDialog = false
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("$count at a time", style = MaterialTheme.typography.bodyLarge)
                                if (count == 3) {
                                    Text(
                                        text = "Default",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadsDialog = false }) { Text("Close") }
            },
        )
    }

    if (showInstallerDialog) {
        AlertDialog(
            onDismissRequest = { showInstallerDialog = false },
            title = { Text("Installer") },
            text = {
                Column {
                    InstallerType.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setInstaller(option)
                                    // Keep the dialog open for Shizuku so its state and the action
                                    // that resolves it stay visible.
                                    if (option != InstallerType.SHIZUKU) showInstallerDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = installer == option,
                                onClick = {
                                    viewModel.setInstaller(option)
                                    if (option != InstallerType.SHIZUKU) showInstallerDialog = false
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (installer == InstallerType.SHIZUKU) {
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = when (shizukuStatus) {
                                    ShizukuInstaller.Status.READY -> "Shizuku is running and allowed"
                                    ShizukuInstaller.Status.PERMISSION_REQUIRED ->
                                        "Shizuku permission is not granted"
                                    ShizukuInstaller.Status.NOT_RUNNING -> "Shizuku is not running"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (shizukuStatus == ShizukuInstaller.Status.READY) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (shizukuStatus != ShizukuInstaller.Status.READY) {
                                TextButton(
                                    onClick = {
                                        if (shizukuStatus == ShizukuInstaller.Status.NOT_RUNNING) {
                                            viewModel.openShizuku()
                                        } else {
                                            viewModel.requestShizukuPermission()
                                        }
                                    },
                                ) {
                                    Text(
                                        if (shizukuStatus == ShizukuInstaller.Status.NOT_RUNNING) {
                                            "Start"
                                        } else {
                                            "Allow"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInstallerDialog = false }) { Text("Close") }
            },
        )
    }

}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(20.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

