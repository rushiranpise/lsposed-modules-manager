package com.rushi.modulesmanager.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class RepoRepository(
    private val context: Context,
    private val api: RepoApi,
) {
    private val _modules = MutableStateFlow<List<RepoModule>>(emptyList())
    val modules: StateFlow<List<RepoModule>> = _modules.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastUpdated = MutableStateFlow<Long?>(null)
    val lastUpdated: StateFlow<Long?> = _lastUpdated.asStateFlow()

    /** When the index file itself last changed, not when this app fetched it. */
    private val _updatedAt = MutableStateFlow<Long?>(null)
    val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()

    private val details = ConcurrentHashMap<String, RepoModule>()
    private var activeSourceId: String? = null
    private var packagesWithoutReleases: Set<String> = emptySet()

    suspend fun load(source: RepoSource) {
        val switching = activeSourceId != source.id
        if (switching) {
            activeSourceId = source.id
            details.clear()
            val cached = readCache(source)
            _modules.value = cached?.modules.orEmpty()
            _lastUpdated.value = cached?.savedAt
            _updatedAt.value = readUpdatedAt(source)
            _error.value = null
        }

        _loading.value = _modules.value.isEmpty()
        _refreshing.value = true
        try {
            val fetched = api.fetchModules(source)
            coroutineContext.ensureActive()
            if (activeSourceId != source.id) return
            _modules.value = fetched.modules.filterNot { it.hide }
            if (source.format == RepoFormat.REPOS_JSON) {
                packagesWithoutReleases = RepoJson.packagesWithoutReleases(fetched.rawJson)
            }
            _lastUpdated.value = System.currentTimeMillis()
            _error.value = null
            withContext(Dispatchers.IO) { writeCache(source, fetched.rawJson) }
            if (source.updatedFeed != null) {
                api.fetchUpdatedAt(source.updatedFeed)?.let { timestamp ->
                    _updatedAt.value = timestamp
                    withContext(Dispatchers.IO) { writeUpdatedAt(source, timestamp) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (activeSourceId != source.id) return
            if (_modules.value.isEmpty()) {
                val cached = readCache(source)
                if (cached != null) {
                    _modules.value = cached.modules
                    _lastUpdated.value = cached.savedAt
                    _error.value = "Showing cached data. ${e.userMessage()}"
                } else {
                    _error.value = e.userMessage()
                }
            } else {
                _error.value = e.userMessage()
            }
        } finally {
            _loading.value = false
            _refreshing.value = false
        }
    }

    /** Loads the full module document (readme, all releases) and merges it into the list. */
    suspend fun loadModule(source: RepoSource, packageName: String): RepoModule? {
        // The daily index already contains the module and its latest release.
        if (source.format == RepoFormat.REPOS_JSON) return cachedModule(packageName)
        details[packageName]?.let { return it }
        return try {
            val module = api.fetchModule(source, packageName)
            coroutineContext.ensureActive()
            details[packageName] = module
            _modules.value = _modules.value.map { if (it.packageName == packageName) module else it }
            module
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun missingReleasePackages(): Set<String> = packagesWithoutReleases

    /** Adds an installed module that the daily index knows about but has no release for. */
    fun addDiscoveredModule(module: RepoModule) {
        if (_modules.value.any { it.packageName == module.packageName }) return
        _modules.value = _modules.value + module
    }

    /** Replaces the daily release with a newer release fetched live from GitHub. */
    fun applyLiveRelease(packageName: String, tag: String, release: RepoRelease) {
        fun RepoModule.merged(): RepoModule = copy(
            latestRelease = tag,
            latestReleaseTime = release.publishedAt,
            releases = (listOf(release) + releases).distinctBy { it.url ?: it.tagName ?: it.name },
        )
        _modules.value = _modules.value.map { module ->
            if (module.packageName == packageName) module.merged() else module
        }
        details[packageName]?.let { details[packageName] = it.merged() }
    }

    /**
     * Replaces the release lists with the full history from GitHub. The daily index keeps only
     * the newest release per channel, and the live check only asks for the latest one.
     */
    fun applyReleaseHistory(packageName: String, all: List<RepoRelease>) {
        if (all.isEmpty()) return
        // Kept apart from the channel lists: the index owns the newest version per channel and
        // the install button, so a stale source repo cannot change what gets installed.
        _modules.value = _modules.value.map { module ->
            if (module.packageName == packageName) module.copy(releaseHistory = all) else module
        }
        details[packageName]?.let { details[packageName] = it.copy(releaseHistory = all) }
    }

    /** Keeps a README fetched from GitHub so reopening a module does not fetch it again. */
    fun applyReadme(packageName: String, html: String, markdown: String? = null) {
        _modules.value = _modules.value.map { module ->
            if (module.packageName == packageName) module.copy(readmeHtml = html) else module
        }
        details[packageName]?.let { details[packageName] = it.copy(readmeHtml = html) }
        runCatching { readmeFile(packageName, "html").writeText(html) }
        if (!markdown.isNullOrBlank()) {
            runCatching { readmeFile(packageName, "md").writeText(markdown) }
        }
    }

    /** Returns the last README fetched for this package, surviving app restarts. */
    fun cachedReadme(packageName: String): String? = cachedReadmeText(packageName, "html")

    /** The raw Markdown behind the cached README, needed to translate it. */
    fun cachedReadmeMarkdown(packageName: String): String? = cachedReadmeText(packageName, "md")

    /**
     * A translated README is stored as rendered HTML, ready to show again. The version suffix
     * invalidates caches written before raw HTML blocks were translated.
     */
    fun cachedReadmeTranslation(packageName: String, language: String): String? =
        cachedReadmeText(packageName, "${language.sanitizeLanguage()}.v2.html")

    fun saveReadmeTranslation(packageName: String, language: String, html: String) {
        runCatching {
            readmeFile(packageName, "${language.sanitizeLanguage()}.v2.html").writeText(html)
        }
    }

    private fun cachedReadmeText(packageName: String, extension: String): String? {
        val file = readmeFile(packageName, extension)
        if (!file.isFile) return null
        return runCatching { file.readText().takeIf { it.isNotBlank() } }.getOrNull()
    }

    private fun String.sanitizeLanguage(): String =
        lowercase().filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "und" }

    private fun readmeFile(packageName: String, extension: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(packageName.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
        return File(context.filesDir, "readme-cache").apply { mkdirs() }.resolve("$digest.$extension")
    }

    fun cachedModule(packageName: String): RepoModule? =
        details[packageName] ?: _modules.value.firstOrNull { it.packageName == packageName }

    fun clearError() {
        _error.value = null
    }

    private fun cacheFile(source: RepoSource): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.id.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
        return File(context.filesDir, "repo-cache").apply { mkdirs() }.resolve("$digest.json")
    }

    private fun updatedFile(source: RepoSource): File {
        val base = cacheFile(source)
        return File(base.parentFile, "${base.name}.updated")
    }

    private fun readUpdatedAt(source: RepoSource): Long? =
        runCatching { updatedFile(source).readText().toLongOrNull() }.getOrNull()

    private fun writeUpdatedAt(source: RepoSource, timestamp: Long) {
        runCatching { updatedFile(source).writeText(timestamp.toString()) }
    }

    private fun readCache(source: RepoSource): CachedRepo? {
        val file = cacheFile(source)
        if (!file.isFile) return null
        return runCatching {
            val raw = file.readText()
            val modules = if (source.format == RepoFormat.REPOS_JSON) {
                packagesWithoutReleases = RepoJson.packagesWithoutReleases(raw)
                RepoJson.parseRepos(raw)
            } else {
                RepoJson.parseModules(raw)
            }
            CachedRepo(modules.filterNot { it.hide }, file.lastModified())
        }.getOrNull()
    }

    private fun writeCache(source: RepoSource, rawJson: String) {
        runCatching { cacheFile(source).writeText(rawJson) }
    }

    private data class CachedRepo(val modules: List<RepoModule>, val savedAt: Long)
}
