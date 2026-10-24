package com.rushi.modulesmanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `a remembered variant is found in a later release`() {
        val first = listOf(
            asset("OS2_HyperCeiler-2.10.166-20260402-release.apk"),
            asset("OS3_HyperCeiler-2.10.166-20260331-release.apk"),
        )
        val next = listOf(
            asset("OS3_HyperCeiler-3.0.0-20270101-release.apk"),
            asset("OS2_HyperCeiler-3.0.0-20270102-release.apk"),
        )

        val preference = ApkVariants.preference(first[0])
        val match = ApkVariants.match(
            preference,
            next,
            sdk = 35,
            deviceAbis = listOf("arm64-v8a"),
        )

        assertEquals(next[1], match)
    }

    @Test
    fun `traits recover the variant after a rename`() {
        val preference = ApkVariants.preference(asset("old-arm64-v8a.apk"))
        val next = listOf(
            asset("renamed-v2-x86_64.apk"),
            asset("renamed-v2-arm64-v8a.apk"),
        )

        val match = ApkVariants.match(
            preference,
            next,
            sdk = 35,
            deviceAbis = listOf("arm64-v8a"),
        )

        assertEquals(next[1], match)
    }

    @Test
    fun `an ambiguous release is not preselected`() {
        val preference = ApkVariants.preference(asset("app.apk"))
        val next = listOf(asset("build.apk"), asset("other.apk"))

        assertNull(ApkVariants.match(preference, next, sdk = 35, deviceAbis = listOf("arm64-v8a")))
    }

    @Test
    fun `a preference round trips through storage`() {
        val preference = ApkVariants.preference(asset("app-arm64-v8a.apk"))

        assertEquals(preference, ApkVariants.decode(ApkVariants.encode(preference)))
    }

    private fun asset(name: String) = RepoAsset(
        name = name,
        contentType = "application/vnd.android.package-archive",
        downloadUrl = "https://example.com/$name",
        downloadCount = 0,
        size = 1_000_000,
    )
}
