package com.rushi.modulesmanager.data

import com.google.mlkit.nl.translate.TranslateLanguage
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Translates a Markdown README on-device. Only prose nodes are sent to the translator, so links,
 * images, code blocks and HTML blocks keep their exact syntax and still render correctly.
 */
object ReadmeTranslator {
    data class Result(val html: String, val sourceLanguage: String, val translated: Boolean)

    private const val MAX_PART = 3000
    private const val SAMPLE_CHARS = 1200

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

        val source = TranslateApi.identify(nodes.joinToString(" ") { it.literal }.take(SAMPLE_CHARS))
            ?: throw IllegalStateException("Could not detect the README language")
        if (source == target) return Result("", source, translated = false)

        val groups = nodes.map { node -> Group(node, splitLong(node.literal)) }
        val flat = groups.flatMap { group -> group.parts.indices.map { group to it } }
        val translated = TranslateApi.translateChunks(
            chunks = flat.map { (group, index) -> group.parts[index] },
            sourceLanguage = source,
            targetTag = target,
            onProgress = onProgress,
        )

        val byGroup = HashMap<Group, MutableMap<Int, String>>()
        translated.forEachIndexed { index, text ->
            val (group, part) = flat[index]
            byGroup.getOrPut(group) { HashMap() }[part] = text
        }
        groups.forEach { group ->
            val replacements = byGroup[group].orEmpty()
            group.node.literal = group.parts.indices.joinToString(" ") { index ->
                replacements[index] ?: group.parts[index]
            }
        }

        return Result(renderer.render(document), source, translated = true)
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

    private class Group(val node: Text, val parts: List<String>)

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
