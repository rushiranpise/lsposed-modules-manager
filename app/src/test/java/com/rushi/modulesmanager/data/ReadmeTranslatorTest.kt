package com.rushi.modulesmanager.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadmeTranslatorTest {

    @Test
    fun `only prose is collected from the markdown tree`() {
        val markdown = """
            # Title

            Install [the module](https://example.com/x) then run `adb shell`.

            ```bash
            adb install -r "my app.apk"
            ```

            <p align="center">centered html</p>
        """.trimIndent()

        val prose = ReadmeTranslator.proseOf(markdown)

        assertTrue(prose.contains("Title"))
        assertTrue(prose.contains("the module"))
        assertTrue(prose.any { it.contains("Install") })
        assertTrue(prose.any { it.contains("then run") })
        assertFalse(prose.any { it.contains("https://") })
        assertFalse(prose.any { it.contains("adb install") })
        assertFalse(prose.any { it.contains("adb shell") })
        assertFalse(prose.any { it.contains("centered html") })
    }

    @Test
    fun `images and tables keep their text but not their targets`() {
        val markdown = """
            ![badge](https://example.com/badge.svg)

            | Name | Value |
            | --- | --- |
            | Alpha | 1 |
        """.trimIndent()

        val prose = ReadmeTranslator.proseOf(markdown)

        assertTrue(prose.contains("badge"))
        assertTrue(prose.contains("Name"))
        assertTrue(prose.contains("Alpha"))
        assertFalse(prose.any { it.contains("https://") })
    }
}
