package com.rushi.modulesmanager.data

import java.util.Locale

/** Languages the translator offers, with names in the user's locale. */
object TranslationLanguages {
    /**
     * Base tags understood by the Google endpoint. This is the same set the app offered before,
     * so a saved choice keeps working.
     */
    private val TAGS = listOf(
        "af", "sq", "ar", "be", "bn", "bg", "ca", "zh", "hr", "cs", "da", "nl", "en", "eo",
        "et", "fi", "fr", "gl", "ka", "de", "el", "gu", "ht", "he", "hi", "hu", "is", "id",
        "ga", "it", "ja", "kn", "kk", "ko", "lv", "lt", "mk", "ms", "mt", "mr", "no", "fa",
        "pl", "pt", "ro", "ru", "sk", "sl", "sw", "sv", "tl", "ta", "te", "th", "tr", "uk",
        "ur", "vi", "cy",
    )

    /** Base language of a tag such as "de-AT" or "zh-CN", or null when it is unknown. */
    fun baseTag(tag: String?): String? {
        val clean = tag?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-') ?: return null
        if (clean.isEmpty() || clean == "auto" || clean == "und") return null
        return when (val base = clean.substringBefore('-')) {
            "iw" -> "he"
            "in" -> "id"
            "jw" -> "jv"
            else -> base
        }
    }

    fun isSupported(tag: String): Boolean = baseTag(tag)?.let { it in TAGS } == true

    fun all(): List<Pair<String, String>> = TAGS
        .map { tag -> tag to displayName(tag) }
        .sortedBy { (_, name) -> name.lowercase(Locale.getDefault()) }

    fun deviceLanguage(): String = Locale.getDefault().language

    fun displayName(tag: String): String {
        val name = Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())
        return name.ifBlank { tag }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    /** Name of the configured language, or of the device language when none is set. */
    fun selectedLabel(tag: String?): String = displayName(tag ?: deviceLanguage())

    /** True when the given text language is the language the user wants to read. */
    fun matches(textLanguage: String?, target: String?): Boolean {
        val text = baseTag(textLanguage) ?: return false
        val wanted = baseTag(target) ?: baseTag(deviceLanguage()) ?: return false
        return text == wanted
    }
}
