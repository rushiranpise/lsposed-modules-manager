package com.rushi.modulesmanager.data

import com.google.mlkit.nl.translate.TranslateLanguage
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Translates a Markdown README on-device. Only prose is sent to the translator, so links, images,
 * code blocks and HTML tags keep their exact syntax. Text inside raw HTML blocks is translated
 * separately, with the tags and attributes left alone.
 *
 * Each text node is detected on its own. A README can mix languages, so deciding from the
 * document as a whole would skip the foreign paragraphs of an otherwise English document.
 */
object ReadmeTranslator {
    data class Result(val html: String, val sourceLanguage: String, val translated: Boolean)

    private const val MAX_PART = 3000
    private const val SAMPLE_CHARS = 1200

    /** Scripts that cannot be the target when the target itself is written in Latin letters. */
    private val NON_LATIN = Regex(
        "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsArabic}" +
            "\\p{IsCyrillic}\\p{IsHebrew}\\p{IsThai}\\p{IsDevanagari}]",
    )

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
        val target = TranslateLanguage.fromLanguageTag(targetTag)
            ?: throw IllegalStateException("Translation is not available for this language")

        val document = parser.parse(markdown)
        val nodes = collectTextNodes(document).filter { it.literal.isNotBlank() }
        val htmlBlocks = collectHtmlBlocks(document)
        if (nodes.isEmpty() && htmlBlocks.isEmpty()) {
            throw IllegalStateException("This README has no text that can be translated")
        }

        val languages = TranslateApi.identifyAll(nodes.map { it.literal.take(SAMPLE_CHARS) })
        val blocks = collectBlocks(document)
        val blockLanguages = TranslateApi.identifyAll(blocks.map { it.text })
        val blockByNode = HashMap<Text, String?>()
        blocks.forEachIndexed { index, block ->
            block.nodes.forEach { blockByNode[it] = blockLanguages.getOrNull(index) }
        }

        val htmlLanguages = TranslateApi.identifyAll(htmlBlocks.map { it.text })
        val htmlSegmentLanguages = TranslateApi.identifyAll(
            htmlBlocks.flatMap { block -> block.segments.map { it.core.take(SAMPLE_CHARS) } },
        )

        val dominant = (languages + blockLanguages + htmlLanguages).filterNotNull()
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: throw IllegalStateException("Could not detect the README language")

        // A very short node ("version.", a stray sentence) is often detected as undetermined,
        // so fall back to the language of the paragraph it belongs to. When that paragraph is
        // already in the target language but the node has a foreign script, use the document's
        // main language instead: "支付宝" inside an English sentence still needs translating.
        val groups = mutableListOf<Group>()
        nodes.forEachIndexed { index, node ->
            val own = languages.getOrNull(index)
            val block = blockByNode[node]
            val language = own ?: when {
                block != null && block != target -> block
                dominant != target && NON_LATIN.containsMatchIn(node.literal) -> dominant
                else -> block
            }
            val parts = splitLong(node.literal)
            groups += Group(parts, language) { replacements ->
                node.literal = parts.indices.joinToString(" ") { part ->
                    replacements[part] ?: parts[part]
                }
            }
        }

        var segmentIndex = 0
        htmlBlocks.forEachIndexed { blockIndex, block ->
            val blockLanguage = htmlLanguages.getOrNull(blockIndex)
            block.segments.forEach { segment ->
                val own = htmlSegmentLanguages.getOrNull(segmentIndex++)
                val language = own ?: when {
                    blockLanguage != null && blockLanguage != target -> blockLanguage
                    dominant != target && NON_LATIN.containsMatchIn(segment.core) -> dominant
                    else -> blockLanguage
                }
                val parts = splitLong(segment.core)
                groups += Group(parts, language) { replacements ->
                    val translated = parts.indices.joinToString(" ") { part ->
                        replacements[part] ?: parts[part]
                    }
                    block.tokens[segment.tokenIndex].raw =
                        segment.leading + translated + segment.trailing
                    block.node.literal = block.tokens.joinToString("") { it.raw }
                }
            }
        }

        val translatable = groups.filter { it.language != null && it.language != target }
        if (translatable.isEmpty()) return Result("", dominant, translated = false)

        val totalParts = translatable.sumOf { it.parts.size }
        var doneParts = 0
        // Each source language needs its own translator.
        for ((source, sourceGroups) in translatable.groupBy { it.language!! }) {
            val entries = sourceGroups.flatMap { group -> group.parts.indices.map { group to it } }
            val translated = TranslateApi.translateChunks(
                chunks = entries.map { (group, index) -> group.parts[index] },
                sourceLanguage = source,
                targetTag = target,
            ) { progress ->
                onProgress((doneParts + progress * entries.size) / totalParts)
            }
            translated.forEachIndexed { index, text ->
                val (group, part) = entries[index]
                val original = group.parts[part]
                // Keep brand names whose only change is capitalization, such as "LSPosed Repo".
                group.replacements[part] = if (text.equals(original, ignoreCase = true)) original else text
            }
            doneParts += entries.size
            onProgress(doneParts.toFloat() / totalParts)
        }

        groups.forEach { group ->
            if (group.replacements.isNotEmpty()) group.commit(group.replacements)
        }

        return Result(renderer.render(document), dominant, translated = true)
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

    /** Groups text nodes by the paragraph-like block they belong to, for context detection. */
    private fun collectBlocks(root: Node): List<Block> {
        val blocks = mutableListOf<Block>()
        fun walk(node: Node) {
            var child = node.firstChild
            while (child != null) {
                when (child) {
                    is Paragraph, is Heading, is TableCell -> {
                        val texts = mutableListOf<Text>()
                        collectTexts(child, texts)
                        if (texts.isNotEmpty()) blocks += Block(texts)
                    }
                    else -> walk(child)
                }
                child = child.next
            }
        }
        walk(root)
        return blocks
    }

    private fun collectTexts(node: Node, out: MutableList<Text>) {
        var child = node.firstChild
        while (child != null) {
            if (child is Text) out += child else collectTexts(child, out)
            child = child.next
        }
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

    private class Block(val nodes: List<Text>) {
        val text: String = nodes.joinToString(" ") { it.literal }.take(SAMPLE_CHARS)
    }

    private class HtmlBlockInfo(
        val node: HtmlBlock,
        val tokens: List<HtmlToken>,
        val segments: List<HtmlSegment>,
    ) {
        val text: String = segments.joinToString(" ") { it.core }.take(SAMPLE_CHARS)
    }

    private class HtmlToken(var raw: String, val isTag: Boolean)

    private class HtmlSegment(
        val tokenIndex: Int,
        val leading: String,
        val core: String,
        val trailing: String,
    )

    private class Group(
        val parts: List<String>,
        val language: String?,
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
