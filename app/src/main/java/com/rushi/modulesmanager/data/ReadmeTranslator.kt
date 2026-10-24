package com.rushi.modulesmanager.data

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Translates a Markdown README. Only prose is sent to the translator, so links, images, code
 * blocks and HTML tags keep their exact syntax. Text inside raw HTML blocks is translated
 * separately, with the tags and attributes left alone.
 *
 * Every part carries its own detected language in the response. A README can mix languages, so
 * deciding from the document as a whole would skip the foreign paragraphs of an otherwise
 * English document.
 */
object ReadmeTranslator {
    data class Result(val html: String, val sourceLanguage: String, val translated: Boolean)

    private const val MAX_PART = 3000

    private val parser = Parser.builder()
        .extensions(listOf(TablesExtension.create()))
        .build()
    private val renderer = HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create()))
        .build()

    suspend fun translate(
        markdown: String,
        targetTag: String,
        onProgress: (Float) -> Unit = {},
    ): Result {
        val target = TranslationLanguages.baseTag(targetTag)
            ?: throw IllegalStateException("Translation is not available for this language")

        val document = parser.parse(markdown)
        val nodes = collectTextNodes(document).filter { it.literal.isNotBlank() }
        val htmlBlocks = collectHtmlBlocks(document)
        if (nodes.isEmpty() && htmlBlocks.isEmpty()) {
            throw IllegalStateException("This README has no text that can be translated")
        }

        val groups = mutableListOf<Group>()
        nodes.forEach { node ->
            val parts = splitLong(node.literal)
            groups += Group(parts) { replacements ->
                node.literal = parts.indices.joinToString(" ") { index ->
                    replacements[index] ?: parts[index]
                }
            }
        }
        htmlBlocks.forEach { block ->
            block.segments.forEach { segment ->
                val parts = splitLong(segment.core)
                groups += Group(parts) { replacements ->
                    val translated = parts.indices.joinToString(" ") { index ->
                        replacements[index] ?: parts[index]
                    }
                    block.tokens[segment.tokenIndex].raw =
                        segment.leading + translated + segment.trailing
                    block.node.literal = block.tokens.joinToString("") { it.raw }
                }
            }
        }

        val entries = groups.flatMap { group -> group.parts.indices.map { group to it } }
        if (entries.isEmpty()) {
            throw IllegalStateException("This README has no text that can be translated")
        }

        val results = TranslateApi.translateAll(
            texts = entries.map { (group, index) -> group.parts[index] },
            targetTag = target,
            onProgress = onProgress,
        )

        val languages = mutableListOf<String>()
        results.forEachIndexed { index, result ->
            val (group, part) = entries[index]
            val original = group.parts[part]
            val source = result.sourceLanguage
            if (source != null) languages += source
            // Already in the target language, or nothing translatable: keep the original.
            if (source == target || original.none { it.isLetter() }) return@forEachIndexed
            val translated = result.text.trim()
            if (translated.isEmpty()) return@forEachIndexed
            // Keep brand names whose only change is capitalization, such as "LSPosed Repo".
            group.replacements[part] =
                if (translated.equals(original, ignoreCase = true)) original else translated
        }

        groups.forEach { group ->
            if (group.replacements.isNotEmpty()) group.commit(group.replacements)
        }

        val translated = groups.any { group ->
            group.replacements.any { (part, text) -> text != group.parts[part] }
        }
        val dominant = languages.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: target
        return Result(renderer.render(document), dominant, translated)
    }

    /** Plain prose of the document, for tests and nothing else. */
    internal fun proseOf(markdown: String): List<String> =
        collectTextNodes(parser.parse(markdown)).map { it.literal }

    /** Text inside a raw HTML fragment that would be translated, for tests. */
    internal fun htmlProseOf(html: String): List<String> =
        htmlTextSegments(tokenizeHtml(html)).map { it.core }

    private fun collectTextNodes(root: Node): List<Text> {
        val out = mutableListOf<Text>()
        fun walk(node: Node) {
            var child = node.firstChild
            while (child != null) {
                if (child is Text) out += child else walk(child)
                child = child.next
            }
        }
        walk(root)
        return out
    }

    private fun collectHtmlBlocks(root: Node): List<HtmlBlockInfo> {
        val blocks = mutableListOf<HtmlBlockInfo>()
        fun walk(node: Node) {
            var child = node.firstChild
            while (child != null) {
                if (child is HtmlBlock) {
                    val tokens = tokenizeHtml(child.literal)
                    val segments = htmlTextSegments(tokens)
                    if (segments.isNotEmpty()) {
                        blocks += HtmlBlockInfo(child, tokens, segments)
                    }
                } else {
                    walk(child)
                }
                child = child.next
            }
        }
        walk(root)
        return blocks
    }

    /** Text between tags, skipping script, style, pre and code bodies. */
    private fun htmlTextSegments(tokens: List<HtmlToken>): List<HtmlSegment> {
        val segments = mutableListOf<HtmlSegment>()
        var skipDepth = 0
        tokens.forEachIndexed { index, token ->
            if (token.isTag) {
                skipDepth = (skipDepth + skipDelta(token.raw)).coerceAtLeast(0)
                return@forEachIndexed
            }
            if (skipDepth > 0) return@forEachIndexed
            val core = token.raw.trim()
            if (core.isEmpty()) return@forEachIndexed
            segments += HtmlSegment(
                tokenIndex = index,
                leading = token.raw.takeWhile { it.isWhitespace() },
                core = core,
                trailing = token.raw.takeLastWhile { it.isWhitespace() },
            )
        }
        return segments
    }

    private val SKIP_TAGS = setOf("pre", "code", "script", "style")

    private fun skipDelta(tag: String): Int {
        val body = tag.removePrefix("<").trimStart()
        if (body.startsWith("!--")) return 0
        val closing = body.startsWith("/")
        val name = body.removePrefix("/")
            .substringBefore('>')
            .substringBefore(' ')
            .substringBefore('\t')
            .substringBefore('\n')
            .trimEnd('/')
            .lowercase()
        if (name !in SKIP_TAGS) return 0
        if (closing) return -1
        return if (body.trimEnd().endsWith("/>")) 0 else 1
    }

    /** Splits HTML into tags and the text between them, respecting quotes and comments. */
    private fun tokenizeHtml(html: String): List<HtmlToken> {
        val tokens = mutableListOf<HtmlToken>()
        var index = 0
        while (index < html.length) {
            if (html[index] != '<') {
                var end = html.indexOf('<', index)
                if (end == -1) end = html.length
                tokens += HtmlToken(html.substring(index, end), isTag = false)
                index = end
                continue
            }

            val next = html.getOrNull(index + 1)
            if (next == null || !(next.isLetter() || next == '/' || next == '!' || next == '?')) {
                tokens += HtmlToken("<", isTag = false)
                index++
                continue
            }

            val comment = html.startsWith("<!--", index)
            var cursor = index + 1
            var quote: Char? = null
            while (cursor < html.length) {
                val char = html[cursor]
                if (comment) {
                    if (html.startsWith("-->", cursor)) {
                        cursor += 2
                        break
                    }
                } else if (quote != null) {
                    if (char == quote) quote = null
                } else if (char == '"' || char == '\'') {
                    quote = char
                } else if (char == '>') {
                    break
                }
                cursor++
            }
            val end = minOf(cursor + 1, html.length)
            tokens += HtmlToken(html.substring(index, end), isTag = true)
            index = end
        }
        return tokens
    }

    private class HtmlBlockInfo(
        val node: HtmlBlock,
        val tokens: List<HtmlToken>,
        val segments: List<HtmlSegment>,
    )

    private class HtmlToken(var raw: String, val isTag: Boolean)

    private class HtmlSegment(
        val tokenIndex: Int,
        val leading: String,
        val core: String,
        val trailing: String,
    )

    private class Group(
        val parts: List<String>,
        val commit: (Map<Int, String>) -> Unit,
    ) {
        val replacements = HashMap<Int, String>()
    }

    /** Splits a very long text node so no request exceeds the translation limit. */
    private fun splitLong(text: String): List<String> {
        if (text.length <= MAX_PART) return listOf(text)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + MAX_PART, text.length)
            if (end < text.length) {
                val space = text.lastIndexOf(' ', end)
                if (space > start) end = space
            }
            val part = text.substring(start, end).trim()
            if (part.isNotEmpty()) parts += part
            start = if (end <= start) start + MAX_PART else end
        }
        return parts.ifEmpty { listOf(text) }
    }
}
