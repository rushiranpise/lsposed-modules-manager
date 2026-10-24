package com.rushi.modulesmanager.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-module update preferences: a skipped version, permanently ignored updates and the
 * opt-in pre-release stream. Kept out of [Settings] so the settings screen stays about the app,
 * while everything here is keyed by package name.
 */
class ModuleStateStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("module_state", Context.MODE_PRIVATE)

    private val _skipped = MutableStateFlow(readMap(KEY_SKIPPED))
    val skipped: StateFlow<Map<String, String>> = _skipped.asStateFlow()

    private val _ignored = MutableStateFlow(readSet(KEY_IGNORED))
    val ignored: StateFlow<Set<String>> = _ignored.asStateFlow()

    private val _preRelease = MutableStateFlow(readSet(KEY_PRE_RELEASE))
    val preRelease: StateFlow<Set<String>> = _preRelease.asStateFlow()

    /** Installed package name to repository package name, for renamed or forked modules. */
    private val _links = MutableStateFlow(readMap(KEY_LINKS))
    val links: StateFlow<Map<String, String>> = _links.asStateFlow()

    /** The APK variant picked for a package, so later releases do not ask again. */
    private val _variants = MutableStateFlow(readMap(KEY_VARIANTS))

    /** User-corrected source repositories for modules the index points at a fork or wrong owner. */
    private val _sources = MutableStateFlow(readMap(KEY_SOURCES))
    val sourceOverrides: StateFlow<Map<String, String>> = _sources.asStateFlow()

    fun skipVersion(packageName: String, raw: String) {
        val updated = _skipped.value + (packageName to raw)
        _skipped.value = updated
        writeMap(KEY_SKIPPED, updated)
    }

    fun clearSkippedVersion(packageName: String) {
        val updated = _skipped.value - packageName
        _skipped.value = updated
        writeMap(KEY_SKIPPED, updated)
    }

    fun toggleIgnored(packageName: String): Boolean {
        val current = _ignored.value
        val updated = if (packageName in current) current - packageName else current + packageName
        _ignored.value = updated
        writeSet(KEY_IGNORED, updated)
        return packageName in updated
    }

    fun togglePreRelease(packageName: String): Boolean {
        val current = _preRelease.value
        val updated = if (packageName in current) current - packageName else current + packageName
        _preRelease.value = updated
        writeSet(KEY_PRE_RELEASE, updated)
        return packageName in updated
    }

    fun linkModule(installedPackage: String, repoPackage: String) {
        val updated = _links.value + (installedPackage to repoPackage)
        _links.value = updated
        writeMap(KEY_LINKS, updated)
    }

    fun unlinkModule(installedPackage: String) {
        val updated = _links.value - installedPackage
        _links.value = updated
        writeMap(KEY_LINKS, updated)
    }

    fun sourceOverride(packageName: String): String? = _sources.value[packageName]

    fun setSourceOverride(packageName: String, repoFullName: String?) {
        val updated = if (repoFullName.isNullOrBlank()) {
            _sources.value - packageName
        } else {
            _sources.value + (packageName to repoFullName)
        }
        _sources.value = updated
        writeMap(KEY_SOURCES, updated)
    }

    fun variantFor(packageName: String): String? = _variants.value[packageName]

    fun rememberVariant(packageName: String, value: String) {
        val updated = _variants.value + (packageName to value)
        _variants.value = updated
        writeMap(KEY_VARIANTS, updated)
    }

    private fun readMap(key: String): Map<String, String> =
        prefs.getString(key, null)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val index = line.indexOf('|')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }
            ?.toMap()
            .orEmpty()

    private fun writeMap(key: String, values: Map<String, String>) {
        prefs.edit()
            .putString(key, values.entries.joinToString("\n") { "${it.key}|${it.value}" })
            .apply()
    }

    private fun readSet(key: String): Set<String> =
        prefs.getString(key, null)
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    private fun writeSet(key: String, values: Set<String>) {
        prefs.edit().putString(key, values.joinToString("\n")).apply()
    }

    private companion object {
        const val KEY_SKIPPED = "skipped_versions"
        const val KEY_IGNORED = "ignored_updates"
        const val KEY_PRE_RELEASE = "pre_release_modules"
        const val KEY_LINKS = "module_links"
        const val KEY_VARIANTS = "module_variants"
        const val KEY_SOURCES = "module_sources"
    }
}
