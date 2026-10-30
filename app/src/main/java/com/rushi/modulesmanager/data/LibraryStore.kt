package com.rushi.modulesmanager.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Personal lists that are not part of the repository: favourites, history and searches. */
class LibraryStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("library", Context.MODE_PRIVATE)

    private val _favourites = MutableStateFlow(readSet(KEY_FAVOURITES))
    val favourites: StateFlow<Set<String>> = _favourites.asStateFlow()

    private val _recentlyViewed = MutableStateFlow(readList(KEY_RECENT))
    val recentlyViewed: StateFlow<List<String>> = _recentlyViewed.asStateFlow()

    /** Returns true when the package is now a favourite. */
    fun toggleFavourite(packageName: String): Boolean {
        val current = _favourites.value
        val updated = if (packageName in current) current - packageName else current + packageName
        _favourites.value = updated
        writeList(KEY_FAVOURITES, updated.toList())
        return packageName in updated
    }

    fun recordViewed(packageName: String) {
        val updated = (listOf(packageName) + _recentlyViewed.value)
            .distinct()
            .take(MAX_RECENT)
        if (updated == _recentlyViewed.value) return
        _recentlyViewed.value = updated
        writeList(KEY_RECENT, updated)
    }

    private fun readSet(key: String): Set<String> = readList(key).toSet()

    private fun readList(key: String): List<String> = runCatching {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun writeList(key: String, values: List<String>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }

    private companion object {
        const val KEY_FAVOURITES = "favourites"
        const val KEY_RECENT = "recently_viewed"
        const val MAX_RECENT = 20
    }
}
