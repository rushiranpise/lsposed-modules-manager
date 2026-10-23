package com.rushi.modulesmanager.data

import com.google.mlkit.nl.translate.TranslateLanguage
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Translates a Markdown README on-device. Only prose nodes are sent to the translator, so links,
 * images, code blocks and HTML blocks keep their exact syntax and still render correctly.
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
        if (nodes.isEmpty()) {
            throw IllegalStateException("This README has no text that can be translated")
        }

        val languages = TranslateApi.identifyAll(nodes.map { it.literal.take(SAMPLE_CHARS) })
        val blocks = collectBlocks(document)
        val blockLanguages = TranslateApi.identifyAll(blocks.map { it.text })
        val blockByNode = HashMap<Text, String?>()
        blocks.forEachIndexed { index, block ->
            block.nodes.forEach { blockByNode[it] = blockLanguages.getOrNull(index) }
        }

        val dominant = (languages + blockLanguages).filterNotNull()
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: throw IllegalStateException("Could not detect the README language")

        // A very short node ("version.", a stray sentence) is often detected as undetermined,
        // so fall back to the language of the paragraph it belongs to. When that paragraph is
        // already in the target language but the node has a foreign script, use the document's
        // main language instead: "支付宝" inside an English sentence still needs translating.
        val groups = nodes.mapIndexed { index, node ->
            val own = languages.getOrNull(index)
            val block = blockByNode[node]
            val language = own ?: when {
                block != null && block != target -> block
                dominant != target && NON_LATIN.containsMatchIn(node.literal) -> dominant
                else -> block
            }
            Group(node, splitLong(node.literal), language)
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
            if (group.replacements.isEmpty()) return@forEach
            group.node.literal = group.parts.indices.joinToString(" ") { index ->
                group.replacements[index] ?: group.parts[index]
            }
        }

        return Result(renderer.render(document), dominant, translated = true)
    }

    /** Plain prose of the document, for tests and nothing else. */
    internal fun proseOf(markdown: String): List<String> =
        collectTextNodes(parser.parse(markdown)).map { it.literal }

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

    private class Block(val nodes: List<Text>) {
        val text: String = nodes.joinToString(" ") { it.literal }.take(SAMPLE_CHARS)
    }

    private class Group(
        val node: Text,
        val parts: List<String>,
        val language: String?,
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
