package com.rushi.modulesmanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `keeps every repository the index lists for a package`() {
        val alternatives = RepoJson.parseSourceAlternatives(DUPLICATES)

        val repos = alternatives["com.example.dup"].orEmpty().map { it.repo }

        assertEquals(listOf("fork/two", "fork/one"), repos)
        assertFalse(repos.any { it.startsWith("Xposed-Modules-Repo/") })
        assertEquals(1, RepoJson.parseRepos(DUPLICATES).size)
    }

    private companion object {
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
