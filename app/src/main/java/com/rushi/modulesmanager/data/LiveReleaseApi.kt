package com.rushi.modulesmanager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Checks the latest GitHub release for a repository. The daily repos.json index is fetched once
 * a day, so installed modules are checked here to catch releases published after that snapshot.
 */
class LiveReleaseApi(
    private val client: OkHttpClient,
    private val apiBase: String = "https://api.github.com",
) {

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

    suspend fun fetchLatest(repoFullName: String): LiveRelease? = withContext(Dispatchers.IO) {
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
    suspend fun fetchAll(repoFullName: String): List<LiveRelease>? = withContext(Dispatchers.IO) {
        val cacheKey = "all:$repoFullName"
        val request = Request.Builder()
            .url("${apiBase.trimEnd('/')}/repos/$repoFullName/releases?per_page=100")
            .header("User-Agent", RepoApi.USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .apply {
                etags[cacheKey]?.let { header("If-None-Match", it) }
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
