package com.rushi.modulesmanager.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RepoApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun source() = RepoSource(
        id = "test",
        name = "Test",
        bases = listOf(server.url("/repos.json").toString()),
    )

    private fun api() = RepoApi(RepoApi.createClient())

    @Test
    fun `parses the daily repos json and derives packages and releases`() = runBlocking {
        server.enqueue(MockResponse().setBody(REPOS_JSON))

        val result = api().fetchModules(source())

        assertEquals(2, result.modules.size)
        val module = result.modules.first { it.packageName == "com.example.module" }
        assertEquals("Module", module.displayName)
        assertEquals("Example summary", module.summary)
        assertEquals("42-4.2", module.latestRelease)
        assertEquals("https://github.com/example/module/releases/download/42-4.2/example.apk",
            module.releaseFor(UpdateChannel.STABLE)?.apkAssets?.single()?.downloadUrl)
        assertEquals(1, module.scope.size)
        assertEquals(12, module.stargazerCount)
        assertEquals(3, module.forkCount)
        assertEquals("2026-01-03T03:04:05Z", module.addedAt)

        val inferred = result.modules.first { it.packageName == "com.inferred.module" }
        assertEquals("Inferred", inferred.displayName)
        assertEquals("v1.0", inferred.latestRelease)
        assertEquals("https://github.com/example/inferred/releases/download/v1.0/inferred.apk",
            inferred.releaseFor(UpdateChannel.STABLE)?.apkAssets?.single()?.downloadUrl)
    }

    @Test
    fun `download falls back to the original asset URL`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setBody("apk-bytes"))
        val directory = Files.createTempDirectory("modules-manager-download").toFile()
        val destination = File(directory, "example.apk")
        val signed = server.url("/signed.apk").toString()
        val original = server.url("/original.apk").toString()

        val file = api().download(listOf(signed, original), destination) { _, _ -> }

        assertEquals("apk-bytes", file.readText())
        assertEquals(2, server.requestCount)
        assertEquals("/signed.apk", server.takeRequest().path)
        assertEquals("/original.apk", server.takeRequest().path)
        assertTrue(directory.deleteRecursively())
    }

    private companion object {
        val REPOS_JSON = """
            [
              {
                "full_name": "example/module",
                "org_repo": "Xposed-Modules-Repo/com.example.module",
                "package": "com.example.module",
                "description": "Example",
                "summary": "Example summary",
                "homepage": "https://example.com",
                "source_url": "https://github.com/example/module",
                "pushed_at": "2026-01-03T03:04:05Z",
                "forks_count": 3,
                "added_at": "2026-01-03T03:04:05Z",
                "stargazers_count": 12,
                "scope": ["com.target.app"],
                "owner": {"login": "example"},
                "release": {
                  "tag": "42-4.2",
                  "name": "4.2",
                  "published_at": "2026-01-02T03:04:05Z",
                  "html_url": "https://github.com/example/module/releases/tag/42-4.2",
                  "apk_url": "https://github.com/example/module/releases/download/42-4.2/example.apk",
                  "prerelease": false
                }
              },
              {
                "full_name": "example/inferred",
                "org_repo": "Xposed-Modules-Repo/com.inferred.module",
                "description": "Inferred",
                "summary": "A module without a package field",
                "release": {
                  "tag": "v1.0",
                  "name": "1.0",
                  "published_at": "2026-01-01T03:04:05Z",
                  "html_url": "https://github.com/example/inferred/releases/tag/v1.0",
                  "apk_url": "https://github.com/example/inferred/releases/download/v1.0/inferred.apk",
                  "prerelease": false
                }
              }
            ]
        """.trimIndent()
    }
}
