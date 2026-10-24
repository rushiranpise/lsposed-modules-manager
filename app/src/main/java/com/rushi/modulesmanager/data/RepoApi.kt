package com.rushi.modulesmanager.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RepoApi(private val client: OkHttpClient) {

    data class FetchedModules(val rawJson: String, val modules: List<RepoModule>, val baseUrl: String)

    /**
     * The last endpoint that served this source successfully. It is tried first on the next
     * request, so a source that failed its primary endpoint does not pay for that failure again.
     */
    private val preferredBases = ConcurrentHashMap<String, String>()

    suspend fun fetchModules(source: RepoSource): FetchedModules = withContext(Dispatchers.IO) {
        fetchWithFallback(source) { base -> fetchModulesFromBase(source, base) }
    }

    suspend fun fetchModule(source: RepoSource, packageName: String): RepoModule = withContext(Dispatchers.IO) {
        fetchWithFallback(source) { base ->
            RepoJson.parseModule(fetch(base.trimEnd('/') + "/module/$packageName.json"))
        }
    }

    private suspend fun fetchModulesFromBase(source: RepoSource, base: String): FetchedModules {
        return when (source.format) {
            RepoFormat.REPOS_JSON -> {
                val raw = fetch(base)
                FetchedModules(raw, RepoJson.parseRepos(raw), base)
            }
            RepoFormat.MODERN -> fetchModern(base)
            RepoFormat.AUTO -> try {
                fetchModern(base)
            } catch (e: CancellationException) {
                throw e
            } catch (modernError: Exception) {
                try {
                    fetchLegacy(base)
                } catch (e: CancellationException) {
                    throw e
                } catch (legacyError: Exception) {
                    // A missing new endpoint usually means this is an older repository document.
                    if (modernError.message?.startsWith("HTTP 40") == true) throw legacyError
                    throw modernError
                }
            }
        }
    }

    /**
     * The current site exposes 30 modules per page. Page 1 carries the page count, so the rest
     * are fetched in parallel and merged into one document that the cache and UI already expect.
     */
    private suspend fun fetchModern(base: String): FetchedModules = coroutineScope {
        val normalized = base.trimEnd('/')
        val firstRaw = withContext(Dispatchers.IO) { fetch("$normalized/module-list/1.json") }
        val first = JSONObject(firstRaw)
        val firstPage = first.optJSONArray("modules")
            ?: throw IOException("Unexpected module-list response")

        val pageSize = firstPage.length().coerceAtLeast(1)
        val total = first.optInt("total", 0)
        val declaredPages = first.optInt("pageCount", 0)
        val pageCount = (
            if (declaredPages > 0) {
                declaredPages
            } else if (total > 0) {
                (total + pageSize - 1) / pageSize
            } else {
                1
            }
            ).coerceIn(1, MAX_PAGES)

        val pages = arrayOfNulls<JSONArray>(pageCount)
        pages[0] = firstPage

        if (pageCount > 1) {
            val semaphore = Semaphore(PAGE_CONCURRENCY)
            val loaded = (2..pageCount).map { page ->
                async(Dispatchers.IO) {
                    page to semaphore.withPermit { fetchPage(normalized, page) }
                }
            }.awaitAll()
            for ((page, modules) in loaded) {
                pages[page - 1] = modules
            }
        }

        // Pages are generated while users publish modules, so a module can shift between pages
        // mid-fetch. Deduplicating by package name keeps the list stable.
        val merged = LinkedHashMap<String, JSONObject>()
        for (page in pages) {
            if (page == null) continue
            for (index in 0 until page.length()) {
                val module = page.optJSONObject(index) ?: continue
                val name = module.optString("name")
                if (name.isNotBlank()) merged[name] = module
            }
        }

        val array = JSONArray()
        for (module in merged.values) array.put(module)
        FetchedModules(array.toString(), RepoJson.parseModules(array), base)
    }

    private fun fetchPage(base: String, page: Int): JSONArray {
        var last: Exception? = null
        for (attempt in 0 until PAGE_RETRIES) {
            try {
                val raw = fetch("$base/module-list/$page.json")
                return JSONObject(raw).optJSONArray("modules")
                    ?: throw IOException("Page $page has no modules")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Could not load module-list page $page")
    }

    /** For self-hosted or older repositories that still expose one complete modules.json. */
    private fun fetchLegacy(base: String): FetchedModules {
        val raw = fetch(base.trimEnd('/') + "/modules.json")
        return FetchedModules(raw, RepoJson.parseModules(raw), base)
    }

    private suspend fun <T> fetchWithFallback(source: RepoSource, block: suspend (String) -> T): T {
        val failures = ArrayList<Throwable>(source.bases.size)
        for (base in orderedBases(source)) {
            try {
                val result = block(base)
                preferredBases[source.id] = base
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += e
            }
        }
        // Nothing worked. Start from the primary endpoint again next time in case it recovered.
        preferredBases.remove(source.id)
        throw RepositoryUnavailableException(source.name, failures)
    }

    private fun orderedBases(source: RepoSource): List<String> {
        val preferred = preferredBases[source.id] ?: return source.bases
        return buildList(source.bases.size) {
            add(preferred)
            source.bases.filterTo(this) { it != preferred }
        }
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string()
            if (body.isNullOrBlank()) throw IOException("Empty response")
            return body
        }
    }

    /**
     * Streams one of [urls] into [destination], reporting bytes written. The signed
     * assets.lsposed.org URL is tried first, then the original GitHub release URL. The file only
     * appears at its final path once the download completed.
     */
    suspend fun download(
        urls: List<String>,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (url in urls.filter { it.isNotBlank() }.distinct()) {
            try {
                return@withContext downloadOnce(url, destination, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the .part file: the next URL or a retry can continue from it.
                last = e
            }
        }
        throw last ?: IOException("No download URL available")
    }

    /**
     * Downloads one URL, continuing a previous `.part` file with HTTP Range when possible.
     * A server that ignores the range (200) restarts the file, and a 416 retries once from zero.
     */
    private fun downloadOnce(
        url: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        val resumed = fetchInto(url, partial, resume = true, onProgress)
        if (resumed == null) {
            // 416: the leftover bytes are not usable, start clean once.
            partial.delete()
            fetchInto(url, partial, resume = false, onProgress)
        }

        if (destination.exists()) destination.delete()
        if (!partial.renameTo(destination)) {
            partial.copyTo(destination, overwrite = true)
            partial.delete()
        }
        return destination
    }

    /** Returns null when the server answered 416 to a range request. */
    private fun fetchInto(
        url: String,
        partial: File,
        resume: Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Unit? {
        val existing = if (resume && partial.isFile) partial.length() else 0L
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { if (existing > 0L) header("Range", "bytes=$existing-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 416 && existing > 0L) return null
            if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("Download failed: empty response")

            val appending = response.code == 206 && existing > 0L
            val contentRange = response.header("Content-Range")
            val start = contentRangeStart(contentRange)
            if (appending && start != null && start != existing) return null

            val contentLength = body.contentLength()
            val total = when {
                !appending -> contentLength
                else -> contentRangeTotal(contentRange)
                    ?: if (contentLength >= 0L) existing + contentLength else -1L
            }
            var downloaded = if (appending) existing else 0L
            onProgress(downloaded, total)

            java.io.RandomAccessFile(partial, "rw").use { output ->
                if (!appending) output.setLength(0)
                output.seek(downloaded)
                var lastReport = downloaded
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReport >= 128 * 1024 ||
                            (total > 0 && downloaded == total)
                        ) {
                            lastReport = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            onProgress(downloaded, if (total > 0) total else downloaded)
        }
        return Unit
    }

    private fun contentRangeStart(header: String?): Long? {
        val raw = header?.trim()?.removePrefix("bytes")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return raw.substringBefore('/').trim().substringBefore('-').trim().toLongOrNull()
    }

    private fun contentRangeTotal(header: String?): Long? {
        val raw = header?.trim()?.removePrefix("bytes")?.trim().orEmpty()
        return raw.substringAfter('/', "").trim().toLongOrNull()
    }

    companion object {
        const val USER_AGENT = "ModulesManager/1.0 (Android)"

        private const val PAGE_CONCURRENCY = 4
        private const val PAGE_RETRIES = 2
        private const val MAX_PAGES = 200

        fun createClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }
}
