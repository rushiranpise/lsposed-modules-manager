package com.rushi.modulesmanager.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RepoModuleTest {

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

    private fun release(tag: String, date: String, prerelease: Boolean) = RepoRelease(
        name = tag,
        tagName = tag,
        url = "https://github.com/example/module/releases/tag/$tag",
        descriptionHtml = null,
        publishedAt = date,
        isPrerelease = prerelease,
        assets = emptyList(),
    )
}
