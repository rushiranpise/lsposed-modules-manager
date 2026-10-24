package com.rushi.modulesmanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Checks the latest GitHub release for a repository. The daily repos.json index is fetched once
 * a day, so installed modules are checked here to catch releases published after that snapshot.
 */
class LiveReleaseApi(
    private val client: OkHttpClient,
    private val apiBase: String = "https://api.github.com",
    private val webBase: String = "https://github.com",
) {

    data class RepoStats(val stars: Int, val forks: Int)

    data class LiveRelease(
        val tag: String,
        val name: String?,
        val publishedAt: String?,
        val url: String?,
        val isPrerelease: Boolean,
        val description: String?,
        val assets: List<RepoAsset>,
    ) {
        fun toRepoRelease(): RepoRelease = RepoRelease(
            name = name,
            tagName = tag,
            url = url,
            descriptionHtml = description,
            publishedAt = publishedAt,
            isPrerelease = isPrerelease,
            assets = assets,
        )
    }

    /** ETag per repository, so unchanged releases return 304 and do not consume the rate limit. */
    private val etags = ConcurrentHashMap<String, String>()

    /** GitHub stars and forks for a repository, with an HTML fallback when the API is limited. */
    suspend fun fetchStats(repoFullName: String): RepoStats? = withContext(Dispatchers.IO) {
        apiStats(repoFullName) ?: htmlStats(repoFullName)
    }

    private fun apiStats(repoFullName: String): RepoStats? = runCatching {
        val request = Request.Builder()
            .url("${apiBase.trimEnd('/')}/repos/$repoFullName")
            .header("User-Agent", RepoApi.USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            val obj = JSONObject(body)
            RepoStats(
                stars = obj.optInt("stargazers_count", 0),
                forks = obj.optInt("forks_count", 0),
            )
        }
    }.getOrNull()

    private fun htmlStats(repoFullName: String): RepoStats? = runCatching {
        val request = Request.Builder()
            .url("${webBase.trimEnd('/')}/$repoFullName")
            .header("User-Agent", RepoApi.USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val html = response.body?.string() ?: return@use null
            RepoStats(
                stars = htmlCount(html, "repo-stars-counter-star") ?: return@use null,
                forks = htmlCount(html, "repo-network-counter") ?: return@use null,
            )
        }
    }.getOrNull()

    private fun htmlCount(html: String, id: String): Int? {
        val tag = Regex("""<[^>]*id="$id"[^>]*>""").find(html)?.value ?: return null
        val title = Regex("""title="([\d,]+)""").find(tag)?.groupValues?.getOrNull(1) ?: return null
        return title.replace(",", "").toIntOrNull()
    }

    suspend fun fetchLatest(repoFullName: String): LiveRelease? = try {
        fetchLatestFromApi(repoFullName)
    } catch (e: LiveUpdateRateLimitException) {
        fetchLatestFromAtom(repoFullName) ?: throw e
    }

    private suspend fun fetchLatestFromApi(repoFullName: String): LiveRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${apiBase.trimEnd('/')}/repos/$repoFullName/releases/latest")
            .header("User-Agent", RepoApi.USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .apply {
                etags[repoFullName]?.let { header("If-None-Match", it) }
            }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> return@withContext null
                response.code == 404 -> return@withContext null
                response.code == 403 || response.code == 429 -> throw LiveUpdateRateLimitException()
                !response.isSuccessful -> throw IOException("HTTP ${response.code}")
            }
            response.header("ETag")?.let { etags[repoFullName] = it }

            val body = response.body?.string() ?: return@withContext null
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name").takeIf { it.isNotBlank() } ?: return@withContext null
            val assets = parseAssets(obj)
            if (assets.isEmpty()) return@withContext null

            LiveRelease(
                tag = tag,
                name = obj.optString("name").takeIf { it.isNotBlank() && it != "null" },
                publishedAt = obj.optString("published_at").takeIf { it.isNotBlank() && it != "null" },
                url = obj.optString("html_url").takeIf { it.isNotBlank() && it != "null" },
                isPrerelease = obj.optBoolean("prerelease", false),
                description = obj.optString("body").takeIf { it.isNotBlank() && it != "null" },
                assets = assets,
            )
        }
    }

    /**
     * Every published release, newest first, including pre-releases. The daily index only keeps
     * the newest release per channel, so the detail screen needs this to list release history.
     * Returns null when the ETag matched and the caller should keep what it already shows.
     */
    suspend fun fetchAll(
        repoFullName: String,
        useCache: Boolean = true,
    ): List<LiveRelease>? = try {
        fetchAllFromApi(repoFullName, useCache)
    } catch (_: LiveUpdateRateLimitException) {
        fetchAllFromAtom(repoFullName)
    }

    private suspend fun fetchAllFromApi(
        repoFullName: String,
        useCache: Boolean,
    ): List<LiveRelease>? = withContext(Dispatchers.IO) {
        val cacheKey = "all:$repoFullName"
        val request = Request.Builder()
            .url("${apiBase.trimEnd('/')}/repos/$repoFullName/releases?per_page=100")
            .header("User-Agent", RepoApi.USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .apply {
                if (useCache) etags[cacheKey]?.let { header("If-None-Match", it) }
            }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> return@withContext null
                response.code == 404 -> return@withContext emptyList()
                response.code == 403 || response.code == 429 -> throw LiveUpdateRateLimitException()
                !response.isSuccessful -> throw IOException("HTTP ${response.code}")
            }
            response.header("ETag")?.let { etags[cacheKey] = it }

            val body = response.body?.string() ?: return@withContext emptyList()
            val array = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    if (obj.optBoolean("draft", false)) continue
                    val tag = obj.optString("tag_name").takeIf { it.isNotBlank() } ?: continue
                    add(
                        LiveRelease(
                            tag = tag,
                            name = obj.optString("name").takeIf { it.isNotBlank() && it != "null" },
                            publishedAt = obj.optString("published_at")
                                .takeIf { it.isNotBlank() && it != "null" },
                            url = obj.optString("html_url")
                                .takeIf { it.isNotBlank() && it != "null" },
                            isPrerelease = obj.optBoolean("prerelease", false),
                            description = obj.optString("body")
                                .takeIf { it.isNotBlank() && it != "null" },
                            assets = parseAssets(obj),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Public Atom feed fallback. It lists recent releases and notes without the API quota; the
     * expanded-assets fragment supplies the APK links for each tag.
     */
    private suspend fun fetchAllFromAtom(repoFullName: String): List<LiveRelease>? =
        withContext(Dispatchers.IO) {
            val releases = fetchAtomFeed(repoFullName) ?: return@withContext null
            releases.map { release ->
                release.copy(assets = fetchAtomAssets(repoFullName, release.tag))
            }
        }

    private suspend fun fetchLatestFromAtom(repoFullName: String): LiveRelease? =
        withContext(Dispatchers.IO) {
            val releases = fetchAtomFeed(repoFullName) ?: return@withContext null
            val latest = releases.firstOrNull { !it.isPrerelease } ?: return@withContext null
            val assets = fetchAtomAssets(repoFullName, latest.tag)
            if (assets.isEmpty()) return@withContext null
            latest.copy(assets = assets)
        }

    private suspend fun fetchAtomFeed(repoFullName: String): List<LiveRelease>? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${webBase.trimEnd('/')}/$repoFullName/releases.atom")
                .header("User-Agent", RepoApi.USER_AGENT)
                .header("Accept", "application/atom+xml")
                .build()
            val xml = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()
                }
            }.getOrNull() ?: return@withContext null
            parseAtomEntries(xml).takeIf { it.isNotEmpty() }
        }

    private fun parseAtomEntries(xml: String): List<LiveRelease> =
        ENTRY.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val title = TITLE.find(block)?.groupValues?.getOrNull(1)
                ?.let(::unescapeXml)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val url = alternateHref(block)
            // The entry title is the release name; the tag is the last segment of its page URL.
            val tag = url?.substringAfter("/releases/tag/", "")
                ?.substringBefore('?')
                ?.takeIf { it.isNotBlank() }
                ?: title
            LiveRelease(
                tag = tag,
                name = title.takeIf { it != tag },
                publishedAt = UPDATED.find(block)?.groupValues?.getOrNull(1)?.trim(),
                url = url,
                isPrerelease = PRE_RELEASE.containsMatchIn("$tag $title"),
                description = CONTENT.find(block)?.groupValues?.getOrNull(1)
                    ?.let(::unescapeXml)
                    ?.takeIf { it.isNotBlank() },
                assets = emptyList(),
            )
        }.take(MAX_ATOM_RELEASES).toList()

    private fun alternateHref(entry: String): String? {
        val link = LINK.findAll(entry)
            .firstOrNull { it.value.contains("rel=\"alternate\"") }
            ?.value ?: return null
        return HREF.find(link)?.groupValues?.getOrNull(1)?.let(::unescapeXml)
    }

    private fun fetchAtomAssets(repoFullName: String, tag: String): List<RepoAsset> {
        val encodedTag = URLEncoder.encode(tag, "UTF-8").replace("+", "%20")
        val url = "${webBase.trimEnd('/')}/$repoFullName/releases/expanded_assets/$encodedTag"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", RepoApi.USER_AGENT)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val html = response.body?.string().orEmpty()
                APK_LINK.findAll(html).mapNotNull { match ->
                    val href = match.groupValues[1]
                    val absolute = when {
                        href.startsWith("http") -> href
                        href.startsWith("//") -> "https:$href"
                        else -> "${webBase.trimEnd('/')}$href"
                    }
                    RepoAsset(
                        name = absolute.substringAfterLast('/').substringBefore('?'),
                        contentType = "application/vnd.android.package-archive",
                        downloadUrl = absolute,
                        downloadCount = 0,
                        size = 0L,
                    )
                }.distinctBy { it.downloadUrl }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private companion object {
        const val MAX_ATOM_RELEASES = 10
        val ENTRY = Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)
        val TITLE = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
        val UPDATED = Regex("""<updated>(.*?)</updated>""", RegexOption.DOT_MATCHES_ALL)
        val CONTENT = Regex("""<content[^>]*>(.*?)</content>""", RegexOption.DOT_MATCHES_ALL)
        val LINK = Regex("""<link\b[^>]*>""")
        val HREF = Regex("""href="([^"]+)"""")
        val APK_LINK = Regex("""href="([^"]+\.apk)""", RegexOption.IGNORE_CASE)
        val PRE_RELEASE = Regex(
            """(?i)(^|[-_. ])(alpha|beta|rc\d*|canary|snapshot|nightly|preview)([-_. ]|$)""",
        )
    }

    private fun parseAssets(json: JSONObject): List<RepoAsset> {
        val array = json.optJSONArray("assets") ?: return emptyList()
        val result = ArrayList<RepoAsset>()
        for (index in 0 until array.length()) {
            val asset = array.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val downloadUrl = asset.optString("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true) || downloadUrl.isBlank()) continue
            result += RepoAsset(
                name = name,
                contentType = asset.optString("content_type").takeIf { it.isNotBlank() },
                downloadUrl = downloadUrl,
                downloadCount = asset.optInt("download_count", 0),
                size = asset.optLong("size", 0L),
            )
        }
        return result
    }
}

class LiveUpdateRateLimitException : IOException("GitHub API rate limit reached")
