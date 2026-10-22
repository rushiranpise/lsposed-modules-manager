package com.rushi.modulesmanager.data

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** Languages the on-device translator supports, with names in the user's locale. */
object TranslationLanguages {
    fun isSupported(tag: String): Boolean = TranslateLanguage.fromLanguageTag(tag) != null

    /** Pairs of ML Kit language tag and display name, sorted by name. */
    fun all(): List<Pair<String, String>> = TranslateLanguage.getAllLanguages()
        .map { tag -> tag to displayName(tag) }
        .sortedBy { (_, name) -> name.lowercase(Locale.getDefault()) }

    fun deviceLanguage(): String = Locale.getDefault().language

    fun displayName(tag: String): String {
        val name = Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())
        return name.ifBlank { tag }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    /** Name of the configured language, or of the device language when none is set. */
    fun selectedLabel(tag: String?): String = displayName(tag ?: deviceLanguage())
}
