package com.rushi.modulesmanager.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class UpdateChannel(val key: String, val label: String, val description: String) {
    STABLE("stable", "Stable", "Released versions only"),
    PRE_RELEASE("prerelease", "Pre-release", "Include pre-release builds");

    companion object {
        fun fromKey(key: String?): UpdateChannel = when (key) {
            "prerelease", "beta", "snapshot" -> PRE_RELEASE
            else -> STABLE
        }
    }
}

enum class SortMode(val key: String, val label: String) {
    NAME("name", "Name"),
    UPDATED("updated", "Last updated"),
    ADDED("added", "Recently added"),
    STARS("stars", "Most starred");

    companion object {
        fun fromKey(key: String?): SortMode = entries.firstOrNull { it.key == key } ?: UPDATED
    }
}

data class RepoAsset(
    val name: String,
    val contentType: String?,
    val downloadUrl: String,
    val downloadCount: Int,
    val size: Long,
    /** GitHub URL used when the signed assets.lsposed.org URL stops working. */
    val originalDownloadUrl: String? = null,
)

data class RepoRelease(
    val name: String?,
    val tagName: String?,
    val url: String?,
    val descriptionHtml: String?,
    val publishedAt: String?,
    val isPrerelease: Boolean,
    val assets: List<RepoAsset>,
) {
    val apkAssets: List<RepoAsset>
        get() = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }

    val isSnapshot: Boolean
        get() {
            val value = (name ?: tagName ?: "").lowercase(Locale.ROOT)
            return value.startsWith("snapshot") || value.startsWith("nightly")
        }

    /** True when this release is the exact version an update check promised. */
    fun matchesVersion(version: ModuleVersion): Boolean {
        val releaseVersion = ModuleVersion.parse(tagName) ?: return false
        return releaseVersion.code == version.code &&
            releaseVersion.name.replace(' ', '_') == version.name.replace(' ', '_')
    }

    fun supports(channel: UpdateChannel): Boolean = when (channel) {
        UpdateChannel.STABLE -> !isPrerelease && !isSnapshot
        UpdateChannel.PRE_RELEASE -> true
    }
}

data class RepoCollaborator(val login: String, val name: String?)

data class RepoModule(
    val packageName: String,
    val displayName: String,
    val summary: String?,
    val url: String?,
    val homepageUrl: String?,
    val sourceUrl: String?,
    val latestRelease: String?,
    val latestReleaseTime: String?,
    val latestBetaRelease: String?,
    val latestBetaReleaseTime: String?,
    val latestSnapshotRelease: String?,
    val latestSnapshotReleaseTime: String?,
    val releases: List<RepoRelease>,
    val betaReleases: List<RepoRelease>,
    val snapshotReleases: List<RepoRelease>,
    val readme: String?,
    val readmeHtml: String?,
    val collaborators: List<RepoCollaborator>,
    val scope: List<String>,
    val hide: Boolean,
    val updatedAt: String?,
    val stargazerCount: Int,
    /** GitHub forks from the daily index. */
    val forkCount: Int = 0,
    /** When the daily index first saw this module. */
    val addedAt: String? = null,
    /** GitHub repo that hosts releases, used for live update checks. */
    val repoFullName: String? = null,
    /** Full release list fetched for the Releases tab. */
    val releaseHistory: List<RepoRelease> = emptyList(),
) {
    val hasReleases: Boolean
        get() = releases.isNotEmpty() || betaReleases.isNotEmpty() || snapshotReleases.isNotEmpty()

    fun latest(channel: UpdateChannel): ModuleVersion? =
        latestFor(channel == UpdateChannel.PRE_RELEASE)

    /**
     * Newest version across the streams that are being followed. A pre-release is not
     * automatically better: 24 of the modules that publish betas ship one with an older
     * versionCode than stable, so both are compared and the newer one wins.
     */
    fun latestFor(includePreReleases: Boolean): ModuleVersion? =
        ModuleVersion.parse(latestRawFor(includePreReleases))

    fun latestTime(channel: UpdateChannel): String? =
        latestTimeFor(channel == UpdateChannel.PRE_RELEASE)

    fun latestTimeFor(includePreReleases: Boolean): String? =
        chosenStream(includePreReleases).second

    /** Repository tag of the version [latestFor] returns, used for per-version skips. */
    fun latestRawFor(includePreReleases: Boolean): String? =
        chosenStream(includePreReleases).first

    private fun chosenStream(includePreReleases: Boolean): Pair<String?, String?> {
        val stable = latestRelease
        if (!includePreReleases) return stable to latestReleaseTime
        val beta = latestBetaRelease ?: return stable to latestReleaseTime
        val betaTime = latestBetaReleaseTime ?: latestReleaseTime
        if (stable == null) return beta to betaTime
        val stableVersion = ModuleVersion.parse(stable)
        val betaVersion = ModuleVersion.parse(beta)
        if (stableVersion == null || betaVersion == null) return stable to latestReleaseTime
        return when {
            betaVersion.code > stableVersion.code -> beta to betaTime
            betaVersion.code < stableVersion.code -> stable to latestReleaseTime
            betaTime.orEmpty() > latestReleaseTime.orEmpty() -> beta to betaTime
            else -> stable to latestReleaseTime
        }
    }

    /** The release that actually carries [version], falling back to the channel release. */
    fun releaseForVersion(version: ModuleVersion, includePreReleases: Boolean): RepoRelease? {
        val matching = allReleases().firstOrNull { it.matchesVersion(version) }
        return matching ?: releaseFor(
            if (includePreReleases) UpdateChannel.PRE_RELEASE else UpdateChannel.STABLE,
        )
    }

    /** Every release the repository published, newest first, whichever channel it belongs to. */
    fun allReleases(): List<RepoRelease> =
        (releases + betaReleases + snapshotReleases + releaseHistory)
            .distinctBy { it.url ?: it.tagName ?: it.name }
            .sortedByDescending { parseInstant(it.publishedAt) ?: Instant.EPOCH }

    fun releasesFor(channel: UpdateChannel): List<RepoRelease> {
        val pool = when (channel) {
            UpdateChannel.STABLE -> releases
            UpdateChannel.PRE_RELEASE -> betaReleases.ifEmpty { releases }
        }
        val filtered = pool.filter { it.supports(channel) }
        return filtered.ifEmpty { pool }
    }

    fun releaseFor(channel: UpdateChannel): RepoRelease? {
        val pool = releasesFor(channel)
        return pool.firstOrNull { it.apkAssets.isNotEmpty() } ?: pool.firstOrNull()
    }

    /** GitHub owner/repo that hosts the module source, used to fetch its README. */
    fun githubRepoFullName(): String? {
        repoFullName
            ?.takeIf { it.contains('/') && !it.startsWith("Xposed-Modules-Repo/", ignoreCase = true) }
            ?.let { return it }
        val candidates = listOf(sourceUrl, url, homepageUrl)
        for (candidate in candidates) {
            val match = Regex("github\\.com/([^/]+)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(candidate ?: "") ?: continue
            val owner = match.groupValues[1]
            if (owner.equals("Xposed-Modules-Repo", ignoreCase = true)) continue
            return "$owner/${match.groupValues[2].removeSuffix(".git")}"
        }
        // Some modules exist only in the Xposed-Modules-Repo mirror. Its README
        // is the only one available, so use it when there is no source repo.
        repoFullName
            ?.takeIf { it.startsWith("Xposed-Modules-Repo/", ignoreCase = true) }
            ?.let { return it }
        return null
    }

    /** Replaces the index source with a repository the user chose. */
    fun withSourceOverride(repoFullName: String): RepoModule {
        val owner = repoFullName.substringBefore('/')
        return copy(
            sourceUrl = "https://github.com/$repoFullName",
            repoFullName = repoFullName,
            url = "https://github.com/$repoFullName/releases",
            collaborators = listOf(RepoCollaborator(owner, null)),
            readme = null,
            readmeHtml = null,
        )
    }

    /** Puts the index source back while keeping releases and versions already merged. */
    fun withOriginalSource(original: RepoModule): RepoModule = copy(
        sourceUrl = original.sourceUrl,
        repoFullName = original.repoFullName,
        url = original.url,
        collaborators = original.collaborators,
        readme = null,
        readmeHtml = null,
    )

    /** GitHub owner of the module, used for its avatar. */
    fun avatarUrl(): String? {
        val candidate = sourceUrl ?: url ?: homepageUrl ?: return null
        val owner = Regex("github\\.com/([^/?#]+)").find(candidate)?.groupValues?.getOrNull(1) ?: return null
        // These accounts host many unrelated modules, so their logo would repeat on every row.
        if (owner.lowercase() in setOf("releases", "apps", "xposed-modules-repo", "lsposed")) return null
        return "https://github.com/$owner.png?size=192"
    }
}

data class ModuleVersion(val code: Long, val name: String) {
    fun isNewerThan(installedCode: Long, installedName: String?): Boolean {
        if (code > 0L) {
            if (installedCode <= 0L) return true
            return code > installedCode
        }
        return compareVersionNames(name, installedName) > 0
    }

    /**
     * LSPosed treats a same-code re-upload as an update, but only when the release was published
     * after the installed copy landed. Without the timestamp, naming drift would report an update
     * forever and installing it would never clear the prompt.
     */
    fun isUpdateOver(installed: InstalledPackageVersion, publishedAt: String?): Boolean {
        if (code > 0L && installed.versionCode > 0L) {
            if (code != installed.versionCode) return code > installed.versionCode
            val sameName = name.replace(' ', '_') ==
                installed.versionName.orEmpty().replace(' ', '_')
            if (sameName) return false
            val published = parseInstant(publishedAt)?.toEpochMilli() ?: return false
            return installed.lastUpdateTime > 0L && published > installed.lastUpdateTime
        }
        return compareVersionNames(name, installed.versionName) > 0
    }

    companion object {
        /** Handles both "versionCode-versionName" and plain tags like "v1.2.3". */
        fun parse(value: String?): ModuleVersion? {
            if (value.isNullOrBlank()) return null
            val parts = value.split('-', limit = 2)
            if (parts.size == 2) {
                val code = parts[0].toLongOrNull()
                if (code != null) return ModuleVersion(code, parts[1])
            }
            return ModuleVersion(0, value)
        }
    }
}

private fun versionSegments(value: String?): List<String> {
    if (value.isNullOrBlank()) return emptyList()
    return value.trim().lowercase()
        .removePrefix("v")
        .split(Regex("[^0-9a-z]+"))
        .filter { it.isNotEmpty() }
}

private fun compareVersionNames(left: String?, right: String?): Int {
    val a = versionSegments(left)
    val b = versionSegments(right)
    val max = maxOf(a.size, b.size)
    for (i in 0 until max) {
        val x = a.getOrNull(i)
        val y = b.getOrNull(i)
        if (x == null && y == null) return 0
        if (x == null) return if (y?.toLongOrNull() == 0L) 0 else -1
        if (y == null) return if (x.toLongOrNull() == 0L) 0 else 1
        val nx = x.toLongOrNull()
        val ny = y.toLongOrNull()
        val cmp = when {
            nx != null && ny != null -> nx.compareTo(ny)
            nx != null -> 1
            ny != null -> -1
            else -> x.compareTo(y)
        }
        if (cmp != 0) return cmp
    }
    return 0
}

object RepoJson {
    /** Parses the daily data/repos.json file from the xposed-modules data repository. */
    fun parseRepos(json: String): List<RepoModule> {
        val array = JSONArray(json)
        val byPackage = LinkedHashMap<String, RepoModule>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val module = runCatching { parseRepoEntry(obj) }.getOrNull() ?: continue
            val existing = byPackage[module.packageName]
            if (existing == null || isNewerModule(module, existing)) {
                byPackage[module.packageName] = module
            }
        }
        return byPackage.values.toList()
    }

    private fun prettifyName(value: String): String =
        value.replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

    private fun isNewerModule(candidate: RepoModule, existing: RepoModule): Boolean {
        val candidateTime = parseInstant(candidate.latestReleaseTime)
        val existingTime = parseInstant(existing.latestReleaseTime)
        if (candidateTime == null) return false
        if (existingTime == null) return true
        return candidateTime.isAfter(existingTime)
    }

    /** Packages present in the index but without a fetched release, used for installed fallback. */
    fun packagesWithoutReleases(json: String): Set<String> {
        val array = JSONArray(json)
        val result = HashSet<String>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val packageName = packageOf(obj) ?: continue
            val release = obj.optJSONObject("release")
            if (release == null || release.optString("apk_url").isBlank()) {
                result += packageName
            }
        }
        return result
    }

    private fun packageOf(obj: JSONObject): String? {
        val orgRepo = obj.optString("org_repo")
        return obj.optString("package").takeIf { it.isNotBlank() }
            ?: orgRepo.takeIf { it.startsWith("Xposed-Modules-Repo/") }
                ?.substringAfter("Xposed-Modules-Repo/")
                ?.takeIf { it.isNotBlank() }
    }

    private fun parseRepoEntry(obj: JSONObject): RepoModule? {
        val orgRepo = obj.optString("org_repo")
        val packageName = packageOf(obj) ?: return null

        val release = obj.optJSONObject("release") ?: return null
        val apkUrl = release.optString("apk_url").takeIf { it.isNotBlank() } ?: return null
        val tag = release.optString("tag").takeIf { it.isNotBlank() }
            ?: release.optString("name").takeIf { it.isNotBlank() }
            ?: return null
        val releaseUrl = release.optString("html_url").takeIf { it.isNotBlank() && it != "null" }
        val publishedAt = release.optString("published_at").takeIf { it.isNotBlank() && it != "null" }
        val releaseName = release.optString("name").takeIf { it.isNotBlank() && it != "null" }
        val htmlUrl = obj.optString("html_url").takeIf { it.isNotBlank() && it != "null" }
        val fullName = obj.optString("full_name").takeIf { it.isNotBlank() && it != "null" }
        val metadataName = obj.optJSONObject("metadata")
            ?.optString("name")
            ?.takeIf { it.isNotBlank() && it != "null" }
        val repoName = (fullName ?: packageName).substringAfter('/')
        val displayName = metadataName ?: if (repoName != packageName && repoName.isNotBlank()) {
            prettifyName(repoName)
        } else {
            prettifyName(packageName.substringAfterLast('.'))
        }
        val summary = obj.optString("summary").takeIf { it.isNotBlank() && it != "null" }
            ?: obj.optString("description").takeIf { it.isNotBlank() && it != "null" }
        val owner = obj.optJSONObject("owner")
        val ownerLogin = owner?.optString("login")?.takeIf { it.isNotBlank() && it != "null" }
        val assetName = apkUrl.substringAfterLast('/').substringBefore('?').ifBlank { "module.apk" }
        val asset = RepoAsset(
            name = assetName,
            contentType = "application/vnd.android.package-archive",
            downloadUrl = apkUrl,
            downloadCount = 0,
            size = 0L,
        )
        val parsedRelease = RepoRelease(
            name = releaseName,
            tagName = tag,
            url = releaseUrl,
            descriptionHtml = null,
            publishedAt = publishedAt,
            isPrerelease = release.optBoolean("prerelease", false),
            assets = listOf(asset),
        )

        return RepoModule(
            packageName = packageName,
            displayName = displayName,
            summary = summary,
            url = releaseUrl ?: htmlUrl,
            homepageUrl = obj.optString("homepage").takeIf { it.isNotBlank() && it != "null" },
            sourceUrl = obj.optString("source_url").takeIf { it.isNotBlank() && it != "null" } ?: htmlUrl,
            latestRelease = tag,
            latestReleaseTime = publishedAt,
            latestBetaRelease = null,
            latestBetaReleaseTime = null,
            latestSnapshotRelease = null,
            latestSnapshotReleaseTime = null,
            releases = listOf(parsedRelease),
            betaReleases = emptyList(),
            snapshotReleases = emptyList(),
            readme = null,
            readmeHtml = null,
            collaborators = listOfNotNull(ownerLogin?.let { RepoCollaborator(it, null) }),
            scope = parseStrings(obj.optJSONArray("scope")),
            hide = false,
            updatedAt = obj.optString("pushed_at").takeIf { it.isNotBlank() && it != "null" } ?: publishedAt,
            stargazerCount = obj.optInt("stargazers_count", 0),
            forkCount = obj.optInt("forks_count", 0),
            addedAt = obj.optString("added_at").takeIf { it.isNotBlank() && it != "null" },
            repoFullName = orgRepo.takeIf { it.isNotBlank() && it != "null" } ?: fullName,
        )
    }

    fun parseModules(json: String): List<RepoModule> = parseModules(JSONArray(json))

    fun parseModules(array: JSONArray): List<RepoModule> {
        val modules = ArrayList<RepoModule>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            runCatching { parseModule(obj) }.getOrNull()?.let(modules::add)
        }
        return modules
    }

    fun parseModule(json: String): RepoModule = parseModule(JSONObject(json))

    private fun parseModule(obj: JSONObject): RepoModule {
        val packageName = obj.optString("name").takeIf { it.isNotBlank() } ?: error("module has no name")
        val description = obj.optString("description").takeIf { it.isNotBlank() && it != "null" }
        return RepoModule(
            packageName = packageName,
            displayName = description ?: packageName,
            summary = obj.optString("summary").takeIf { it.isNotBlank() && it != "null" },
            url = obj.optString("url").takeIf { it.isNotBlank() && it != "null" },
            homepageUrl = obj.optString("homepageUrl").takeIf { it.isNotBlank() && it != "null" },
            sourceUrl = obj.optString("sourceUrl").takeIf { it.isNotBlank() && it != "null" },
            latestRelease = obj.optString("latestRelease").takeIf { it.isNotBlank() && it != "null" },
            latestReleaseTime = obj.optString("latestReleaseTime").takeIf { it.isNotBlank() && it != "null" },
            latestBetaRelease = obj.optString("latestBetaRelease").takeIf { it.isNotBlank() && it != "null" },
            latestBetaReleaseTime = obj.optString("latestBetaReleaseTime").takeIf { it.isNotBlank() && it != "null" },
            latestSnapshotRelease = obj.optString("latestSnapshotRelease").takeIf { it.isNotBlank() && it != "null" },
            latestSnapshotReleaseTime = obj.optString("latestSnapshotReleaseTime").takeIf { it.isNotBlank() && it != "null" },
            releases = parseReleases(obj.optJSONArray("releases")),
            betaReleases = parseReleases(obj.optJSONArray("betaReleases")),
            snapshotReleases = parseReleases(obj.optJSONArray("snapshotReleases")),
            readme = obj.optString("readme").takeIf { it.isNotBlank() && it != "null" },
            readmeHtml = obj.optString("readmeHTML").takeIf { it.isNotBlank() && it != "null" },
            collaborators = parseCollaborators(obj.optJSONArray("collaborators")),
            scope = parseStrings(obj.optJSONArray("scope")),
            hide = obj.optBoolean("hide", false),
            updatedAt = obj.optString("updatedAt").takeIf { it.isNotBlank() && it != "null" },
            stargazerCount = obj.optInt("stargazerCount", 0),
        )
    }

    private fun parseReleases(array: JSONArray?): List<RepoRelease> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val assets = obj.optJSONArray("releaseAssets")?.let { items ->
                    buildList(items.length()) {
                        for (i in 0 until items.length()) {
                            val asset = items.optJSONObject(i) ?: continue
                            val url = asset.optString("downloadUrl")
                            if (url.isBlank()) continue
                            add(
                                RepoAsset(
                                    name = asset.optString("name").ifBlank { "download" },
                                    contentType = asset.optString("contentType").takeIf { it.isNotBlank() },
                                    downloadUrl = url,
                                    downloadCount = asset.optInt("downloadCount", 0),
                                    size = asset.optLong("size", 0L),
                                    originalDownloadUrl = asset.optString("originalDownloadUrl")
                                        .takeIf { it.isNotBlank() && it != "null" },
                                ),
                            )
                        }
                    }
                } ?: emptyList()

                add(
                    RepoRelease(
                        name = obj.optString("name").takeIf { it.isNotBlank() && it != "null" },
                        tagName = obj.optString("tagName").takeIf { it.isNotBlank() && it != "null" },
                        url = obj.optString("url").takeIf { it.isNotBlank() && it != "null" },
                        descriptionHtml = obj.optString("descriptionHTML").takeIf { it.isNotBlank() && it != "null" },
                        publishedAt = (obj.optString("publishedAt").takeIf { it.isNotBlank() && it != "null" })
                            ?: (obj.optString("createdAt").takeIf { it.isNotBlank() && it != "null" }),
                        isPrerelease = obj.optBoolean("isPrerelease", false),
                        assets = assets,
                    ),
                )
            }
        }.sortedByDescending { parseInstant(it.publishedAt) ?: Instant.EPOCH }
    }

    private fun parseCollaborators(array: JSONArray?): List<RepoCollaborator> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val login = obj.optString("login").takeIf { it.isNotBlank() } ?: continue
                add(RepoCollaborator(login, obj.optString("name").takeIf { it.isNotBlank() && it != "null" }))
            }
        }
    }

    private fun parseStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}

fun parseInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value) }.getOrNull()
}

fun formatDate(value: String?): String? {
    val instant = parseInstant(value) ?: return null
    return runCatching {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrNull()
}

fun formatRelativeDate(value: String?): String? {
    val instant = parseInstant(value) ?: return null
    return formatRelativeTime(instant.toEpochMilli())
}

fun formatRelativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = (now - epochMs) / 1000
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        seconds < 86_400 * 30 -> "${seconds / 86_400} d ago"
        else -> formatDate(Instant.ofEpochMilli(epochMs).toString()) ?: ""
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "unknown size"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toInt()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

fun stripHtml(html: String?): String? {
    if (html.isNullOrBlank()) return null
    return html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: ""
        }
        .replace(Regex("&#([0-9]+);")) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
        }
        .replace("&nbsp;", " ")
        .replace("&mdash;", "-")
        .replace("&ndash;", "-")
        .replace("&hellip;", "...")
        .replace("&rsquo;", "'")
        .replace("&lsquo;", "'")
        .replace("&ldquo;", "\"")
        .replace("&rdquo;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("\r", "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
