package com.rushi.modulesmanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkVariantsTest {

    @Test
    fun `reads abi, vendor os and android version from the name`() {
        val asset = asset("HyperCeiler-HyperOS3-A16-arm64.apk")

        val hints = ApkVariants.hints(asset)

        assertTrue(hints.joinToString().contains("arm64"))
        assertTrue(hints.joinToString().contains("HyperOS 3"))
        assertTrue(hints.joinToString().contains("Android 16"))
    }

    @Test
    fun `api102 is a libxposed level, not a minimum sdk`() {
        val trait = ApkVariants.traits(asset("module-api102-arm64-v8a.apk"))

        assertEquals(102, trait.libxposedApi)
        assertNull(trait.minSdk)
    }

    @Test
    fun `a libxposed variant is still usable`() {
        val assets = listOf(
            asset("module-api102-arm64-v8a.apk"),
            asset("module-api100-armeabi-v7a.apk"),
        )

        val choices = ApkVariants.bestChoices(
            assets,
            sdk = 35,
            deviceAbis = listOf("arm64-v8a"),
        )

        assertEquals("module-api102-arm64-v8a.apk", choices.single().name)
    }

    private fun asset(name: String) = RepoAsset(
        name = name,
        contentType = "application/vnd.android.package-archive",
        downloadUrl = "https://example.com/$name",
        downloadCount = 0,
        size = 1024,
    )
}
