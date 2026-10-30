package com.rushi.modulesmanager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rushi.modulesmanager.AppViewModel
import com.rushi.modulesmanager.ModuleUpdate
import com.rushi.modulesmanager.ReadmeTranslationState
import com.rushi.modulesmanager.data.ModuleVersion
import com.rushi.modulesmanager.data.SourceAlternative
import com.rushi.modulesmanager.data.RepoAsset
import com.rushi.modulesmanager.data.RepoModule
import com.rushi.modulesmanager.data.RepoRelease
import com.rushi.modulesmanager.data.UpdateChannel
import com.rushi.modulesmanager.data.preferredAsset
import com.rushi.modulesmanager.data.formatBytes
import com.rushi.modulesmanager.data.formatDate
import com.rushi.modulesmanager.data.stripHtml
import com.rushi.modulesmanager.ui.components.EmptyState
import com.rushi.modulesmanager.ui.components.LoadingState
import com.rushi.modulesmanager.ui.components.ModuleAvatar
import com.rushi.modulesmanager.ui.components.PackageLabel
import com.rushi.modulesmanager.ui.components.StarBadge
import com.rushi.modulesmanager.ui.components.StatusPill
import com.rushi.modulesmanager.ui.components.VersionPill
import com.rushi.modulesmanager.ui.components.shortVersion
import com.rushi.modulesmanager.ui.theme.amoledCardBorder
import com.rushi.modulesmanager.ui.theme.amoledCardContainerColor

private val tabs = listOf("Overview", "Releases", "Information")

@Composable
fun ModuleDetailScreen(
    viewModel: AppViewModel,
    packageName: String,
    onBack: () -> Unit,
    onOpenDeveloper: (String) -> Unit,
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val loading by viewModel.detailLoading.collectAsStateWithLifecycle()
    val detailError by viewModel.detailError.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val readmeTranslation by viewModel.readmeTranslation.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showOriginalReadme by rememberSaveable(packageName) { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf(false) }
    var sourceInput by remember { mutableStateOf("") }

    val context = LocalContext.current
    val module = detail
    val installedVersion = installedVersions[packageName]?.versionName
    val installedHasLauncher = remember(packageName, installedVersion) {
        context.packageManager.getLaunchIntentForPackage(packageName) != null
    }
    val update = updates.firstOrNull { it.installed.packageName == packageName }
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val sourceAlternatives by viewModel.sourceAlternatives.collectAsStateWithLifecycle()
    val release = module?.releaseFor(channel)
    val primaryAsset = release?.preferredAsset()
    val latest = module?.latest(channel)

    LaunchedEffect(packageName) { viewModel.openModule(packageName) }
    DisposableEffect(packageName) {
        onDispose { viewModel.closeModule() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleFavourite(packageName) }) {
                        Icon(
                            imageVector = if (packageName in favourites) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Outlined.FavoriteBorder
                            },
                            contentDescription = if (packageName in favourites) {
                                "Remove from favourites"
                            } else {
                                "Add to favourites"
                            },
                            tint = if (packageName in favourites) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                    if (tab == 0 && module != null) {
                        val translating = readmeTranslation?.loading == true
                        val translated = readmeTranslation?.html != null
                        IconButton(
                            enabled = !translating,
                            onClick = {
                                if (translated) {
                                    showOriginalReadme = !showOriginalReadme
                                } else {
                                    showOriginalReadme = false
                                    viewModel.translateReadme()
                                }
                            },
                        ) {
                            if (translating) {
                                val progress = readmeTranslation?.progress ?: 0f
                                if (progress > 0f) {
                                    CircularProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.Translate,
                                    contentDescription = when {
                                        !translated -> "Translate README"
                                        showOriginalReadme -> "Show translation"
                                        else -> "Show original"
                                    },
                                    tint = if (translated && !showOriginalReadme) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        LocalContentColor.current
                                    },
                                )
                            }
                        }
                    }
                    module?.releasesPageUrl()?.let { url ->
                        IconButton(onClick = { viewModel.openLink(url) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = "Open releases page",
                            )
                        }
                    }
                    IconButton(onClick = { copyToClipboard(context, packageName) }) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy package name",
                        )
                    }
                    if (module != null && viewModel.canOverrideSource(packageName)) {
                        IconButton(onClick = {
                            sourceInput = module.githubRepoFullName() ?: module.sourceUrl.orEmpty()
                            editingSource = true
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Set source repository",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (module == null) {
                if (loading) {
                    LoadingState("Loading module details...")
                } else {
                    EmptyState(
                        icon = Icons.Outlined.Info,
                        title = "Module not found",
                        body = detailError ?: "This module is not part of the selected repository.",
                    )
                }
                return@Column
            }

            DetailHeader(
                module = module,
                installedVersion = installedVersion,
                installedHasLauncher = installedHasLauncher,
                update = update,
                latest = latest,
                channel = channel,
                primaryAsset = primaryAsset,
                onInstall = {
                    if (release != null) viewModel.installRelease(module, release)
                },
                onOpenModule = { viewModel.openInstalledModule(packageName) },
                onOpenAppInfo = { viewModel.openAppInfo(packageName) },
                onOpenDeveloper = onOpenDeveloper,
            )

            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (tab) {
                    0 -> OverviewTab(
                        module = module,
                        installing = loading,
                        translation = readmeTranslation,
                        showOriginal = showOriginalReadme,
                        onOpenLink = viewModel::openLink,
                    )
                    1 -> ReleasesTab(
                        module = module,
                        loading = loading,
                        onInstall = { asset ->
                            viewModel.installChosen(module, asset)
                        },
                        onOpenRelease = viewModel::openLink,
                    )
                    else -> InformationTab(
                        module = module,
                        onOpenLink = viewModel::openLink,
                    )
                }
            }
        }
    }

    if (editingSource && module != null) {
        AlertDialog(
            onDismissRequest = { editingSource = false },
            title = { Text("Set source repository") },
            text = {
                Column {
                    Text(
                        text = "Used for the owner, avatar, README and release links when the index points to a fork or the wrong repository.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val knownSources = remember(module, sourceAlternatives) {
                        val current = module.githubRepoFullName()
                        val listed = sourceAlternatives[module.packageName].orEmpty()
                        (if (current != null) {
                            listed + SourceAlternative(current, null, null)
                        } else {
                            listed
                        }).distinctBy { it.repo }
                            .sortedByDescending { it.releaseTime ?: "" }
                    }
                    if (knownSources.size > 1) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Repositories in the index",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            knownSources.forEach { candidate ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { sourceInput = candidate.repo }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = sourceInput == candidate.repo,
                                        onClick = { sourceInput = candidate.repo },
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = candidate.repo,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        candidate.releaseTime?.let { time ->
                                            Text(
                                                text = "Last release ${time.substringBefore('T')}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (candidate.repo == module.githubRepoFullName()) {
                                        Text(
                                            text = "Current",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Or enter another repository",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    OutlinedTextField(
                        value = sourceInput,
                        onValueChange = { sourceInput = it },
                        placeholder = { Text("owner/repo or https://github.com/owner/repo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.setModuleSource(module, sourceInput)) editingSource = false
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (viewModel.hasSourceOverride(packageName)) {
                        TextButton(onClick = {
                            viewModel.clearModuleSource(packageName)
                            editingSource = false
                        }) { Text("Reset") }
                    }
                    TextButton(onClick = { editingSource = false }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun DetailHeader(
    module: RepoModule,
    installedVersion: String?,
    installedHasLauncher: Boolean,
    update: ModuleUpdate?,
    latest: ModuleVersion?,
    channel: UpdateChannel,
    primaryAsset: RepoAsset?,
    onInstall: () -> Unit,
    onOpenModule: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenDeveloper: (String) -> Unit,
) {
    val updateVersion = update?.version
    val hasUpdate = updateVersion != null
    val isInstalled = installedVersion != null
    val latestDate = formatDate(module.latestTime(channel))
    // Installed modules already show their version in the pills below.
    val latestLabel = when {
        latest == null -> ""
        isInstalled -> if (latestDate != null) "Released" else ""
        else -> "Latest ${latest.name}"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp, bottom = 12.dp),
    ) {
        Row {
            ModuleAvatar(module, size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                PackageLabel(module.packageName)
                module.developerLogin()?.let { developer ->
                    Text(
                        text = "by $developer",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .clickable { onOpenDeveloper(developer) },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StarBadge(module.stargazerCount)
                    if (latestLabel.isNotBlank()) {
                        Text(
                            text = latestLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (latestDate != null) StatusPill(text = latestDate)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        hasUpdate -> VersionPill(
                            text = "v${installedVersion.orEmpty()} \u2192 v${shortVersion(updateVersion.name)}",
                        )
                        isInstalled -> VersionPill(
                            text = "Installed v${shortVersion(installedVersion)}",
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        val primaryLabel = when {
            hasUpdate -> "Update"
            isInstalled && installedHasLauncher -> "Open"
            isInstalled -> "App info"
            else -> "Install"
        }
        val primaryEnabled = when {
            hasUpdate -> primaryAsset != null
            isInstalled -> true
            else -> primaryAsset != null
        }
        Button(
            onClick = {
                when {
                    hasUpdate || !isInstalled -> onInstall()
                    installedHasLauncher -> onOpenModule()
                    else -> onOpenAppInfo()
                }
            },
            enabled = primaryEnabled,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!isInstalled || hasUpdate || !installedHasLauncher) {
                Icon(
                    imageVector = if (!isInstalled || hasUpdate) {
                        Icons.Outlined.Download
                    } else {
                        Icons.Outlined.Info
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(primaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OverviewTab(
    module: RepoModule,
    installing: Boolean,
    translation: ReadmeTranslationState?,
    showOriginal: Boolean,
    onOpenLink: (String) -> Unit,
) {
    val readme = module.readmeHtml
    if (readme.isNullOrBlank() && translation?.html == null) {
        val fallback = module.readme ?: module.summary
        if (fallback.isNullOrBlank()) {
            EmptyState(
                icon = Icons.Outlined.Extension,
                title = "No description",
                body = "This module has not published a README.",
            )
        } else {
            Text(
                text = fallback,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            )
        }
        return
    }
    val html = translation?.html?.takeUnless { showOriginal } ?: readme
    if (html != null) {
        ReadmeWebView(
            html = html,
            repoFullName = module.githubRepoFullName(),
            onOpenLink = onOpenLink,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ReadmeWebView(
    html: String,
    repoFullName: String?,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val baseUrl = repoFullName?.let { "https://github.com/$it/" } ?: "https://github.com"
    val rendered = remember(html, repoFullName) { rewriteRelativeImages(html, repoFullName) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.stopLoading()
            webViewInstance?.destroy()
            webViewInstance = null
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webViewInstance = this
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.loadsImagesAutomatically = true
                settings.textZoom = 100
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        onOpenLink(request.url.toString())
                        return true
                    }
                }
                loadDataWithBaseURL(baseUrl, wrapReadme(rendered, dark), "text/html", "utf-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(baseUrl, wrapReadme(rendered, dark), "text/html", "utf-8", null)
        },
    )
}

private val RELATIVE_IMG_SRC = Regex("""(<img\b[^>]*\bsrc=")(?!https?:|data:)([^"]+)""", RegexOption.IGNORE_CASE)

// GitHub's README HTML keeps relative asset paths. Load them from the raw repo
// instead of the GitHub site root, where they do not exist.
private fun rewriteRelativeImages(html: String, repoFullName: String?): String {
    if (repoFullName.isNullOrBlank()) return html
    val rawBase = "https://raw.githubusercontent.com/$repoFullName/HEAD/"
    return RELATIVE_IMG_SRC.replace(html) { match ->
        val path = match.groupValues[2].removePrefix("./").removePrefix("/")
        match.groupValues[1] + rawBase + path
    }
}

private fun wrapReadme(html: String, dark: Boolean): String {
    val colors = if (dark) {
        "body{color:#DDE4E4;background:transparent}a{color:#80D4DA}code,pre{background:rgba(255,255,255,.08);color:#DDE4E4}hr{border-color:rgba(255,255,255,.15)}td,th{border-color:rgba(255,255,255,.2)}blockquote{color:#BEC8C9;border-left:3px solid rgba(255,255,255,.2)}"
    } else {
        "body{color:#161D1E;background:transparent}a{color:#00696E}code,pre{background:rgba(0,0,0,.05);color:#161D1E}hr{border-color:rgba(0,0,0,.12)}td,th{border-color:rgba(0,0,0,.15)}blockquote{color:#3F4849;border-left:3px solid rgba(0,0,0,.15)}"
    }
    return """
        <!doctype html>
        <html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        body{font-family:Roboto,sans-serif;font-size:15px;line-height:1.55;margin:16px;}
        img{max-width:100%;height:auto;}
        pre{overflow:auto;padding:10px;border-radius:8px;}
        code{font-family:monospace;font-size:13px;padding:1px 4px;border-radius:4px;}
        pre code{padding:0;}
        table{border-collapse:collapse;}
        td,th{border:1px solid;padding:5px 8px;}
        blockquote{margin:8px 0;padding-left:10px;}
        h1,h2,h3{line-height:1.25;}
        $colors
        </style></head>
        <body>$html</body></html>
    """.trimIndent()
}

@Composable
private fun ReleasesTab(
    module: RepoModule,
    loading: Boolean,
    onInstall: (RepoAsset) -> Unit,
    onOpenRelease: (String?) -> Unit,
) {
    val releases = module.allReleases()
    if (releases.isEmpty()) {
        if (loading) {
            LoadingState("Loading releases...")
        } else {
            EmptyState(
                icon = Icons.Outlined.Info,
                title = "No releases",
                body = "This module has not published any releases on GitHub.",
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(releases, key = { it.url ?: it.tagName ?: it.toString() }) { item ->
            ReleaseCard(
                release = item,
                onInstall = onInstall,
                onOpenRelease = { onOpenRelease(item.url) },
            )
        }
    }
}

@Composable
private fun ReleaseCard(
    release: RepoRelease,
    onInstall: (RepoAsset) -> Unit,
    onOpenRelease: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = amoledCardContainerColor()),
        border = amoledCardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = release.name ?: release.tagName ?: "Release",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatDate(release.publishedAt) ?: "Unknown date",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (release.isPrerelease) {
                    StatusPill(text = "Pre-release")
                    Spacer(Modifier.width(4.dp))
                }
                if (release.url != null) {
                    IconButton(onClick = onOpenRelease) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open release page")
                    }
                }
            }
            val notes = stripHtml(release.descriptionHtml)
            if (!notes.isNullOrBlank()) {
                var expanded by rememberSaveable { mutableStateOf(false) }
                var canExpand by rememberSaveable { mutableStateOf(false) }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout ->
                        if (!expanded && layout.hasVisualOverflow) canExpand = true
                    },
                )
                if (canExpand) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) {
                        Text(if (expanded) "Show less" else "Read more")
                    }
                }
            }
            if (release.apkAssets.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                release.apkAssets.forEach { asset ->
                    AssetRow(asset = asset, onInstall = { onInstall(asset) })
                }
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "No APK attached to this release",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AssetRow(asset: RepoAsset, onInstall: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = asset.name,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = buildList {
                if (asset.size > 0) add(formatBytes(asset.size))
                if (asset.downloadCount > 0) add("${asset.downloadCount} downloads")
            }.joinToString(" - ")
            if (details.isNotEmpty()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onInstall) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Install")
        }
    }
}

@Composable
private fun InformationTab(
    module: RepoModule,
    onOpenLink: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        module.url?.let { InfoRow("Releases", it, onClick = { onOpenLink(it) }) }
        if (module.collaborators.isNotEmpty()) {
            InfoRow(
                title = "Maintainers",
                value = module.collaborators.joinToString(", ") { it.name ?: it.login },
            )
        }
        InfoRow("Stars", module.stargazerCount.toString())
        InfoRow("Forks", module.forkCount.toString())
        if (module.scope.isNotEmpty()) {
            InfoRow(
                title = "Target apps",
                value = module.scope.joinToString("\n"),
            )
        }
        HorizontalDivider()
        Text(
            text = "Version strings in this repository look like versionCode-versionName. Updates are matched by package name and version code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("package name", text))
    Toast.makeText(context, "Package name copied", Toast.LENGTH_SHORT).show()
}
