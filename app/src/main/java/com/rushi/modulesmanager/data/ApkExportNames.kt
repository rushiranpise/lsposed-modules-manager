package com.rushi.modulesmanager.data

import java.net.URI
import java.security.MessageDigest

/** Asset names that many repositories use verbatim, so they say nothing about the module. */
private val GENERIC_NAMES = setOf(
    "app",
    "app-release",
    "apprelease",
    "app-debug",
    "appdebug",
    "release",
    "debug",
    "module",
    "app-release-unsigned",
    "app-release-signed",
)

/**
 * Name for the public copy in `Download/ModulesManager`.
 *
 * The identity hash goes after the human-readable part and before the
 * extension (`Wren-1a2b3c4d5e6f7a8b.apk`), so two modules that both publish
 * `app-release.apk` never overwrite each other and the name stays recognisable.
 */
internal fun exportedApkName(asset: RepoAsset): String {
    val baseName = asset.name
        .substringAfterLast('/')
        .substringAfterLast('\\')
    val display = displayNameFor(baseName, asset)
    val stem = display.substringBeforeLast('.', display)
    val ext = display.substringAfterLast('.', "")
    val key = exportIdentity(asset)
    return if (ext.isBlank()) "$stem-$key" else "$stem-$key.$ext"
}

/** Human-readable part of the file name; the identity hash is appended later. */
private fun displayNameFor(baseName: String, asset: RepoAsset): String {
    val stem = baseName.substringBeforeLast('.', baseName)
    val isGeneric = GENERIC_NAMES.any { stem.equals(it, ignoreCase = true) }
    val candidate = (if (isGeneric) projectNameFrom(asset) else stem).orEmpty()
    val clean = candidate.ifBlank { stem }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(80)
        .ifBlank { "module" }
    return if (clean.endsWith(".apk", ignoreCase = true)) clean else "$clean.apk"
}

/** Stable 16-character identity for an asset, used to avoid overwriting exports. */
private fun exportIdentity(asset: RepoAsset): String {
    val identity = buildString {
        append(asset.downloadUrl)
        append('|')
        append(asset.originalDownloadUrl.orEmpty())
        append('|')
        append(asset.name)
        append('|')
        append(asset.size)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * Extracts the project name from the download URL when the asset file name is
 * a generic `app-release.apk` style name. GitHub release URLs carry it right
 * before the `releases/download` segment; assets.lsposed.org places it right
 * after the `/a/` marker.
 */
private fun projectNameFrom(asset: RepoAsset): String? {
    val url = asset.downloadUrl.ifBlank { asset.originalDownloadUrl.orEmpty() }
    val path = runCatching { URI(url).path }.getOrNull() ?: return null
    val segments = path.split('/').filter { it.isNotBlank() }
    if (segments.isEmpty()) return null

    val releaseIdx = segments.indexOfLast { it.equals("releases", ignoreCase = true) }
    if (releaseIdx >= 1) return segments[releaseIdx - 1]

    val aIdx = segments.indexOfFirst { it.equals("a", ignoreCase = true) }
    if (aIdx in 0 until segments.lastIndex) return segments[aIdx + 1]

    return segments.dropLast(1).lastOrNull { it.contains('.') }
}
