package com.rushi.modulesmanager.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Translation through Google's public web endpoint. No API key or account is needed, but the
 * text leaves the device, the endpoint is unofficial and it is rate limited, so requests are
 * batched. Every answer carries the detected source language, which replaces a separate
 * on-device language identifier. The same batch API answers on a few Google hosts, so a blocked
 * or failing host falls through to the next one.
 */
object TranslateApi {
    data class Result(val text: String, val sourceLanguage: String?)

    suspend fun translateTo(text: String, targetTag: String): Result =
        translateAll(listOf(text), targetTag).first()

    /** Translates [texts] in order, batching them so a README needs only a few requests. */
    suspend fun translateAll(
        texts: List<String>,
        targetTag: String,
        onProgress: (Float) -> Unit = {},
    ): List<Result> = withContext(Dispatchers.IO) {
        val target = TranslationLanguages.baseTag(targetTag)
            ?: throw IllegalStateException("Translation is not available for this language")
        val results = arrayOfNulls<Result>(texts.size)
        val pending = texts.indices.filter { texts[it].isNotBlank() }
        texts.forEachIndexed { index, text ->
            if (text.isBlank()) results[index] = Result(text, null)
        }
        if (pending.isEmpty()) return@withContext results.map { it ?: Result("", null) }

        var done = 0
        for (batch in batches(pending, texts)) {
            val translated = request(batch.map { texts[it] }, target)
            batch.forEachIndexed { position, index ->
                results[index] = translated[position]
            }
            done += batch.size
            onProgress(done.toFloat() / pending.size)
        }
        results.map { it ?: Result("", null) }
    }

    /** Detects the language of each text without keeping the translation. */
    suspend fun detectAll(texts: List<String>): List<String?> =
        translateAll(texts, "en").map { it.sourceLanguage }

    private suspend fun request(texts: List<String>, target: String): List<Result> {
        val body = FormBody.Builder().apply {
            texts.forEach { add("q", it) }
        }.build()

        var lastError: Exception? = null
        for (host in HOSTS) {
            try {
                val request = Request.Builder()
                    .url("https://$host/translate_a/t?client=dict-chrome-ex&sl=auto&tl=$target")
                    .header("User-Agent", RepoApi.USER_AGENT)
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException(
                            if (response.code == 429) {
                                "Google Translate is rate limiting requests, try again later"
                            } else {
                                "Translation failed (HTTP ${response.code})"
                            },
                        )
                    }
                    return parse(response.body?.string().orEmpty(), texts.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Translation failed")
    }

    private fun parse(raw: String, expected: Int): List<Result> {
        val root = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            throw IOException("Google Translate returned an unexpected response")
        }
        val out = ArrayList<Result>(expected)
        for (index in 0 until root.length()) {
            val item = root.optJSONArray(index) ?: continue
            val text = item.optString(0)
            val detected = item.optString(1).takeIf { it.isNotBlank() && it != "null" }
            out += Result(text, TranslationLanguages.baseTag(detected))
        }
        if (out.size != expected) {
            throw IOException("Google Translate returned an incomplete response")
        }
        return out
    }

    /** Groups indexes into requests that stay under the endpoint's item and size limits. */
    private fun batches(indices: List<Int>, texts: List<String>): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var chars = 0
        for (index in indices) {
            val length = texts[index].length
            if (current.isNotEmpty() &&
                (current.size >= MAX_BATCH_ITEMS || chars + length > MAX_BATCH_CHARS)
            ) {
                result += current
                current = mutableListOf()
                chars = 0
            }
            current += index
            chars += length
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** Tried in order; every one serves the same batch response. */
    private val HOSTS = listOf(
        "clients5.google.com",
        "translate.googleapis.com",
        "translate.google.com",
    )
    private const val MAX_BATCH_ITEMS = 20
    private const val MAX_BATCH_CHARS = 6000
}
