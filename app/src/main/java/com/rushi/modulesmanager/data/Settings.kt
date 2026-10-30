package com.rushi.modulesmanager.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("modules_manager", Context.MODE_PRIVATE)
    private val translationPrefs = context.getSharedPreferences("translation_cache", Context.MODE_PRIVATE)

    private val _channel = MutableStateFlow(UpdateChannel.fromKey(prefs.getString(KEY_CHANNEL, null)))
    val channel: StateFlow<UpdateChannel> = _channel.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.fromKey(prefs.getString(KEY_SORT, null)))
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _installer = MutableStateFlow(InstallerType.fromKey(prefs.getString(KEY_INSTALLER, null)))
    val installer: StateFlow<InstallerType> = _installer.asStateFlow()

    private val _theme = MutableStateFlow(AppTheme.fromKey(prefs.getString(KEY_THEME, null)))
    val theme: StateFlow<AppTheme> = _theme.asStateFlow()

    private val _amoled = MutableStateFlow(prefs.getBoolean(KEY_AMOLED, false))
    val amoled: StateFlow<Boolean> = _amoled.asStateFlow()

    private val _systemColors = MutableStateFlow(prefs.getBoolean(KEY_SYSTEM_COLORS, true))
    val systemColors: StateFlow<Boolean> = _systemColors.asStateFlow()

    private val _updatesFirst = MutableStateFlow(prefs.getBoolean(KEY_UPDATES_FIRST, false))
    val updatesFirst: StateFlow<Boolean> = _updatesFirst.asStateFlow()

    private val _autoTranslate = MutableStateFlow(prefs.getBoolean(KEY_AUTO_TRANSLATE, false))
    val autoTranslate: StateFlow<Boolean> = _autoTranslate.asStateFlow()

    private val _concurrentDownloads = MutableStateFlow(
        prefs.getInt(KEY_CONCURRENT_DOWNLOADS, DownloadSlots.DEFAULT_LIMIT)
            .coerceIn(DownloadSlots.MIN_LIMIT, DownloadSlots.MAX_LIMIT),
    )
    val concurrentDownloads: StateFlow<Int> = _concurrentDownloads.asStateFlow()

    /** Null means the device language. */
    private val _translateLanguage = MutableStateFlow(
        prefs.getString(KEY_TRANSLATE_LANGUAGE, null)?.takeIf { it.isNotBlank() },
    )
    val translateLanguage: StateFlow<String?> = _translateLanguage.asStateFlow()

    fun setChannel(channel: UpdateChannel) {
        prefs.edit().putString(KEY_CHANNEL, channel.key).apply()
        _channel.value = channel
    }

    fun setSortMode(mode: SortMode) {
        prefs.edit().putString(KEY_SORT, mode.key).apply()
        _sortMode.value = mode
    }

    fun setInstaller(installer: InstallerType) {
        prefs.edit().putString(KEY_INSTALLER, installer.key).apply()
        _installer.value = installer
    }

    fun setUpdatesFirst(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATES_FIRST, enabled).apply()
        _updatesFirst.value = enabled
    }

    fun setAutoTranslate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TRANSLATE, enabled).apply()
        _autoTranslate.value = enabled
    }

    fun setConcurrentDownloads(count: Int) {
        val clamped = count.coerceIn(DownloadSlots.MIN_LIMIT, DownloadSlots.MAX_LIMIT)
        prefs.edit().putInt(KEY_CONCURRENT_DOWNLOADS, clamped).apply()
        _concurrentDownloads.value = clamped
    }

    fun setTranslateLanguage(language: String?) {
        prefs.edit().putString(KEY_TRANSLATE_LANGUAGE, language).apply()
        _translateLanguage.value = language
    }

    fun setTheme(theme: AppTheme) {
        prefs.edit().putString(KEY_THEME, theme.key).apply()
        _theme.value = theme
    }

    fun setAmoled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AMOLED, enabled).apply()
        _amoled.value = enabled
    }

    fun setSystemColors(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYSTEM_COLORS, enabled).apply()
        _systemColors.value = enabled
    }

    fun lastLiveCheck(): Long = prefs.getLong(KEY_LAST_LIVE_CHECK, 0L)

    fun setLastLiveCheck(time: Long) {
        prefs.edit().putLong(KEY_LAST_LIVE_CHECK, time).apply()
    }

    fun cachedTranslation(key: String): String? = translationPrefs.getString(key, null)

    fun saveTranslation(key: String, text: String) {
        translationPrefs.edit().putString(key, text).apply()
    }

    private companion object {
        const val KEY_CHANNEL = "update_channel"
        const val KEY_SORT = "sort_mode"
        const val KEY_INSTALLER = "installer_type"
        const val KEY_THEME = "app_theme"
        const val KEY_AMOLED = "amoled_mode"
        const val KEY_SYSTEM_COLORS = "system_colors"
        const val KEY_UPDATES_FIRST = "updates_first"
        const val KEY_AUTO_TRANSLATE = "auto_translate"
        const val KEY_CONCURRENT_DOWNLOADS = "concurrent_downloads"
        const val KEY_TRANSLATE_LANGUAGE = "translate_language"
        const val KEY_LAST_LIVE_CHECK = "last_live_check"
    }
}
