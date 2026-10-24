package com.rushi.modulesmanager.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkVariantsTest {

    @Test
    fun `equal variants ask for a choice`() {
        val assets = listOf(asset("module.apk"), asset("module-alt.apk"))

        val choices = ApkVariants.bestChoices(assets, sdk = 35, deviceAbis = listOf("arm64-v8a"))

        assertEquals(2, choices.size)
        assertEquals(assets.toSet(), choices.toSet())
    }

    @Test
    fun `a native abi wins without asking`() {
        val arm = asset("module-arm64-v8a.apk")
        val x86 = asset("module-x86_64.apk")

        val choices = ApkVariants.bestChoices(
            listOf(x86, arm),
            sdk = 35,
            deviceAbis = listOf("arm64-v8a"),
        )

        assertEquals(listOf(arm), choices)
        assertEquals(
            arm,
            ApkVariants.pick(listOf(x86, arm), sdk = 35, deviceAbis = listOf("arm64-v8a")),
        )
    }

    @Test
    fun `a build for a newer android is not a choice`() {
        val high = asset("module-minSdk99-arm64-v8a.apk")
        val normal = asset("module-minSdk23-arm64-v8a.apk")

        val choices = ApkVariants.bestChoices(
            listOf(high, normal),
            sdk = 35,
            deviceAbis = listOf("arm64-v8a"),
        )

        assertEquals(listOf(normal), choices)
    }

    private fun asset(name: String) = RepoAsset(
        name = name,
        contentType = "application/vnd.android.package-archive",
        downloadUrl = "https://example.com/$name",
        downloadCount = 0,
        size = 1_000_000,
    )
}
