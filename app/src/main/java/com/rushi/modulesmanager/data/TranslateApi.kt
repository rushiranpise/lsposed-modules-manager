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

    /** Translates chunks with a single client so the model is only prepared once. */
    suspend fun translateChunks(
        chunks: List<String>,
        sourceLanguage: String,
        targetTag: String,
        onProgress: (Float) -> Unit = {},
    ): List<String> = withContext(Dispatchers.Default) {
        val target = TranslateLanguage.fromLanguageTag(targetTag)
            ?: throw IllegalStateException("Translation is not available for this language")
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(target)
                .build(),
        )
        try {
            translator.downloadModelIfNeeded().await()
            val translated = ArrayList<String>(chunks.size)
            chunks.forEachIndexed { index, chunk ->
                translated += translator.translate(chunk).await()
                onProgress((index + 1f) / chunks.size)
            }
            translated
        } finally {
            translator.close()
        }
    }

    /** Identifies each text with one client, returning null where the language is unclear. */
    suspend fun identifyAll(texts: List<String>): List<String?> = withContext(Dispatchers.Default) {
        val identifier = LanguageIdentification.getClient()
        try {
            texts.map { text ->
                if (text.isBlank()) {
                    null
                } else {
                    try {
                        TranslateLanguage.fromLanguageTag(identifier.identifyLanguage(text).await())
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        } finally {
            identifier.close()
        }
    }

    suspend fun identify(text: String): String? {
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
