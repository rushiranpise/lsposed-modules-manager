package com.rushi.modulesmanager.data

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkExportNamesTest {

    private fun asset(
        name: String,
        url: String = "https://github.com/acme/Wren/releases/download/v1/$name",
    ) = RepoAsset(
        name = name,
        contentType = "application/vnd.android.package-archive",
        downloadUrl = url,
        downloadCount = 0,
        size = 1_234L,
    )

    @Test
    fun genericNameUsesProjectNameAndIdentityHash() {
        val name = exportedApkName(asset("app-release.apk"))
        assertTrue(name.startsWith("Wren-"))
        assertTrue(Regex("-[0-9a-f]{16}\\.apk$").containsMatchIn(name))
    }

    @Test
    fun specificNameStaysRecognisable() {
        val name = exportedApkName(asset("Wren-1.2.apk"))
        assertTrue(name.startsWith("Wren-1.2-"))
        assertTrue(name.endsWith(".apk"))
    }

    @Test
    fun identityChangesWithTheDownloadUrl() {
        val first = exportedApkName(asset("module.apk", "https://example.test/a/module.apk"))
        val second = exportedApkName(asset("module.apk", "https://example.test/b/module.apk"))
        assertNotEquals(first, second)
    }
}
