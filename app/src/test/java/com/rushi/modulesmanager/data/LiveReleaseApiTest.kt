package com.rushi.modulesmanager.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveReleaseApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api() = LiveReleaseApi(
        client = RepoApi.createClient(),
        apiBase = server.url("/").toString().trimEnd('/'),
        webBase = server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `parses the latest release and its apk asset`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "521-3.8.4",
                  "name": "3.8.4",
                  "published_at": "2026-02-03T04:05:06Z",
                  "html_url": "https://github.com/example/module/releases/tag/521-3.8.4",
                  "prerelease": false,
                  "body": "Changes",
                  "assets": [
                    {
                      "name": "example.apk",
                      "content_type": "application/vnd.android.package-archive",
                      "browser_download_url": "https://github.com/example/module/releases/download/521-3.8.4/example.apk",
                      "download_count": 9,
                      "size": 1234
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val release = api().fetchLatest("example/module")

        assertEquals("521-3.8.4", release?.tag)
        assertEquals("3.8.4", release?.name)
        assertEquals(1, release?.assets?.size)
        assertEquals("example.apk", release?.assets?.single()?.name)
        assertEquals(9, release?.assets?.single()?.downloadCount)
        assertEquals("/repos/example/module/releases/latest", server.takeRequest().path)
    }

    @Test
    fun `reads every published release including pre-releases`() = runBlocking {
        server.enqueue(MockResponse().setBody(RELEASES))

        val releases = api().fetchAll("example/module")

        assertEquals(2, releases?.size)
        assertEquals("1.0", releases?.get(0)?.tag)
        assertTrue(releases?.get(0)?.isPrerelease == true)
        assertEquals("0.9", releases?.get(1)?.tag)
        assertFalse(releases?.get(1)?.isPrerelease == true)
        assertEquals(1, releases?.get(1)?.assets?.size)
        assertEquals("module-0.9.apk", releases?.get(1)?.assets?.single()?.name)
        assertEquals(
            "/repos/example/module/releases?per_page=100",
            server.takeRequest().path,
        )
    }

    @Test
    fun `falls back to the releases feed when the api is rate limited`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setBody(ATOM_FEED))
        server.enqueue(MockResponse().setBody(ATOM_ASSETS))

        val releases = api().fetchAll("example/module")

        assertEquals(1, releases?.size)
        val release = releases?.single()
        assertEquals("1.0", release?.tag)
        assertEquals("<p>Notes</p>", release?.description)
        assertEquals("module-1.0.apk", release?.assets?.single()?.name)
        assertTrue(
            release?.assets?.single()?.downloadUrl?.endsWith(
                "/example/module/releases/download/1.0/module-1.0.apk",
            ) == true,
        )
        assertEquals("/repos/example/module/releases?per_page=100", server.takeRequest().path)
        assertEquals("/example/module/releases.atom", server.takeRequest().path)
        assertEquals(
            "/example/module/releases/expanded_assets/1.0",
            server.takeRequest().path,
        )
    }

    @Test
    fun `falls back to the feed for the latest release`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setBody(ATOM_FEED))
        server.enqueue(MockResponse().setBody(ATOM_ASSETS))

        val release = api().fetchLatest("example/module")

        assertEquals("1.0", release?.tag)
        assertEquals("module-1.0.apk", release?.assets?.single()?.name)
        assertEquals("/repos/example/module/releases/latest", server.takeRequest().path)
        assertEquals("/example/module/releases.atom", server.takeRequest().path)
        assertEquals(
            "/example/module/releases/expanded_assets/1.0",
            server.takeRequest().path,
        )
    }

    private companion object {
        val ATOM_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Release notes from module</title>
              <entry>
                <title>1.0</title>
                <updated>2026-02-01T00:00:00Z</updated>
                <link rel="alternate" type="text/html" href="https://github.com/example/module/releases/tag/1.0"/>
                <content type="html">&lt;p&gt;Notes&lt;/p&gt;</content>
              </entry>
            </feed>
        """.trimIndent()

        val ATOM_ASSETS = """
            <ul>
              <li><a href="/example/module/releases/download/1.0/module-1.0.apk">module-1.0.apk</a></li>
              <li><a href="/example/module/releases/download/1.0/checksums.txt">checksums.txt</a></li>
            </ul>
        """.trimIndent()

        val RELEASES = """
            [
              {
                "tag_name": "1.0",
                "name": "1.0 beta",
                "published_at": "2026-02-01T00:00:00Z",
                "html_url": "https://github.com/example/module/releases/tag/1.0",
                "prerelease": true,
                "draft": false,
                "body": "Beta notes",
                "assets": []
              },
              {
                "tag_name": "0.9",
                "name": "0.9",
                "published_at": "2026-01-01T00:00:00Z",
                "html_url": "https://github.com/example/module/releases/tag/0.9",
                "prerelease": false,
                "draft": false,
                "body": null,
                "assets": [
                  {
                    "name": "module-0.9.apk",
                    "content_type": "application/vnd.android.package-archive",
                    "browser_download_url": "https://github.com/example/module/releases/download/0.9/module-0.9.apk",
                    "download_count": 5,
                    "size": 1024
                  },
                  {
                    "name": "checksum.txt",
                    "content_type": "text/plain",
                    "browser_download_url": "https://github.com/example/module/releases/download/0.9/checksum.txt",
                    "download_count": 1,
                    "size": 32
                  }
                ]
              },
              {
                "tag_name": "draft-release",
                "name": "Draft",
                "prerelease": false,
                "draft": true,
                "assets": []
              }
            ]
        """.trimIndent()
    }
}
