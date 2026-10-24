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
    }
}
