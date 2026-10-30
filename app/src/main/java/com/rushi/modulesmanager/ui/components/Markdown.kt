package com.rushi.modulesmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.rushi.modulesmanager.data.stripHtml
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.renderer.text.TextContentRenderer

/**
 * Release bodies arrive as raw Markdown from the GitHub API. Rendering the
 * blocks keeps headings, lists and emphasis readable instead of showing the
 * syntax characters.
 */
private val markdownParser = Parser.builder().build()
private val markdownTextRenderer = TextContentRenderer.builder().build()

/** Markdown with its syntax markers removed, for collapsed previews. */
fun markdownPlainText(markdown: String): String =
    markdownTextRenderer.render(markdownParser.parse(markdown)).trim()

private data class MarkdownColors(
    val text: Color,
    val heading: Color,
    val link: Color,
    val codeBackground: Color,
    val rule: Color,
)

@Composable
fun MarkdownBlocks(markdown: String, modifier: Modifier = Modifier) {
    val document = remember(markdown) { markdownParser.parse(markdown) }
    val colors = MarkdownColors(
        text = MaterialTheme.colorScheme.onSurfaceVariant,
        heading = MaterialTheme.colorScheme.onSurface,
        link = MaterialTheme.colorScheme.primary,
        codeBackground = MaterialTheme.colorScheme.surfaceVariant,
        rule = MaterialTheme.colorScheme.outlineVariant,
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        var block = document.firstChild
        while (block != null) {
            MarkdownBlock(block, colors)
            block = block.next
        }
    }
}

@Composable
private fun MarkdownBlock(node: Node, colors: MarkdownColors) {
    when (node) {
        is Paragraph -> InlineMarkdown(
            node = node,
            style = MaterialTheme.typography.bodySmall,
            colors = colors,
        )
        is Heading -> InlineMarkdown(
            node = node,
            style = when (node.level) {
                1 -> MaterialTheme.typography.titleMedium
                2 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.labelLarge
            },
            colors = colors,
            color = colors.heading,
        )
        is BulletList -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            var item = node.firstChild
            while (item != null) {
                if (item is ListItem) ListEntry(item, "\u2022", colors)
                item = item.next
            }
        }
        is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            var item = node.firstChild
            var index = 0
            while (item != null) {
                if (item is ListItem) ListEntry(item, "${node.startNumber + index}.", colors)
                item = item.next
                index++
            }
        }
        is FencedCodeBlock -> CodeBlock(node.literal, colors)
        is IndentedCodeBlock -> CodeBlock(node.literal, colors)
        is BlockQuote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(colors.rule, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                var child = node.firstChild
                while (child != null) {
                    MarkdownBlock(child, colors)
                    child = child.next
                }
            }
        }
        is ThematicBreak -> HorizontalDivider(color = colors.rule)
        is HtmlBlock -> {
            val text = stripHtml(node.literal)?.trim()
            if (!text.isNullOrBlank()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                )
            }
        }
        is TableBlock -> {
            val text = buildString { collectText(node, this) }.trim()
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                )
            }
        }
        else -> {
            var child = node.firstChild
            while (child != null) {
                MarkdownBlock(child, colors)
                child = child.next
            }
        }
    }
}

@Composable
private fun ListEntry(item: ListItem, marker: String, colors: MarkdownColors) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodySmall,
            color = colors.text,
            modifier = Modifier.width(18.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            var child = item.firstChild
            while (child != null) {
                MarkdownBlock(child, colors)
                child = child.next
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String?, colors: MarkdownColors) {
    if (code.isNullOrBlank()) return
    Surface(
        color = colors.codeBackground,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = code.trimEnd(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = colors.text,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun InlineMarkdown(
    node: Node,
    style: TextStyle,
    colors: MarkdownColors,
    color: Color = colors.text,
) {
    val annotated = remember(node, colors) {
        buildAnnotatedString { appendInline(node, colors) }
    }
    if (annotated.text.isNotBlank()) {
        Text(text = annotated, style = style, color = color)
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(
    node: Node,
    colors: MarkdownColors,
) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is MarkdownText -> append(child.literal)
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                appendInline(child, colors)
            }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInline(child, colors)
            }
            is Code -> {
                val literal = child.literal
                withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground),
                ) {
                    append(literal)
                }
            }
            is Link -> withStyle(
                SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline),
            ) {
                appendInline(child, colors)
            }
            is SoftLineBreak -> append(' ')
            is HardLineBreak -> append('\n')
            is HtmlInline -> Unit
            else -> appendInline(child, colors)
        }
        child = child.next
    }
}

private fun collectText(node: Node, builder: StringBuilder) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is MarkdownText -> builder.append(child.literal).append(' ')
            is SoftLineBreak, is HardLineBreak -> builder.append('\n')
            else -> collectText(child, builder)
        }
        child = child.next
    }
}
