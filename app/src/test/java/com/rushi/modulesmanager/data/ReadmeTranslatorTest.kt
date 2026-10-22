package com.rushi.modulesmanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadmeTranslatorTest {

    @Test
    fun `code, links and inline code are protected`() {
        val markdown = """
            # Title

            Install [the module](https://example.com/x) then run `adb shell`.

            ```bash
            adb install -r "my app.apk"
            ```
        """.trimIndent()

        val tokenized = ReadmeTranslator.tokenize(markdown)

        assertTrue(tokenized.text.contains("# Title"))
        assertFalse(tokenized.text.contains("https://example.com/x"))
        assertFalse(tokenized.text.contains("`adb shell`"))
        assertFalse(tokenized.text.contains("adb install"))
        assertEquals("$markdown\n", roundTrip(tokenized))
    }

    @Test
    fun `translating returns the exact original document`() {
        val markdown = "Line one with a [link](https://example.com).\n\n" +
            "```kotlin\nval x = 1 // ``` not a fence\n```\n\n" +
            "Closing fence with spaces:\n\n```  \nlast line\n```   \n\nAfter the fence `code`."

        val tokenized = ReadmeTranslator.tokenize(markdown)

        assertTrue(tokenized.text.contains("After the fence"))
        assertEquals("$markdown\n", roundTrip(tokenized))
    }

    @Test
    fun `long documents are split into chunk sized pieces`() {
        val markdown = (1..400).joinToString("\n\n") { "Paragraph $it with some words and a [link](https://example.com/$it)." }

        val tokenized = ReadmeTranslator.tokenize(markdown)
        val chunks = ReadmeTranslator.chunk(tokenized.text, 2800)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 2800 })
        assertEquals("$markdown\n", ReadmeTranslator.restore(chunks, chunks, tokenized.tokens))
    }

    @Test
    fun `a chunk that loses its placeholders is kept in its original form`() {
        val markdown = "Read [this](https://example.com) first."
        val tokenized = ReadmeTranslator.tokenize(markdown)
        val chunks = ReadmeTranslator.chunk(tokenized.text, 2800)
        val mangled = chunks.map { "übersetzt" }

        assertEquals("$markdown\n", ReadmeTranslator.restore(mangled, chunks, tokenized.tokens))
    }

    private fun roundTrip(tokenized: ReadmeTranslator.Tokenized): String {
        val chunks = ReadmeTranslator.chunk(tokenized.text, 2800)
        return ReadmeTranslator.restore(chunks, chunks, tokenized.tokens)
    }
}
