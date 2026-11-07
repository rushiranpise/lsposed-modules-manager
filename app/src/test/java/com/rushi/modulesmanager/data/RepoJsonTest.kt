package com.rushi.modulesmanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoJsonTest {

    @Test
    fun `finds packages that have no release in the daily index`() {
        val packages = RepoJson.packagesWithoutReleases(INDEX)

        assertTrue(packages.contains("com.example.missing"))
        assertFalse(packages.contains("com.example.ready"))
    }

    @Test
    fun `keeps every repository the index lists for a package including mirrors`() {
        val alternatives = RepoJson.parseSourceAlternatives(DUPLICATES)

        val repos = alternatives["com.example.dup"].orEmpty().map { it.repo }

        assertEquals(3, repos.size)
        assertTrue(repos.containsAll(listOf("fork/two", "fork/one", "Xposed-Modules-Repo/com.example.dup")))
        assertEquals(1, RepoJson.parseRepos(DUPLICATES).size)
    }

    @Test
    fun `counts a source repository and its mirror as two sources`() {
        val alternatives = RepoJson.parseSourceAlternatives(SOURCE_AND_MIRROR)

        val repos = alternatives["com.wmods.wppenhacer"].orEmpty().map { it.repo }

        assertEquals(2, repos.size)
        assertTrue(repos.contains("Dev4Mod/WaEnhancer"))
        assertTrue(repos.contains("Xposed-Modules-Repo/com.wmods.wppenhacer"))
    }

    @Test
    fun `reads target libxposed api from index metadata`() {
        val modules = RepoJson.parseRepos(API_METADATA).associateBy { it.packageName }

        assertEquals(102, modules["com.example.modern"]?.apiVersion)
        assertNull(modules["com.example.legacy"]?.apiVersion)
    }

    private companion object {
        val SOURCE_AND_MIRROR = """
            [
              {
                "full_name": "Dev4Mod/WaEnhancer",
                "html_url": "https://github.com/Dev4Mod/WaEnhancer",
                "org_repo": "Xposed-Modules-Repo/com.wmods.wppenhacer",
                "package": "com.wmods.wppenhacer",
                "release": {
                  "tag": "1.6.3-ceca486e",
                  "published_at": "2026-11-06T23:34:31Z",
                  "html_url": "https://github.com/Dev4Mod/WaEnhancer/releases/tag/1.6.3-ceca486e",
                  "apk_url": "https://github.com/Dev4Mod/WaEnhancer/releases/download/1.6.3-ceca486e/WaEnhancer-1.6.3.CECA486E.apk"
                }
              }
            ]
        """.trimIndent()

        val API_METADATA = """
            [
              {
                "full_name": "example/modern",
                "package": "com.example.modern",
                "release": {
                  "tag": "1-1.0",
                  "apk_url": "https://example.com/modern.apk"
                },
                "metadata": { "targetApi": 102 }
              },
              {
                "full_name": "example/legacy",
                "package": "com.example.legacy",
                "release": {
                  "tag": "1-1.0",
                  "apk_url": "https://example.com/legacy.apk"
                },
                "metadata": { "name": "Legacy" }
              }
            ]
        """.trimIndent()

        val DUPLICATES = """
            [
              {
                "full_name": "fork/one",
                "package": "com.example.dup",
                "source_url": "https://github.com/fork/one",
                "release": {
                  "tag": "1-1.0",
                  "published_at": "2026-01-01T00:00:00Z",
                  "apk_url": "https://github.com/fork/one/releases/download/1-1.0/one.apk"
                }
              },
              {
                "full_name": "fork/two",
                "package": "com.example.dup",
                "release": {
                  "tag": "2-2.0",
                  "published_at": "2026-02-01T00:00:00Z",
                  "apk_url": "https://github.com/fork/two/releases/download/2-2.0/two.apk"
                }
              },
              {
                "full_name": "Xposed-Modules-Repo/com.example.dup",
                "package": "com.example.dup",
                "release": {
                  "tag": "3-3.0",
                  "published_at": "2026-03-01T00:00:00Z",
                  "apk_url": "https://example.com/mirror.apk"
                }
              }
            ]
        """.trimIndent()

        val INDEX = """
            [
              {
                "full_name": "example/ready",
                "package": "com.example.ready",
                "release": {
                  "tag": "1-1.0",
                  "apk_url": "https://github.com/example/ready/releases/download/1-1.0/ready.apk"
                }
              },
              {
                "full_name": "example/missing",
                "org_repo": "Xposed-Modules-Repo/com.example.missing",
                "release": null
              }
            ]
        """.trimIndent()
    }
}
