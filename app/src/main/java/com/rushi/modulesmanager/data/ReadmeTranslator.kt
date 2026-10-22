package com.rushi.modulesmanager.data

import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * Translates a Markdown README on-device. Fenced code blocks, inline code and URLs are swapped
 * for tokens before translating and put back afterwards, so code and links survive intact.
 */
object ReadmeTranslator {
    data class Result(val markdown: String, val sourceLanguage: String, val translated: Boolean)

    // Plain ASCII markers survive the on-device models better than private-use characters.
    private const val OPEN = "ZQXZ"
    private const val CLOSE = "ZQXZ"
    private const val MAX_CHUNK = 2800
    private const val SAMPLE_CHARS = 1200

    private val TOKEN = Regex("$OPEN\\s*(\\d+)\\s*$CLOSE")
    private val INLINE_CODE = Regex("`[^`\\n]+`")
    private val URL = Regex("""https?://[^\s)\]>"']+""")

    suspend fun translate(
        markdown: String,
        targetTag: String,
        onProgress: (Float) -> Unit = {},
    ): Result {
        val target = TranslateLanguage.fromLanguageTag(targetTag)
            ?: throw IllegalStateException("Translation is not available for this language")

        val tokenized = tokenize(markdown)
        val source = TranslateApi.identify(stripTokens(tokenized.text).take(SAMPLE_CHARS))
            ?: throw IllegalStateException("Could not detect the README language")
        if (source == target) return Result(markdown, source, translated = false)

        val chunks = chunk(tokenized.text, MAX_CHUNK)
        val translated = TranslateApi.translateChunks(chunks, source, target, onProgress)
        return Result(restore(translated, chunks, tokenized.tokens), source, translated = true)
    }

    internal data class Tokenized(val text: String, val tokens: List<String>)

    /** Replaces everything that must not be translated with a numbered token. */
    internal fun tokenize(markdown: String): Tokenized {
        val tokens = mutableListOf<String>()

        fun protect(value: String): String {
            tokens += value
            return "$OPEN${tokens.lastIndex}$CLOSE"
        }

        val out = StringBuilder()
        var fence: String? = null
        for (line in markdown.lines()) {
            val trimmed = line.trimStart()
            if (fence != null) {
                out.append(protect(line)).append('\n')
                val closing = line.trim()
                if (closing.length >= 3 && closing.all { it == fence.first() }) fence = null
                continue
            }
            val marker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (marker != null) {
                fence = marker
                out.append(protect(line)).append('\n')
                continue
            }
            var prose = line
            prose = INLINE_CODE.replace(prose) { protect(it.value) }
            prose = URL.replace(prose) { protect(it.value) }
            out.append(prose).append('\n')
        }
        return Tokenized(out.toString(), tokens)
    }

    private fun stripTokens(text: String): String = TOKEN.replace(text, " ")

    internal fun chunk(text: String, max: Int): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                chunks += current.toString()
                current.clear()
            }
        }
        for (line in text.split('\n')) {
            val pieces = if (line.length > max) splitLong(line, max) else listOf(line)
            for (piece in pieces) {
                val extra = if (current.isEmpty()) piece.length else piece.length + 1
                if (current.length + extra > max) flush()
                if (current.isNotEmpty()) current.append('\n')
                current.append(piece)
            }
        }
        flush()
        return if (chunks.isEmpty()) listOf(text) else chunks
    }

    private fun splitLong(line: String, max: Int): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < line.length) {
            var end = minOf(start + max, line.length)
            if (end < line.length) {
                val space = line.lastIndexOf(' ', end)
                if (space > start) end = space
            }
            // Never cut a placeholder in half; the translator would drop the link.
            val candidate = line.substring(start, end)
            val open = candidate.lastIndexOf(OPEN)
            if (open > 0 && candidate.indexOf(CLOSE, open) < 0) {
                end = start + open
            }
            val piece = line.substring(start, end).trim()
            if (piece.isNotEmpty()) pieces += piece
            start = if (end <= start) start + max else end
        }
        return pieces
    }

    internal fun restore(translated: List<String>, originals: List<String>, tokens: List<String>): String {
        val body = translated.indices.joinToString("\n") { index ->
            val translatedChunk = translated[index]
            val original = originals.getOrElse(index) { translatedChunk }
            // A chunk whose placeholders went missing is kept in its original form.
            val kept = if (countTokens(translatedChunk) == countTokens(original)) translatedChunk else original
            kept
        }
        return TOKEN.replace(body) { match ->
            tokens.getOrNull(match.groupValues[1].toIntOrNull() ?: -1) ?: match.value
        }
    }

    private fun countTokens(text: String): Int = TOKEN.findAll(text).count()
}
