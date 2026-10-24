package com.rushi.modulesmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rushi.modulesmanager.data.ApkInspector
import com.rushi.modulesmanager.data.ApkInstaller
import com.rushi.modulesmanager.data.ApkVariants
import com.rushi.modulesmanager.data.AppTheme
import com.rushi.modulesmanager.data.DefaultSources
import com.rushi.modulesmanager.data.DownloadCenter
import com.rushi.modulesmanager.data.DownloadService
import com.rushi.modulesmanager.data.InstalledModule
import com.rushi.modulesmanager.data.InstalledPackageVersion
import com.rushi.modulesmanager.data.InstallerType
import com.rushi.modulesmanager.data.LiveReleaseApi
import com.rushi.modulesmanager.data.LiveUpdateRateLimitException
import com.rushi.modulesmanager.data.ModuleVersion
import com.rushi.modulesmanager.data.PendingInstall
import com.rushi.modulesmanager.data.ReadmeTranslator
import com.rushi.modulesmanager.data.RepoAsset
import com.rushi.modulesmanager.data.RepoModule
import com.rushi.modulesmanager.data.RepoRelease
import com.rushi.modulesmanager.data.RootInstaller
import com.rushi.modulesmanager.data.ShizukuInstaller
import com.rushi.modulesmanager.data.SortMode
import com.rushi.modulesmanager.data.TranslateApi
import com.rushi.modulesmanager.data.TranslationLanguages
import com.rushi.modulesmanager.data.UpdateChannel
import com.rushi.modulesmanager.data.preferredAsset
import com.rushi.modulesmanager.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class ModuleUpdate(
    val installed: InstalledModule,
    val module: RepoModule,
    val version: ModuleVersion,
)

/** A release with several equally suitable APKs, waiting for the user's choice. */
data class VariantChoice(
    val module: RepoModule,
    val release: RepoRelease,
    val assets: List<RepoAsset>,
)

enum class BatchPhase { QUEUED, DOWNLOADING, WAITING_TO_INSTALL, INSTALLING, COMPLETED, FAILED }

data class BatchItemState(
    val title: String,
    val phase: BatchPhase,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    val error: String? = null,
)

data class BatchUpdateState(
    val running: Boolean,
    val total: Int,
    val current: String? = null,
    val items: Map<String, BatchItemState> = emptyMap(),
    /** True when a run was restored after the process stopped before it could finish. */
    val interrupted: Boolean = false,
) {
    val finished: Int
        get() = items.values.count {
            it.phase == BatchPhase.COMPLETED || it.phase == BatchPhase.FAILED
        }
}

private sealed interface InstallResult {
    data object Success : InstallResult
    data object Delegated : InstallResult
    data class Failure(val message: String) : InstallResult
}

private data class PreparedInstall(
    val update: ModuleUpdate,
    val file: File,
    val asset: RepoAsset,
)

data class TranslationState(val text: String? = null, val loading: Boolean = false)

private data class LibraryState(
    val skipped: Map<String, String> = emptyMap(),
    val ignored: Set<String> = emptySet(),
    val preRelease: Set<String> = emptySet(),
    val links: Map<String, String> = emptyMap(),
)

data class ReadmeTranslationState(
    val loading: Boolean = false,
    val progress: Float = 0f,
    val html: String? = null,
    val language: String? = null,
)

class AppViewModel(private val container: AppContainer) : ViewModel() {

    private val settings = container.settings
    private val loadMutex = Mutex()

    val channel: StateFlow<UpdateChannel> = settings.channel
    val sortMode: StateFlow<SortMode> = settings.sortMode
    val installer: StateFlow<InstallerType> = settings.installer
    val theme: StateFlow<AppTheme> = settings.theme
    val amoled: StateFlow<Boolean> = settings.amoled
    val systemColors: StateFlow<Boolean> = settings.systemColors
    val updatesFirst: StateFlow<Boolean> = settings.updatesFirst
    val skippedVersions: StateFlow<Map<String, String>> = container.moduleState.skipped
    val ignoredUpdates: StateFlow<Set<String>> = container.moduleState.ignored
    val preReleaseModules: StateFlow<Set<String>> = container.moduleState.preRelease
    val moduleLinks: StateFlow<Map<String, String>> = container.moduleState.links

    private val libraryState = combine(
        skippedVersions,
        ignoredUpdates,
        preReleaseModules,
        moduleLinks,
    ) { skipped, ignored, preRelease, links ->
        LibraryState(
            skipped = skipped,
            ignored = ignored,
            preRelease = preRelease,
            links = links,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())

    val autoTranslate: StateFlow<Boolean> = settings.autoTranslate
    val translateLanguage: StateFlow<String?> = settings.translateLanguage

    private val _shizukuStatus = MutableStateFlow(ShizukuInstaller.status())
    val shizukuStatus: StateFlow<ShizukuInstaller.Status> = _shizukuStatus.asStateFlow()

    private val shizukuBinderReceived = Shizuku.OnBinderReceivedListener { refreshShizukuStatus() }
    private val shizukuBinderDead = Shizuku.OnBinderDeadListener { refreshShizukuStatus() }

    val modules: StateFlow<List<RepoModule>> = container.repo.modules
    val loading: StateFlow<Boolean> = container.repo.loading
    val refreshing: StateFlow<Boolean> = container.repo.refreshing
    val error: StateFlow<String?> = container.repo.error
    val lastUpdated: StateFlow<Long?> = container.repo.lastUpdated
    val downloads: StateFlow<DownloadCenter.State?> = container.downloads.state

    private val _repoInstalled = MutableStateFlow<List<InstalledModule>>(emptyList())
    val repoInstalled: StateFlow<List<InstalledModule>> = _repoInstalled.asStateFlow()

    /** Every installed Xposed module, including those the daily index does not list. */
    private val _allInstalled = MutableStateFlow<List<InstalledModule>>(emptyList())
    val allInstalled: StateFlow<List<InstalledModule>> = _allInstalled.asStateFlow()

    private val _installedVersions = MutableStateFlow<Map<String, InstalledPackageVersion>>(emptyMap())
    val installedVersions: StateFlow<Map<String, InstalledPackageVersion>> = _installedVersions.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _translations = MutableStateFlow<Map<String, TranslationState>>(emptyMap())
    val translations: StateFlow<Map<String, TranslationState>> = _translations.asStateFlow()

    /** Detected language of each module description, so rows already in the target language
     * can hide the translate action. */
    private val _descriptionLanguages = MutableStateFlow<Map<String, String>>(emptyMap())
    val descriptionLanguages: StateFlow<Map<String, String>> = _descriptionLanguages.asStateFlow()

    private val _detail = MutableStateFlow<RepoModule?>(null)
    val detail: StateFlow<RepoModule?> = _detail.asStateFlow()

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    private val _detailError = MutableStateFlow<String?>(null)
    val detailError: StateFlow<String?> = _detailError.asStateFlow()

    private val _readmeTranslation = MutableStateFlow<ReadmeTranslationState?>(null)
    val readmeTranslation: StateFlow<ReadmeTranslationState?> = _readmeTranslation.asStateFlow()

    private val _batchUpdate = MutableStateFlow<BatchUpdateState?>(null)
    val batchUpdate: StateFlow<BatchUpdateState?> = _batchUpdate.asStateFlow()

    private val _variantChoice = MutableStateFlow<VariantChoice?>(null)
    val variantChoice: StateFlow<VariantChoice?> = _variantChoice.asStateFlow()

    /** Synthetic modules for manual GitHub links, keyed by the installed package name. */
    private val _manualModules = MutableStateFlow<Map<String, RepoModule>>(emptyMap())
    val manualModules: StateFlow<Map<String, RepoModule>> = _manualModules.asStateFlow()
    private val loadedManualLinks = mutableSetOf<String>()

    private var detailJob: Job? = null
    private var readmeTranslationJob: Job? = null
    private var downloadJob: Job? = null
    private var batchJob: Job? = null
    private var batchPersisting = false
    private var shizukuPermissionJob: Job? = null
    private var installedRebuildJob: Job? = null
    private var liveCheckJob: Job? = null
    private var liveCheckAttempted = false
    private var discoveryJob: Job? = null
    private val discoveryAttempted = mutableSetOf<String>()
    private var detectedModules: List<InstalledModule> = emptyList()
    private var installedVersionMap: Map<String, InstalledPackageVersion> = emptyMap()

    /** The system installer can finish while this process is gone. */
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val packageName = intent?.data?.schemeSpecificPart ?: return
            if (container.pendingInstalls.isPending(packageName)) rescan()
        }
    }

    val updates: StateFlow<List<ModuleUpdate>> = combine(
        modules,
        repoInstalled,
        channel,
        libraryState,
        manualModules,
    ) { repoModules, installedModules, selectedChannel, library, manual ->
        val byPackage = (repoModules + manual.values).associateBy { it.packageName }
        installedModules.mapNotNull { installedModule ->
            val module = byPackage[installedModule.packageName]
                ?: library.links[installedModule.packageName]?.let { byPackage[it] }
                ?: return@mapNotNull null
            if (installedModule.packageName in library.ignored || module.packageName in library.ignored) {
                return@mapNotNull null
            }
            val includePreRelease = selectedChannel == UpdateChannel.PRE_RELEASE ||
                module.packageName in library.preRelease ||
                installedModule.packageName in library.preRelease
            val latest = module.latestFor(includePreRelease) ?: return@mapNotNull null
            val skipped = library.skipped[installedModule.packageName]
                ?: library.skipped[module.packageName]
            if (skipped != null && skipped == module.latestRawFor(includePreRelease)) {
                return@mapNotNull null
            }
            val version = _installedVersions.value[installedModule.packageName]
            val isUpdate = if (version != null) {
                latest.isUpdateOver(version, module.latestTimeFor(includePreRelease))
            } else {
                latest.isNewerThan(installedModule.versionCode, installedModule.versionName)
            }
            if (isUpdate) ModuleUpdate(installedModule, module, latest) else null
        }.sortedBy { it.module.displayName.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived)
            Shizuku.addBinderDeadListener(shizukuBinderDead)
        }
        runCatching {
            ContextCompat.registerReceiver(
                container.appContext,
                packageChangeReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        restoreBatch()
        viewModelScope.launch {
            _batchUpdate.collect { state ->
                if (batchPersisting && state?.running == true) container.batchState.save(state)
            }
        }
        rescan()
        viewModelScope.launch {
            loadMutex.withLock { container.repo.load(DefaultSources.official) }
        }
        viewModelScope.launch {
            modules.map { list -> list.map { it.packageName } }
                .distinctUntilChanged()
                .collectLatest { rebuildRepoInstalled() }
        }
        viewModelScope.launch {
            repoInstalled.collectLatest { installed ->
                // The live releases are held in memory, so a fresh process has to check once even
                // when the saved timestamp is still inside the six-hour window.
                if (installed.isNotEmpty()) refreshLiveUpdates(force = !liveCheckAttempted)
            }
        }
        viewModelScope.launch {
            moduleLinks.collect { links -> refreshManualModules(links) }
        }
        viewModelScope.launch {
            manualModules.collectLatest { rebuildRepoInstalled() }
        }
        viewModelScope.launch {
            combine(settings.autoTranslate, settings.translateLanguage, modules) { auto, language, list ->
                Triple(auto, language, list)
            }.collect { (auto, language, list) ->
                if (auto) autoTranslateDescriptions(list, language)
            }
        }
        viewModelScope.launch {
            // Detection goes through the network now, so it only runs when translation is on.
            combine(settings.autoTranslate, modules) { auto, list -> auto to list }
                .distinctUntilChanged()
                .collect { (auto, list) ->
                    if (!auto) return@collect
                    val entries = list.map { it.packageName to it.summary.orEmpty() }
                    // A network failure must not take the app down with it.
                    val detected = try {
                        TranslateApi.detectAll(entries.map { it.second })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@collect
                    }
                    _descriptionLanguages.value = buildMap {
                        entries.forEachIndexed { index, (packageName, summary) ->
                            if (summary.isNotBlank()) {
                                detected.getOrNull(index)?.let { put(packageName, it) }
                            }
                        }
                    }
                }
        }
    }

    fun rescan(showMessage: Boolean = false) {
        viewModelScope.launch {
            val scanned = withContext(Dispatchers.IO) { container.scanner.scan() }
            detectedModules = scanned.modules
            installedVersionMap = scanned.versions
            _installedVersions.value = scanned.versions
            reconcilePendingInstalls()
            rebuildRepoInstalled()
            if (showMessage) message("Rescanned installed modules")
        }
    }

    /**
     * Combines Xposed-detected modules with repository packages installed without metadata.
     * The repository list drives the Modules filter and update checks; the full list drives the
     * Installed tab, which also shows modules the daily index does not know about.
     */
    private fun rebuildRepoInstalled() {
        installedRebuildJob?.cancel()
        val repoPackages = (modules.value.map { it.packageName } +
            container.moduleState.links.value.keys).distinct()
        val versions = installedVersionMap
        val detected = detectedModules.associateBy { it.packageName }
        if (versions.isEmpty()) {
            _repoInstalled.value = emptyList()
            _allInstalled.value = detectedModules.sortedBy { it.label.lowercase() }
            return
        }
        installedRebuildJob = viewModelScope.launch {
            val full = withContext(Dispatchers.IO) {
                if (repoPackages.isEmpty()) {
                    emptyList()
                } else {
                    repoPackages.asSequence()
                        .filter { versions.containsKey(it) }
                        .mapNotNull { detected[it] ?: container.scanner.installedModule(it) }
                        .distinctBy { it.packageName }
                        .toList()
                }
            }
            _repoInstalled.value = full.sortedBy { it.label.lowercase() }
            val notDetected = full.filter { it.packageName !in detected }
            _allInstalled.value = (detectedModules + notDetected)
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            discoverMissingInstalledModules()
        }
    }

    /**
     * Some entries in the daily index have no fetched release. If one of those packages is
     * installed, ask its GitHub mirror for a release and add it, so installed counts stay exact.
     */
    private fun discoverMissingInstalledModules() {
        if (discoveryJob?.isActive == true) return
        val candidates = container.repo.missingReleasePackages().filter { packageName ->
            installedVersionMap.containsKey(packageName) &&
                container.repo.cachedModule(packageName) == null &&
                packageName !in discoveryAttempted
        }
        if (candidates.isEmpty()) return
        discoveryAttempted += candidates

        discoveryJob = viewModelScope.launch {
            for (packageName in candidates) {
                val repo = "Xposed-Modules-Repo/$packageName"
                try {
                    val live = container.liveApi.fetchLatest(repo) ?: continue
                    val release = live.toRepoRelease()
                    val segment = packageName.substringAfterLast('.')
                        .replace('-', ' ')
                        .replace('_', ' ')
                        .trim()
                    val displayName = segment
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        .ifBlank { packageName }
                    container.repo.addDiscoveredModule(
                        RepoModule(
                            packageName = packageName,
                            displayName = displayName,
                            summary = null,
                            url = release.url,
                            homepageUrl = null,
                            sourceUrl = "https://github.com/$repo",
                            latestRelease = live.tag,
                            latestReleaseTime = release.publishedAt,
                            latestBetaRelease = null,
                            latestBetaReleaseTime = null,
                            latestSnapshotRelease = null,
                            latestSnapshotReleaseTime = null,
                            releases = listOf(release),
                            betaReleases = emptyList(),
                            snapshotReleases = emptyList(),
                            readme = null,
                            readmeHtml = null,
                            collaborators = emptyList(),
                            scope = emptyList(),
                            hide = false,
                            updatedAt = release.publishedAt,
                            stargazerCount = 0,
                            repoFullName = repo,
                        ),
                    )
                } catch (_: LiveUpdateRateLimitException) {
                    break
                } catch (_: Exception) {
                    // Leave the module out when its GitHub mirror has no release.
                }
            }
        }
    }

    fun refresh() {
        rescan()
        viewModelScope.launch {
            loadMutex.withLock { container.repo.load(DefaultSources.official) }
            refreshLiveUpdates(force = true)
        }
    }

    /**
     * The daily index is only fetched once a day. For installed modules, ask GitHub for the latest
     * release and merge it when it is newer than both the daily data and the installed version.
     */
    private fun refreshLiveUpdates(force: Boolean = false) {
        if (liveCheckJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!force && now - settings.lastLiveCheck() < LIVE_UPDATE_INTERVAL_MS) return
        val installed = repoInstalled.value
        if (installed.isEmpty()) return
        liveCheckAttempted = true

        liveCheckJob = viewModelScope.launch {
            val repoModules = container.repo.modules.value.associateBy { it.packageName }
            val stopped = AtomicBoolean(false)
            val semaphore = Semaphore(4)
            coroutineScope {
                installed.map { item ->
                    async(Dispatchers.IO) {
                        if (stopped.get()) return@async
                        semaphore.withPermit {
                            val module = repoModules[item.packageName] ?: return@withPermit
                            val repoFullName = module.repoFullName ?: return@withPermit
                            try {
                                val live = container.liveApi.fetchLatest(repoFullName) ?: return@withPermit
                                val liveVersion = ModuleVersion.parse(live.tag) ?: return@withPermit
                                val dailyVersion = ModuleVersion.parse(module.latestRelease)
                                if (dailyVersion != null &&
                                    !liveVersion.isNewerThan(dailyVersion.code, dailyVersion.name)
                                ) {
                                    return@withPermit
                                }
                                if (!liveVersion.isNewerThan(item.versionCode, item.versionName)) {
                                    return@withPermit
                                }
                                val release = live.toRepoRelease()
                                container.repo.applyLiveRelease(module.packageName, live.tag, release)
                                _detail.value = _detail.value?.let { detail ->
                                    if (detail.packageName == module.packageName) {
                                        detail.copy(
                                            latestRelease = live.tag,
                                            latestReleaseTime = release.publishedAt,
                                            releases = listOf(release),
                                        )
                                    } else {
                                        detail
                                    }
                                }
                            } catch (_: LiveUpdateRateLimitException) {
                                stopped.set(true)
                            } catch (_: Exception) {
                                // Keep the daily data when GitHub is unavailable.
                            }
                        }
                    }
                }.awaitAll()
            }
            // A rate-limited run is incomplete: keep the timestamp stale so it runs again.
            if (stopped.get()) liveCheckAttempted = false else settings.setLastLiveCheck(now)
        }
    }

    fun setChannel(channel: UpdateChannel) {
        settings.setChannel(channel)
    }

    fun setSortMode(mode: SortMode) {
        settings.setSortMode(mode)
    }

    fun setInstaller(installer: InstallerType) {
        settings.setInstaller(installer)
        if (installer == InstallerType.SHIZUKU) requestShizukuPermission()
    }

    fun refreshShizukuStatus() {
        _shizukuStatus.value = ShizukuInstaller.status()
    }

    /**
     * Asks Shizuku for the permission as soon as the mode is selected, instead of waiting for the
     * first install. The mode stays selected when Shizuku is not running: the install then falls
     * back to a session and the next attempt tries Shizuku again.
     */
    fun requestShizukuPermission() {
        refreshShizukuStatus()
        when (_shizukuStatus.value) {
            ShizukuInstaller.Status.READY -> {
                message("Shizuku permission is already granted")
                return
            }
            ShizukuInstaller.Status.NOT_RUNNING -> {
                message("Start Shizuku, then choose Shizuku again")
                return
            }
            ShizukuInstaller.Status.PERMISSION_REQUIRED -> Unit
        }
        if (shizukuPermissionJob?.isActive == true) return
        shizukuPermissionJob = viewModelScope.launch {
            val granted = ShizukuInstaller.ensurePermission()
            refreshShizukuStatus()
            message(if (granted) "Shizuku permission granted" else "Shizuku permission was not granted")
        }
    }

    fun openShizuku() {
        if (!ShizukuInstaller.openManager(container.appContext)) {
            message("Shizuku is not installed")
        }
    }

    fun setUpdatesFirst(enabled: Boolean) {
        settings.setUpdatesFirst(enabled)
    }

    fun setTheme(theme: AppTheme) {
        settings.setTheme(theme)
    }

    fun setAmoled(enabled: Boolean) {
        settings.setAmoled(enabled)
    }

    fun setSystemColors(enabled: Boolean) {
        settings.setSystemColors(enabled)
    }

    fun openModule(packageName: String) {
        detailJob?.cancel()
        readmeTranslationJob?.cancel()
        _readmeTranslation.value = null
        _detail.value = _manualModules.value[packageName]
            ?: container.repo.cachedModule(packageName)
        _detailError.value = null
        _detailLoading.value = true
        detailJob = viewModelScope.launch {
            val loaded = loadDetailedModule(packageName)
            if (loaded != null) {
                _detail.value = loaded
            } else if (_detail.value == null) {
                _detailError.value = "This module is not available in the selected repository."
            }
            _detailLoading.value = false

            // The daily index does not carry the README. Show the last one from disk first,
            // then refresh it from GitHub when available.
            val module = _detail.value
            if (module != null && module.readmeHtml.isNullOrBlank()) {
                val cached = withContext(Dispatchers.IO) {
                    container.repo.cachedReadme(packageName)
                }
                if (!cached.isNullOrBlank() && _detail.value?.packageName == packageName) {
                    container.repo.applyReadme(packageName, cached)
                    _detail.value = _detail.value?.copy(readmeHtml = cached)
                }

                val repoFullName = module.githubRepoFullName()
                if (repoFullName != null) {
                    val document = container.readmeApi.fetch(repoFullName)
                    if (document != null && _detail.value?.packageName == packageName) {
                        container.repo.applyReadme(packageName, document.html, document.markdown)
                        _detail.value = _detail.value?.copy(readmeHtml = document.html)
                    }
                }
            }

            // The daily index only carries the newest release per channel, so load the real
            // history from GitHub for the Releases tab. The Xposed-Modules-Repo mirror keeps the
            // release history, while the source repo can be stale or block the API.
            val shown = _detail.value
            if (shown != null) {
                val candidates = listOfNotNull(
                    "Xposed-Modules-Repo/${shown.packageName}",
                    shown.repoFullName,
                    shown.githubRepoFullName(),
                ).distinct()
                var history: List<LiveReleaseApi.LiveRelease> = emptyList()
                for (repo in candidates) {
                    val attempt = runCatching { container.liveApi.fetchAll(repo) }
                    val found = attempt.getOrNull().orEmpty()
                    if (found.isNotEmpty()) {
                        history = found
                        break
                    }
                    // Another repo cannot help when GitHub refused the request for rate limits.
                    if (attempt.exceptionOrNull() is LiveUpdateRateLimitException) break
                }
                if (history.isNotEmpty() && _detail.value?.packageName == packageName) {
                    container.repo.applyReleaseHistory(packageName, history.map { it.toRepoRelease() })
                    _detail.value = container.repo.cachedModule(packageName)
                }
            }

            if (settings.autoTranslate.value && _detail.value?.packageName == packageName) {
                translateReadme(announce = false)
            }
        }
    }

    fun closeModule() {
        detailJob?.cancel()
        readmeTranslationJob?.cancel()
        _readmeTranslation.value = null
        _detail.value = null
        _detailError.value = null
        _detailLoading.value = false
    }

    /** Translates the open README on demand, reusing a saved translation when there is one. */
    fun translateReadme(announce: Boolean = true) {
        val module = _detail.value ?: return
        if (readmeTranslationJob?.isActive == true) return
        val packageName = module.packageName
        val target = targetLanguage()
        val current = _readmeTranslation.value
        if (current?.html != null && current.language == target) return

        _readmeTranslation.value = ReadmeTranslationState(loading = true, language = target)
        readmeTranslationJob = viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) {
                    container.repo.cachedReadmeTranslation(packageName, target)
                }
                if (!cached.isNullOrBlank()) {
                    _readmeTranslation.value = ReadmeTranslationState(html = cached, language = target)
                    return@launch
                }

                val markdown = readmeMarkdown(module)
                if (markdown.isNullOrBlank()) {
                    _readmeTranslation.value = null
                    if (announce) message("This README has no Markdown text that can be translated")
                    return@launch
                }

                val result = ReadmeTranslator.translate(markdown, target) { progress ->
                    _readmeTranslation.update { it?.copy(progress = progress) }
                }
                if (!result.translated) {
                    _readmeTranslation.value = null
                    if (announce) message("This README is already in ${displayLanguage(target)}")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    container.repo.saveReadmeTranslation(packageName, target, result.html)
                }
                if (_detail.value?.packageName != packageName) return@launch
                _readmeTranslation.value = ReadmeTranslationState(html = result.html, language = target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _readmeTranslation.value = null
                if (announce) message(e.message ?: "Could not translate this README")
            }
        }
    }

    /** The raw Markdown of the open README, fetched from GitHub when only the HTML is cached. */
    private suspend fun readmeMarkdown(module: RepoModule): String? {
        val cached = withContext(Dispatchers.IO) {
            container.repo.cachedReadmeMarkdown(module.packageName)
        }
        if (!cached.isNullOrBlank()) return cached

        val repoFullName = module.githubRepoFullName() ?: return null
        val document = container.readmeApi.fetch(repoFullName) ?: return null
        val markdown = document.markdown ?: return null
        container.repo.applyReadme(module.packageName, document.html, markdown)
        return markdown
    }

    fun latestVersion(module: RepoModule): ModuleVersion? = module.latest(channel.value)

    fun moduleFor(packageName: String): RepoModule? = container.repo.cachedModule(packageName)

    fun updateFor(packageName: String): ModuleUpdate? = updates.value.firstOrNull { it.installed.packageName == packageName }

    fun canTrackPreReleases(module: RepoModule): Boolean =
        !module.latestBetaRelease.isNullOrBlank()

    fun skipVersion(module: RepoModule) {
        val includePreRelease = includePreReleases(module)
        val raw = module.latestRawFor(includePreRelease)
        if (raw.isNullOrBlank()) {
            message("There is no version to skip")
            return
        }
        container.moduleState.skipVersion(module.packageName, raw)
        message("Skipped ${module.latestFor(includePreRelease)?.name ?: raw}")
    }

    fun clearSkippedVersion(module: RepoModule) {
        container.moduleState.clearSkippedVersion(module.packageName)
        message("Skipped version cleared")
    }

    fun toggleIgnoreUpdates(module: RepoModule) {
        val ignored = container.moduleState.toggleIgnored(module.packageName)
        message(if (ignored) "Updates ignored for ${module.displayName}" else "Updates restored")
    }

    fun togglePreReleases(module: RepoModule) {
        val enabled = container.moduleState.togglePreRelease(module.packageName)
        message(if (enabled) "Following pre-releases for ${module.displayName}" else "Following stable releases")
    }

    /** Links an installed package to a GitHub repository entered by the user. */
    fun linkModule(installedPackage: String, repoInput: String) {
        val repo = parseGithubRepo(repoInput)
        if (repo == null) {
            message("Paste a GitHub URL like https://github.com/owner/repo")
            return
        }
        container.moduleState.linkModule(installedPackage, repo)
        loadedManualLinks.remove("$installedPackage|$repo")
        viewModelScope.launch {
            val module = loadManualModule(installedPackage, repo)
            if (module == null) {
                message("Could not load releases from $repo")
            } else {
                loadedManualLinks += "$installedPackage|$repo"
                _manualModules.update { it + (installedPackage to module) }
                message("Linked to $repo")
            }
        }
    }

    fun unlinkModule(installedPackage: String) {
        container.moduleState.unlinkModule(installedPackage)
        loadedManualLinks.removeAll { it.startsWith("$installedPackage|") }
        _manualModules.update { it - installedPackage }
        message("Manual link removed")
    }

    private suspend fun refreshManualModules(links: Map<String, String>) {
        _manualModules.update { current -> current.filterKeys { it in links } }
        for ((packageName, repo) in links) {
            val key = "$packageName|$repo"
            if (key in loadedManualLinks) continue
            val module = loadManualModule(packageName, repo) ?: continue
            loadedManualLinks += key
            _manualModules.update { it + (packageName to module) }
        }
    }

    /** Builds a module from a GitHub repository's releases for a manually linked package. */
    private suspend fun loadManualModule(packageName: String, repoFullName: String): RepoModule? =
        runCatching {
            val releases = container.liveApi.fetchAll(repoFullName).orEmpty()
            if (releases.isEmpty()) return@runCatching null
            val mapped = releases.map { it.toRepoRelease() }
            val stable = mapped.filter { !it.isPrerelease && !it.isSnapshot }
            val beta = mapped.filter { it.isPrerelease && !it.isSnapshot }
            val snapshots = mapped.filter { it.isSnapshot }
            val latest = stable.firstOrNull()
            RepoModule(
                packageName = packageName,
                displayName = repoFullName.substringAfter('/'),
                summary = null,
                url = "https://github.com/$repoFullName",
                homepageUrl = null,
                sourceUrl = "https://github.com/$repoFullName",
                latestRelease = latest?.tagName,
                latestReleaseTime = latest?.publishedAt,
                latestBetaRelease = beta.firstOrNull()?.tagName,
                latestBetaReleaseTime = beta.firstOrNull()?.publishedAt,
                latestSnapshotRelease = snapshots.firstOrNull()?.tagName,
                latestSnapshotReleaseTime = snapshots.firstOrNull()?.publishedAt,
                releases = stable,
                betaReleases = beta,
                snapshotReleases = snapshots,
                readme = null,
                readmeHtml = null,
                collaborators = emptyList(),
                scope = emptyList(),
                hide = false,
                updatedAt = latest?.publishedAt,
                stargazerCount = 0,
                repoFullName = repoFullName,
                releaseHistory = mapped,
            )
        }.getOrNull()

    private fun parseGithubRepo(input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return null
        val urlMatch = Regex(
            """(?:https?://)?(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)""",
            RegexOption.IGNORE_CASE,
        ).find(value)
        val shortMatch = Regex("""^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$""").find(value)
        val match = urlMatch ?: shortMatch ?: return null
        val owner = match.groupValues[1].trim()
        val repo = match.groupValues[2].trim().removeSuffix(".git")
        return if (owner.isBlank() || repo.isBlank()) null else "$owner/$repo"
    }

    /** Manual links need their releases fetched before the detail page can show them. */
    private suspend fun loadDetailedModule(packageName: String): RepoModule? {
        _manualModules.value[packageName]?.let { return it }
        val linkedRepo = container.moduleState.links.value[packageName]
        if (linkedRepo != null) {
            val module = loadManualModule(packageName, linkedRepo) ?: return null
            loadedManualLinks += "$packageName|$linkedRepo"
            _manualModules.update { it + (packageName to module) }
            return module
        }
        return container.repo.loadModule(DefaultSources.official, packageName)
    }

    private fun includePreReleases(module: RepoModule): Boolean =
        channel.value == UpdateChannel.PRE_RELEASE ||
            module.packageName in preReleaseModules.value

    fun install(module: RepoModule, asset: RepoAsset) {
        if (downloads.value != null || downloadJob?.isActive == true || batchJob?.isActive == true) {
            message("Wait for the current install to finish")
            return
        }
        val appContext = container.appContext
        val installerType = settings.installer.value
        if ((installerType == InstallerType.SYSTEM || installerType == InstallerType.SESSION) &&
            !ApkInstaller.canInstall(appContext)
        ) {
            message("Allow \"Install unknown apps\" for Modules Manager, then try again")
            ApkInstaller.openInstallPermissionSettings(appContext)
            return
        }

        DownloadService.start(appContext)
        val destination = apkDestination(appContext, module, asset)
        downloadJob = viewModelScope.launch {
            try {
                container.downloads.update(
                    DownloadCenter.State(
                        packageName = module.packageName,
                        label = module.displayName,
                        fileName = asset.name,
                        downloaded = 0,
                        total = asset.size,
                        phase = DownloadCenter.Phase.DOWNLOADING,
                    ),
                )
                val file = downloadApk(appContext, module, asset, destination) { done, total ->
                    container.downloads.progress(done, total)
                }
                container.downloads.phase(DownloadCenter.Phase.INSTALLING)
                markPending(module, asset)
                val result = installDownloaded(appContext, file, module, announce = true)
                container.downloads.update(null)
                when (result) {
                    InstallResult.Success -> {
                        container.pendingInstalls.remove(module.packageName)
                        message("${module.displayName} installed")
                        rescan()
                        file.delete()
                    }
                    InstallResult.Delegated -> message("Opening the system installer")
                    is InstallResult.Failure -> {
                        container.pendingInstalls.remove(module.packageName)
                        message(result.message)
                        file.delete()
                    }
                }
            } catch (e: CancellationException) {
                container.downloads.update(null)
                destination.delete()
                File(destination.path + ".part").delete()
                throw e
            } catch (e: Exception) {
                container.downloads.update(null)
                container.pendingInstalls.remove(module.packageName)
                destination.delete()
                File(destination.path + ".part").delete()
                message(e.userMessage())
            }
        }
    }

    /** Remembers the version the external installer is expected to land. */
    private fun markPending(
        module: RepoModule,
        asset: RepoAsset,
        version: ModuleVersion? = null,
    ): PendingInstall {
        val expected = version ?: module.allReleases()
            .firstOrNull { release -> release.assets.any { it.downloadUrl == asset.downloadUrl } }
            ?.let { ModuleVersion.parse(it.tagName ?: it.name) }
        val pending = PendingInstall(
            packageName = module.packageName,
            label = module.displayName,
            versionCode = expected?.code ?: 0L,
            versionName = expected?.name,
            startedAt = System.currentTimeMillis(),
        )
        container.pendingInstalls.put(pending)
        return pending
    }

    /** Keeps the serial install slot busy until the external installer lands the package. */
    private suspend fun awaitLanded(pending: PendingInstall): Boolean {
        val deadline = System.currentTimeMillis() + DELEGATED_INSTALL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val installed = withContext(Dispatchers.IO) {
                container.scanner.installedVersion(pending.packageName)
            }
            if (pending.landed(installed)) return true
            delay(1000)
        }
        val installed = withContext(Dispatchers.IO) {
            container.scanner.installedVersion(pending.packageName)
        }
        return pending.landed(installed)
    }

    /** Clears the installs that actually landed while the app was away. */
    private fun reconcilePendingInstalls() {
        val pending = container.pendingInstalls.all()
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        val completed = mutableListOf<PendingInstall>()
        val expired = mutableListOf<PendingInstall>()
        for (item in pending) {
            val installed = installedVersionMap[item.packageName]
            val landed = item.landed(installed)
            when {
                landed -> {
                    container.pendingInstalls.remove(item.packageName)
                    completed += item
                }
                now - item.startedAt > PENDING_INSTALL_TIMEOUT_MS -> {
                    container.pendingInstalls.remove(item.packageName)
                    expired += item
                }
            }
        }
        if (completed.isNotEmpty() || expired.isNotEmpty()) {
            val done = completed.map { it.packageName }.toSet()
            val failed = expired.map { it.packageName }.toSet()
            _batchUpdate.update { state ->
                state?.copy(
                    items = state.items.mapValues { (packageName, item) ->
                        when {
                            packageName in done && item.phase != BatchPhase.COMPLETED ->
                                item.copy(phase = BatchPhase.COMPLETED, error = null)
                            packageName in failed && item.phase == BatchPhase.INSTALLING ->
                                item.copy(
                                    phase = BatchPhase.FAILED,
                                    error = "The installer did not confirm the install",
                                )
                            else -> item
                        }
                    },
                )
            }
        }
        if (completed.isNotEmpty()) {
            message(
                if (completed.size == 1) {
                    "${completed.first().label} installed"
                } else {
                    "${completed.size} modules installed"
                },
            )
        }
    }

    private fun apkDestination(context: Context, module: RepoModule, asset: RepoAsset): File =
        File(context.cacheDir, "apk")
            .apply { mkdirs() }
            .resolve("${module.packageName}-${asset.name}".sanitizeFileName())

    /** Downloads and validates one APK. Shared by single installs and the update queue. */
    private suspend fun downloadApk(
        context: Context,
        module: RepoModule,
        asset: RepoAsset,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        val file = container.api.download(
            urls = listOfNotNull(asset.downloadUrl, asset.originalDownloadUrl),
            destination = destination,
            onProgress = onProgress,
        )
        val info = withContext(Dispatchers.IO) { ApkInspector.inspect(context, file) }
            ?: throw IllegalStateException("The downloaded file is not a valid APK")
        if (info.packageName != module.packageName) {
            throw IllegalStateException("This release contains ${info.packageName}, not ${module.packageName}")
        }
        if (info.minSdk > 0 && info.minSdk > Build.VERSION.SDK_INT) {
            throw IllegalStateException(
                "This APK needs Android API ${info.minSdk}, this device runs API ${Build.VERSION.SDK_INT}",
            )
        }
        if (info.abis.isNotEmpty() && info.abis.none { it in Build.SUPPORTED_ABIS }) {
            throw IllegalStateException(
                "This APK is built for ${info.abis.joinToString()}, which this device cannot run",
            )
        }
        val installedPackage = _installedVersions.value[module.packageName]
        if (installedPackage != null && info.versionCode < installedPackage.versionCode) {
            throw IllegalStateException("This release is older than the installed version")
        }
        return file
    }

    private suspend fun installDownloaded(
        context: Context,
        file: File,
        module: RepoModule,
        announce: Boolean,
    ): InstallResult {
        return when (settings.installer.value) {
            InstallerType.SYSTEM -> if (ApkInstaller.installSystem(context, file)) {
                InstallResult.Delegated
            } else {
                InstallResult.Failure("Could not open a system installer")
            }
            InstallerType.SESSION -> installSession(context, file, module.packageName)
            InstallerType.SHIZUKU -> {
                if (ShizukuInstaller.ensurePermission()) {
                    val problem = ShizukuInstaller.install(file)
                    if (problem == null) InstallResult.Success else InstallResult.Failure(problem)
                } else {
                    refreshShizukuStatus()
                    val reason = if (!ShizukuInstaller.isRunning()) {
                        "Shizuku is not running"
                    } else {
                        "Shizuku permission was not granted"
                    }
                    if (announce) message("$reason, using the session installer")
                    sessionFallback(context, file, module.packageName)
                }
            }
            InstallerType.ROOT -> {
                if (RootInstaller.isAvailable()) {
                    val problem = RootInstaller.install(file, context.packageName)
                    if (problem == null) InstallResult.Success else InstallResult.Failure(problem)
                } else {
                    if (announce) message("Root access is not granted, using the session installer")
                    sessionFallback(context, file, module.packageName)
                }
            }
        }
    }

    private suspend fun sessionFallback(
        context: Context,
        file: File,
        packageName: String,
    ): InstallResult =
        if (ApkInstaller.canInstall(context)) {
            installSession(context, file, packageName)
        } else {
            ApkInstaller.openInstallPermissionSettings(context)
            InstallResult.Failure("Allow unknown apps for Modules Manager, then try again")
        }

    private suspend fun installSession(
        context: Context,
        file: File,
        packageName: String,
    ): InstallResult {
        val result = withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
            val outcome = CompletableDeferred<Pair<Int, String?>>()
            launch {
                ApkInstaller.installSession(context, file, packageName) { status, reason ->
                    outcome.complete(status to reason)
                }
            }
            outcome.await()
        }
        if (result == null) {
            ApkInstaller.abandonPendingSession(context)
            return InstallResult.Failure("The install timed out")
        }
        val (status, reason) = result
        return if (status == PackageInstaller.STATUS_SUCCESS) {
            InstallResult.Success
        } else {
            InstallResult.Failure(installFailureMessage(status, reason))
        }
    }

    /** Restores a batch that was running when the process died. */
    private fun restoreBatch() {
        val stored = container.batchState.load() ?: return
        container.batchState.clear()
        val pendingPackages = container.pendingInstalls.all().map { it.packageName }.toSet()
        val items = stored.items.mapValues { (packageName, item) ->
            when {
                item.phase == BatchPhase.COMPLETED || item.phase == BatchPhase.FAILED -> item
                item.phase == BatchPhase.INSTALLING && packageName in pendingPackages -> item
                else -> item.copy(
                    phase = BatchPhase.FAILED,
                    error = "The app restarted before this finished",
                )
            }
        }
        val interrupted = items.values.any {
            it.phase == BatchPhase.FAILED || it.phase == BatchPhase.INSTALLING
        }
        if (!interrupted) return
        _batchUpdate.value = stored.copy(
            running = false,
            current = null,
            items = items,
            interrupted = true,
        )
    }

    fun dismissBatch() {
        batchPersisting = false
        _batchUpdate.value = null
        container.batchState.clear()
    }

    /** Downloads several updates in parallel, then installs them one at a time. */
    fun updateAll() {
        if (batchJob?.isActive == true || downloadJob?.isActive == true) return
        val targets = updates.value
        if (targets.isEmpty()) {
            message("No updates available")
            return
        }
        val appContext = container.appContext
        val installerType = settings.installer.value
        if ((installerType == InstallerType.SYSTEM || installerType == InstallerType.SESSION) &&
            !ApkInstaller.canInstall(appContext)
        ) {
            message("Allow \"Install unknown apps\" for Modules Manager, then try again")
            ApkInstaller.openInstallPermissionSettings(appContext)
            return
        }

        batchPersisting = true
        _batchUpdate.value = BatchUpdateState(
            running = true,
            total = targets.size,
            items = targets.associate {
                it.module.packageName to BatchItemState(it.module.displayName, BatchPhase.QUEUED)
            },
        )
        batchJob = viewModelScope.launch {
            val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
            val queue = Channel<PreparedInstall>(Channel.UNLIMITED)
            try {
                coroutineScope {
                    val slots = Semaphore(3)
                    val downloads = targets.map { update ->
                        launch {
                            val packageName = update.module.packageName
                            updateBatchItem(packageName) { it.copy(phase = BatchPhase.DOWNLOADING) }
                            slots.withPermit {
                                try {
                                    val detailed = container.repo.loadModule(
                                        DefaultSources.official,
                                        packageName,
                                    ) ?: update.module
                                    val includePreRelease = includePreReleases(detailed)
                                    val latest = detailed.latestFor(includePreRelease) ?: update.version
                                    val release = detailed.releaseForVersion(latest, includePreRelease)
                                    if (release == null || !release.matchesVersion(latest)) {
                                        throw IllegalStateException("Repository is serving an older release")
                                    }
                                    val asset = release.preferredAsset()
                                        ?: throw IllegalStateException("No APK asset")
                                    updateBatchItem(packageName) { it.copy(total = asset.size) }
                                    val file = downloadApk(
                                        appContext,
                                        update.module,
                                        asset,
                                        apkDestination(appContext, update.module, asset),
                                    ) { downloaded, total ->
                                        updateBatchItem(packageName) {
                                            it.copy(
                                                downloaded = downloaded,
                                                total = if (total > 0L) total else it.total,
                                            )
                                        }
                                    }
                                    updateBatchItem(packageName) {
                                        it.copy(
                                            phase = BatchPhase.WAITING_TO_INSTALL,
                                            downloaded = it.total,
                                        )
                                    }
                                    queue.send(PreparedInstall(update, file, asset))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    val reason = e.userMessage()
                                    failures += "${update.module.displayName}: $reason"
                                    updateBatchItem(packageName) {
                                        it.copy(phase = BatchPhase.FAILED, error = reason)
                                    }
                                }
                            }
                        }
                    }

                    val installer = launch {
                        for (prepared in queue) {
                            val update = prepared.update
                            val packageName = update.module.packageName
                            _batchUpdate.update {
                                it?.copy(current = update.module.displayName)
                            }
                            updateBatchItem(packageName) { it.copy(phase = BatchPhase.INSTALLING) }
                            val pending = markPending(update.module, prepared.asset, update.version)
                            val result = try {
                                installDownloaded(appContext, prepared.file, update.module, announce = false)
                            } catch (e: CancellationException) {
                                prepared.file.delete()
                                throw e
                            } catch (e: Exception) {
                                InstallResult.Failure(e.userMessage())
                            }
                            when (result) {
                                InstallResult.Success -> {
                                    container.pendingInstalls.remove(packageName)
                                    updateBatchItem(packageName) { it.copy(phase = BatchPhase.COMPLETED) }
                                    prepared.file.delete()
                                }
                                InstallResult.Delegated -> {
                                    if (awaitLanded(pending)) {
                                        container.pendingInstalls.remove(packageName)
                                        updateBatchItem(packageName) { it.copy(phase = BatchPhase.COMPLETED) }
                                    } else {
                                        container.pendingInstalls.remove(packageName)
                                        val reason = "The installer did not confirm the install"
                                        failures += "${update.module.displayName}: $reason"
                                        updateBatchItem(packageName) {
                                            it.copy(phase = BatchPhase.FAILED, error = reason)
                                        }
                                    }
                                }
                                is InstallResult.Failure -> {
                                    container.pendingInstalls.remove(packageName)
                                    failures += "${update.module.displayName}: ${result.message}"
                                    updateBatchItem(packageName) {
                                        it.copy(phase = BatchPhase.FAILED, error = result.message)
                                    }
                                    prepared.file.delete()
                                }
                            }
                        }
                    }

                    downloads.joinAll()
                    queue.close()
                    installer.join()
                }
                rescan()
                val failed = synchronized(failures) { failures.toList() }
                val summary = if (failed.isEmpty()) {
                    "Updated ${targets.size} modules"
                } else {
                    "Updated ${targets.size - failed.size} of ${targets.size}; failed: " +
                        failed.take(3).joinToString("; ")
                }
                message(summary)
            } catch (e: CancellationException) {
                message("Update all cancelled")
                throw e
            } finally {
                batchPersisting = false
                queue.cancel()
                _batchUpdate.update { it?.copy(running = false, current = null) }
                container.batchState.clear()
            }
        }
    }

    fun cancelBatch() {
        batchPersisting = false
        batchJob?.cancel()
        _batchUpdate.update { it?.copy(running = false, current = null) }
        container.batchState.clear()
    }

    private fun updateBatchItem(
        packageName: String,
        transform: (BatchItemState) -> BatchItemState,
    ) {
        _batchUpdate.update { state ->
            val item = state?.items?.get(packageName) ?: return@update state
            state.copy(items = state.items + (packageName to transform(item)))
        }
    }

    /** Resolves the newest APK for an installed module and starts installing it. */
    fun installLatest(module: RepoModule) {
        viewModelScope.launch {
            val detailed = loadDetailedModule(module.packageName) ?: module
            val includePreRelease = includePreReleases(detailed)
            val latest = detailed.latestFor(includePreRelease)
            val release = if (latest != null) {
                detailed.releaseForVersion(latest, includePreRelease)
            } else {
                detailed.releaseFor(channel.value)
            }
            if (latest != null && release != null && !release.matchesVersion(latest)) {
                message("The repository is still serving an older release")
                return@launch
            }
            installRelease(detailed, release)
        }
    }

    /** Installs the release, asking which APK when several fit the device equally well. */
    fun installRelease(module: RepoModule, release: RepoRelease?) {
        val options = release?.let { ApkVariants.bestChoices(it.apkAssets) }.orEmpty()
        when {
            options.size > 1 && release != null -> {
                _variantChoice.value = VariantChoice(module, release, options)
            }
            options.size == 1 -> install(module, options.first())
            else -> {
                val fallback = release?.preferredAsset()
                if (fallback != null) {
                    install(module, fallback)
                } else {
                    message("No APK is available for ${module.displayName}")
                }
            }
        }
    }

    fun installVariant(asset: RepoAsset) {
        val choice = _variantChoice.value ?: return
        _variantChoice.value = null
        install(choice.module, asset)
    }

    fun cancelVariantChoice() {
        _variantChoice.value = null
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        container.downloads.update(null)
    }

    fun cachedTranslation(module: RepoModule): String? = settings.cachedTranslation(
        translationKey(module, targetLanguage(), module.summary.orEmpty()),
    )

    fun translate(module: RepoModule) {
        val summary = module.summary?.trim().orEmpty()
        if (summary.isEmpty()) {
            message("This module has no description to translate")
            return
        }
        if (_translations.value[module.packageName]?.loading == true) return

        _translations.update { it + (module.packageName to TranslationState(loading = true)) }
        viewModelScope.launch { translateDescription(module, announce = true) }
    }

    fun setAutoTranslate(enabled: Boolean) {
        settings.setAutoTranslate(enabled)
    }

    fun setTranslateLanguage(language: String?) {
        settings.setTranslateLanguage(language)
        _translations.value = emptyMap()
        _readmeTranslation.value = null
        readmeTranslationJob?.cancel()
    }

    /** Translates descriptions that have no saved translation yet, two at a time. */
    private suspend fun autoTranslateDescriptions(modules: List<RepoModule>, language: String?) {
        val target = language ?: Locale.getDefault().language
        if (!TranslationLanguages.isSupported(target)) return

        val semaphore = Semaphore(2)
        coroutineScope {
            modules.mapNotNull { module ->
                val summary = module.summary?.trim().orEmpty()
                if (summary.isEmpty()) return@mapNotNull null
                if (TranslationLanguages.matches(_descriptionLanguages.value[module.packageName], target)) {
                    return@mapNotNull null
                }
                if (settings.cachedTranslation(translationKey(module, target, summary)) != null) {
                    return@mapNotNull null
                }
                if (_translations.value[module.packageName]?.loading == true) return@mapNotNull null
                async {
                    semaphore.withPermit {
                        if (!settings.autoTranslate.value) return@withPermit
                        _translations.update {
                            it + (module.packageName to TranslationState(loading = true))
                        }
                        translateDescription(module, announce = false)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun translateDescription(module: RepoModule, announce: Boolean) {
        val summary = module.summary?.trim().orEmpty()
        val target = targetLanguage()
        try {
            val result = TranslateApi.translateTo(summary, target)
            result.sourceLanguage?.let { source ->
                _descriptionLanguages.update { it + (module.packageName to source) }
            }
            if (TranslationLanguages.matches(result.sourceLanguage, target) ||
                result.text.trim() == summary
            ) {
                _translations.update { it - module.packageName }
                if (announce) message("This description is already in ${displayLanguage(target)}")
                return
            }
            settings.saveTranslation(translationKey(module, target, summary), result.text)
            _translations.update {
                it + (module.packageName to TranslationState(text = result.text))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _translations.update { it - module.packageName }
            if (announce) message(e.message ?: "Translation failed")
        }
    }

    /** Hides the translation so the original description shows again. */
    fun showOriginal(module: RepoModule) {
        // An empty state also masks a saved translation from earlier runs.
        _translations.update { it + (module.packageName to TranslationState()) }
    }

    private fun targetLanguage(): String =
        settings.translateLanguage.value ?: Locale.getDefault().language

    private fun displayLanguage(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).ifBlank { tag }

    private fun translationKey(module: RepoModule, target: String, text: String): String =
        "${module.packageName}:$target:${text.hashCode()}"

    fun openInstalledModule(packageName: String) {
        if (!container.scanner.openModule(packageName)) {
            message("This module has no settings screen")
        }
    }

    /** Hook-only modules have no screen to open, so show their system app info instead. */
    fun openAppInfo(packageName: String) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { container.appContext.startActivity(intent) }
            .onFailure { message("Could not open app info") }
    }

    /** Opens the system uninstaller for an installed module. */
    fun uninstall(packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { container.appContext.startActivity(intent) }
            .onFailure { message("Could not open the uninstaller") }
    }

    fun openLink(url: String?) {
        if (url.isNullOrBlank()) {
            message("No link available")
            return
        }
        runCatching {
            container.appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { message("No app can open this link") }
    }

    private fun installFailureMessage(status: Int, reason: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled"
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "The system blocked the install. Allow unknown apps for Modules Manager and try again"
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "The installed app conflicts with this APK, usually because the signing keys differ"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This APK is not compatible with this device"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install this APK"
        else -> reason?.takeIf { it.isNotBlank() }?.let { "Install failed: $it" } ?: "Install failed"
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "module.apk" }

    override fun onCleared() {
        runCatching {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceived)
            Shizuku.removeBinderDeadListener(shizukuBinderDead)
        }
        runCatching { container.appContext.unregisterReceiver(packageChangeReceiver) }
        super.onCleared()
    }

    companion object {
        private const val LIVE_UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val INSTALL_TIMEOUT_MS = 5 * 60 * 1000L
        private const val PENDING_INSTALL_TIMEOUT_MS = 30 * 60 * 1000L
        private const val DELEGATED_INSTALL_TIMEOUT_MS = 2 * 60 * 1000L
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(container) }
        }
    }
}
