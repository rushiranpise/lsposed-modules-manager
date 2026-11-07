package com.rushi.modulesmanager.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RepoModuleTest {

    @Test
    fun `repository slugs become readable module names`() {
        assertEquals("Samsung Feature Extension", prettifyRepoName("samsung-feature-extension"))
        assertEquals("LSPilot", prettifyRepoName("LSPilot"))
        assertEquals("My Module", prettifyRepoName("my_module"))
    }

    @Test
    fun `the release list combines history with the index releases, newest first`() {
        val module = RepoModule(
            packageName = "com.example.module",
            displayName = "Example",
            summary = null,
            url = null,
            homepageUrl = null,
            sourceUrl = null,
            latestRelease = "3.0",
            latestReleaseTime = "2026-03-01T00:00:00Z",
            latestBetaRelease = null,
            latestBetaReleaseTime = null,
            latestSnapshotRelease = null,
            latestSnapshotReleaseTime = null,
            releases = listOf(release("3.0", "2026-03-01T00:00:00Z", prerelease = false)),
            betaReleases = emptyList(),
            snapshotReleases = emptyList(),
            readme = null,
            readmeHtml = null,
            collaborators = emptyList(),
            scope = emptyList(),
            hide = false,
            updatedAt = null,
            stargazerCount = 0,
            releaseHistory = listOf(
                release("3.1-beta", "2026-03-10T00:00:00Z", prerelease = true),
                release("3.0", "2026-03-01T00:00:00Z", prerelease = false),
                release("2.0", "2026-02-01T00:00:00Z", prerelease = false),
            ),
        )

        val releases = module.allReleases()

        assertEquals(listOf("3.1-beta", "3.0", "2.0"), releases.map { it.tagName })
        assertEquals(true, releases.first().isPrerelease)
    }

    @Test
    fun `live release lookup prefers the source repository over the mirror`() {
        val module = module(
            sourceUrl = "https://github.com/Dev4Mod/WaEnhancer",
            repoFullName = "Xposed-Modules-Repo/com.wmods.wppenhacer",
        )

        assertEquals(
            listOf(
                "Dev4Mod/WaEnhancer",
                "Xposed-Modules-Repo/com.wmods.wppenhacer",
            ),
            module.liveReleaseRepos(),
        )
    }

    @Test
    fun `a newer release in the history replaces the index release`() {
        val indexed = release("1.6.1-f4a8da1e", "2026-10-18T17:53:19Z", prerelease = false)
        val current = release("1.6.3-ceca486e", "2026-11-06T23:34:31Z", prerelease = false)
        val module = module(
            latestRelease = indexed.tagName,
            latestReleaseTime = indexed.publishedAt,
            releases = listOf(indexed),
        )

        val updated = module.withReleaseHistory(listOf(current))

        assertEquals("1.6.3-ceca486e", updated.latestRelease)
        assertEquals("2026-11-06T23:34:31Z", updated.latestReleaseTime)
        assertEquals(listOf("1.6.3-ceca486e", "1.6.1-f4a8da1e"), updated.releases.map { it.tagName })
    }

    @Test
    fun `a hash-suffixed tag matches the plain installed version`() {
        val release = release("1.6.3-ceca486e", "2026-11-06T23:34:31Z", prerelease = false)
        val version = ModuleVersion.parse("1.6.3")!!

        assertEquals(true, release.matchesVersion(version))
    }

    private fun module(
        latestRelease: String? = null,
        latestReleaseTime: String? = null,
        releases: List<RepoRelease> = emptyList(),
        repoFullName: String? = null,
        sourceUrl: String? = null,
    ) = RepoModule(
        packageName = "com.wmods.wppenhacer",
        displayName = "Wa Enhancer",
        summary = null,
        url = null,
        homepageUrl = null,
        sourceUrl = sourceUrl,
        latestRelease = latestRelease,
        latestReleaseTime = latestReleaseTime,
        latestBetaRelease = null,
        latestBetaReleaseTime = null,
        latestSnapshotRelease = null,
        latestSnapshotReleaseTime = null,
        releases = releases,
        betaReleases = emptyList(),
        snapshotReleases = emptyList(),
        readme = null,
        readmeHtml = null,
        collaborators = emptyList(),
        scope = emptyList(),
        hide = false,
        updatedAt = null,
        stargazerCount = 0,
        repoFullName = repoFullName,
    )

    private fun release(tag: String, date: String, prerelease: Boolean) = RepoRelease(
        name = tag,
        tagName = tag,
        url = "https://github.com/example/module/releases/tag/$tag",
        descriptionHtml = null,
        publishedAt = date,
        isPrerelease = prerelease,
        assets = listOf(
            RepoAsset(
                name = "$tag.apk",
                contentType = "application/vnd.android.package-archive",
                downloadUrl = "https://github.com/example/module/releases/download/$tag/module.apk",
                downloadCount = 0,
                size = 1L,
            ),
        ),
    )
}
