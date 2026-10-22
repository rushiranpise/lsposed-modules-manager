package com.rushi.modulesmanager.data

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation through ML Kit. No API key is needed; the first use of a language pair
 * downloads its model (around 30 MB).
 */
object TranslateApi {
    data class Result(val text: String, val sourceLanguage: String)

    suspend fun translateTo(text: String, targetTag: String): Result = withContext(Dispatchers.Default) {
        val target = TranslateLanguage.fromLanguageTag(targetTag)
            ?: throw IllegalStateException("Translation is not available for this language")
        val source = identify(text)
            ?: throw IllegalStateException("Could not detect the description language")
        if (source == target || text.isBlank()) return@withContext Result(text, source)

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build(),
        )
        try {
            translator.downloadModelIfNeeded().await()
            Result(translator.translate(text).await(), source)
        } finally {
            translator.close()
        }
    }

    private suspend fun identify(text: String): String? {
        val identifier = LanguageIdentification.getClient()
        return try {
            val code = identifier.identifyLanguage(text).await()
            TranslateLanguage.fromLanguageTag(code)
        } catch (_: Exception) {
            null
        } finally {
            identifier.close()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
}
