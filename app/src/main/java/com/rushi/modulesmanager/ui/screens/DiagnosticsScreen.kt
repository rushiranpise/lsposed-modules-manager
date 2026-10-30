package com.rushi.modulesmanager.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rushi.modulesmanager.AppViewModel
import com.rushi.modulesmanager.BuildConfig
import com.rushi.modulesmanager.data.AppStorage
import com.rushi.modulesmanager.data.formatBytes
import com.rushi.modulesmanager.ui.components.HeaderDivider
import com.rushi.modulesmanager.ui.theme.amoledCardBorder
import com.rushi.modulesmanager.ui.theme.amoledCardContainerColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class StorageStats(
    val index: Long = 0L,
    val readme: Long = 0L,
    val apks: Long = 0L,
)

@Composable
fun DiagnosticsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val installed by viewModel.allInstalled.collectAsStateWithLifecycle()
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()

    var stats by remember { mutableStateOf(StorageStats()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        stats = withContext(Dispatchers.IO) {
            StorageStats(
                index = AppStorage.indexCacheSize(context),
                readme = AppStorage.readmeCacheSize(context),
                apks = AppStorage.downloadedApkSize(context),
            )
        }
    }

    val report = buildString {
        appendLine("Modules Manager ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Build: ${BuildConfig.BUILD_TYPE} ${BuildConfig.GIT_COMMIT}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Installed modules: ${installed.size}")
        appendLine("Repository modules: ${modules.size}")
        appendLine("Updates available: ${updates.size}")
        appendLine("Index and README cache: ${formatStorage(stats.index + stats.readme)}")
        appendLine("Downloaded APKs: ${formatStorage(stats.apks)}")
    }.trimEnd()

    fun refreshStorage() {
        scope.launch {
            delay(300)
            reload++
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Diagnostics") },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = amoledCardContainerColor(),
                ),
                border = amoledCardBorder(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(14.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(report))
                        scope.launch { snackbar.showSnackbar("Report copied") }
                    },
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, report)
                            putExtra(Intent.EXTRA_SUBJECT, "Modules Manager diagnostics")
                        }
                        context.startActivity(Intent.createChooser(intent, "Share diagnostics"))
                    },
                ) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("Storage", style = MaterialTheme.typography.titleSmall)

            StorageRow(
                title = "Index and README cache",
                detail = formatStorage(stats.index + stats.readme),
                onClear = {
                    viewModel.clearIndexCache()
                    refreshStorage()
                },
            )
            StorageRow(
                title = "Downloaded APKs",
                detail = formatStorage(stats.apks),
                onClear = {
                    viewModel.clearDownloadedApks()
                    refreshStorage()
                },
            )

            Text(
                text = "The index cache is fetched again after clearing. Downloaded APKs are only needed until the installer finishes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Storage totals are a real zero when the folder is empty, unlike an unknown download size. */
private fun formatStorage(bytes: Long): String =
    if (bytes <= 0L) "0 B" else formatBytes(bytes)

@Composable
private fun StorageRow(title: String, detail: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClear) { Text("Clear") }
    }
}
