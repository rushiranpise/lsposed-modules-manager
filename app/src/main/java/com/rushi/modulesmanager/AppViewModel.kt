package com.rushi.modulesmanager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rushi.modulesmanager.data.ApkInspector
import com.rushi.modulesmanager.data.ApkInstaller
import com.rushi.modulesmanager.data.AppTheme
import com.rushi.modulesmanager.data.DefaultSources
import com.rushi.modulesmanager.data.DownloadCenter
import com.rushi.modulesmanager.data.InstalledModule
import com.rushi.modulesmanager.data.InstalledPackageVersion
import com.rushi.modulesmanager.data.InstallerType
import com.rushi.modulesmanager.data.LiveUpdateRateLimitException
import com.rushi.modulesmanager.data.ModuleVersion
import com.rushi.modulesmanager.data.ReadmeTranslator
import com.rushi.modulesmanager.data.RepoAsset
import com.rushi.modulesmanager.data.RepoModule
import com.rushi.modulesmanager.data.ShizukuInstaller
import com.rushi.modulesmanager.data.SortMode
import com.rushi.modulesmanager.data.TranslateApi
import com.rushi.modulesmanager.data.UpdateChannel
import com.rushi.modulesmanager.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

data class TranslationState(val text: String? = null, val loading: Boolean = false)

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

    private val _detail = MutableStateFlow<RepoModule?>(null)
    val detail: StateFlow<RepoModule?> = _detail.asStateFlow()

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    private val _detailError = MutableStateFlow<String?>(null)
    val detailError: StateFlow<String?> = _detailError.asStateFlow()

    private val _readmeTranslation = MutableStateFlow<ReadmeTranslationState?>(null)
    val readmeTranslation: StateFlow<ReadmeTranslationState?> = _readmeTranslation.asStateFlow()

    private var detailJob: Job? = null
    private var readmeTranslationJob: Job? = null
    private var downloadJob: Job? = null
    private var shizukuPermissionJob: Job? = null
    private var installedRebuildJob: Job? = null
    private var liveCheckJob: Job? = null
    private var liveCheckAttempted = false
    private var discoveryJob: Job? = null
    private val discoveryAttempted = mutableSetOf<String>()
    private var detectedModules: List<InstalledModule> = emptyList()
    private var installedVersionMap: Map<String, InstalledPackageVersion> = emptyMap()

    val updates: StateFlow<List<ModuleUpdate>> = combine(modules, repoInstalled, channel) { repoModules, installedModules, selectedChannel ->
        val byPackage = repoModules.associateBy { it.packageName }
        installedModules.mapNotNull { installedModule ->
            val module = byPackage[installedModule.packageName] ?: return@mapNotNull null
            val latest = module.latest(selectedChannel) ?: return@mapNotNull null
            if (latest.isNewerThan(installedModule.versionCode, installedModule.versionName)) {
                ModuleUpdate(installedModule, module, latest)
            } else {
                null
            }
        }.sortedBy { it.module.displayName.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived)
            Shizuku.addBinderDeadListener(shizukuBinderDead)
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
    }

    fun rescan(showMessage: Boolean = false) {
        viewModelScope.launch {
            val scanned = withContext(Dispatchers.IO) { container.scanner.scan() }
            detectedModules = scanned.modules
            installedVersionMap = scanned.versions
            _installedVersions.value = scanned.versions
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
        val repoPackages = modules.value.map { it.packageName }
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
        _detail.value = container.repo.cachedModule(packageName)
        _detailError.value = null
        _detailLoading.value = true
        detailJob = viewModelScope.launch {
            val loaded = container.repo.loadModule(DefaultSources.official, packageName)
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
    fun translateReadme() {
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
                    message("This README has no Markdown text that can be translated")
                    return@launch
                }

                val result = ReadmeTranslator.translate(markdown, target) { progress ->
                    _readmeTranslation.update { it?.copy(progress = progress) }
                }
                if (!result.translated) {
                    _readmeTranslation.value = null
                    message("This README is already in ${displayLanguage(target)}")
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
                message(e.message ?: "Could not translate this README")
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

    fun install(module: RepoModule, asset: RepoAsset) {
        if (downloads.value != null || downloadJob?.isActive == true) {
            message("Wait for the current download to finish")
            return
        }
        val appContext = container.appContext
        val installerType = settings.installer.value
        if (installerType != InstallerType.SHIZUKU && !ApkInstaller.canInstall(appContext)) {
            message("Allow \"Install unknown apps\" for Modules Manager, then try again")
            ApkInstaller.openInstallPermissionSettings(appContext)
            return
        }

        val destination = File(appContext.cacheDir, "apk")
            .apply { mkdirs() }
            .resolve(asset.name.sanitizeFileName())

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
                val file = container.api.download(
                    urls = listOfNotNull(asset.downloadUrl, asset.originalDownloadUrl),
                    destination = destination,
                ) { done, total ->
                    container.downloads.progress(done, total)
                }
                container.downloads.phase(DownloadCenter.Phase.VERIFYING)

                val info = withContext(Dispatchers.IO) { ApkInspector.inspect(appContext, file) }
                    ?: throw IllegalStateException("The downloaded file is not a valid APK")
                if (info.packageName != module.packageName) {
                    throw IllegalStateException("This release contains ${info.packageName}, not ${module.packageName}")
                }
                val installedPackage = _installedVersions.value[module.packageName]
                if (installedPackage != null) {
                    if (info.versionCode < installedPackage.versionCode) {
                        throw IllegalStateException("This release is older than the installed version")
                    }
                    val installedSignature = withContext(Dispatchers.IO) {
                        ApkInspector.installedSignatureDigest(appContext, module.packageName)
                    }
                    if (installedSignature != null && info.signatureDigest != null &&
                        installedSignature != info.signatureDigest
                    ) {
                        throw IllegalStateException(
                            "This APK is signed with a different key, so Android cannot update the installed app",
                        )
                    }
                }

                container.downloads.phase(DownloadCenter.Phase.INSTALLING)
                when (installerType) {
                    InstallerType.SYSTEM -> {
                        val launched = ApkInstaller.installSystem(appContext, file)
                        container.downloads.update(null)
                        if (launched) {
                            message("Opening the system installer")
                        } else {
                            message("Could not open a system installer")
                            file.delete()
                        }
                    }
                    InstallerType.SESSION -> runSessionInstall(appContext, file, module)
                    InstallerType.SHIZUKU -> {
                        if (ShizukuInstaller.ensurePermission()) {
                            val problem = ShizukuInstaller.install(file)
                            container.downloads.update(null)
                            if (problem == null) {
                                message("${module.displayName} installed")
                                rescan()
                            } else {
                                message(problem)
                            }
                            file.delete()
                        } else {
                            refreshShizukuStatus()
                            val reason = if (!ShizukuInstaller.isRunning()) {
                                "Shizuku is not running"
                            } else {
                                "Shizuku permission was not granted"
                            }
                            message("$reason, using the session installer")
                            if (ApkInstaller.canInstall(appContext)) {
                                runSessionInstall(appContext, file, module)
                            } else {
                                container.downloads.update(null)
                                file.delete()
                                message("Allow unknown apps for Modules Manager, then try again")
                                ApkInstaller.openInstallPermissionSettings(appContext)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                container.downloads.update(null)
                destination.delete()
                File(destination.path + ".part").delete()
                throw e
            } catch (e: Exception) {
                container.downloads.update(null)
                destination.delete()
                File(destination.path + ".part").delete()
                message(e.userMessage())
            }
        }
    }

    /** Resolves the newest APK for an installed module and starts installing it. */
    fun installLatest(module: RepoModule) {
        viewModelScope.launch {
            val detailed = container.repo.loadModule(DefaultSources.official, module.packageName) ?: module
            val asset = detailed.releaseFor(channel.value)?.apkAssets?.firstOrNull()
            if (asset == null) {
                message("No APK is available for ${module.displayName}")
                return@launch
            }
            install(detailed, asset)
        }
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

        val target = targetLanguage()
        _translations.update { it + (module.packageName to TranslationState(loading = true)) }
        viewModelScope.launch {
            try {
                val result = TranslateApi.translateTo(summary, target)
                if (result.text == summary) {
                    _translations.update { it - module.packageName }
                    message("This description is already in ${displayLanguage(target)}")
                    return@launch
                }
                settings.saveTranslation(translationKey(module, target, summary), result.text)
                _translations.update {
                    it + (module.packageName to TranslationState(text = result.text))
                }
            } catch (e: Exception) {
                _translations.update { it - module.packageName }
                message(e.message ?: "Translation failed")
            }
        }
    }

    /** Hides the translation so the original description shows again. */
    fun showOriginal(module: RepoModule) {
        // An empty state also masks a saved translation from earlier runs.
        _translations.update { it + (module.packageName to TranslationState()) }
    }

    private fun targetLanguage(): String = Locale.getDefault().language

    private fun displayLanguage(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).ifBlank { tag }

    private fun translationKey(module: RepoModule, target: String, text: String): String =
        "${module.packageName}:$target:${text.hashCode()}"

    fun openInstalledModule(packageName: String) {
        if (!container.scanner.openModule(packageName)) {
            message("This module has no settings screen")
        }
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

    private fun runSessionInstall(appContext: Context, file: File, module: RepoModule) {
        ApkInstaller.installSession(appContext, file) { status, reason ->
            if (status == PackageInstaller.STATUS_SUCCESS) {
                message("${module.displayName} installed")
                rescan()
            } else {
                message(installFailureMessage(status, reason))
            }
            container.downloads.update(null)
            file.delete()
        }
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
        super.onCleared()
    }

    companion object {
        private const val LIVE_UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(container) }
        }
    }
}
