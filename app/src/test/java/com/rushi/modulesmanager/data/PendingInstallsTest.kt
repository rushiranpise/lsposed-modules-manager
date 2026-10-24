package com.rushi.modulesmanager.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInstallsTest {

    @Test
    fun `the expected version code or a newer one has landed`() {
        val pending = pending(versionCode = 42, versionName = "4.2")

        assertTrue(pending.landed(installed("4.2", 42)))
        assertTrue(pending.landed(installed("4.3", 43)))
        assertFalse(pending.landed(installed("4.1", 41)))
    }

    @Test
    fun `version names match with or without a v prefix`() {
        val pending = pending(versionCode = 0, versionName = "v1.0.1")

        assertTrue(pending.landed(installed("1.0.1", 0)))
        assertFalse(pending.landed(installed("1.0.0", 0)))
    }

    @Test
    fun `update time confirms an install when no version is known`() {
        val pending = PendingInstall(
            packageName = "com.example",
            label = "Example",
            versionCode = 0,
            versionName = null,
            startedAt = 1_000,
        )

        assertTrue(pending.landed(installed(null, 0, lastUpdateTime = 1_500)))
        assertFalse(pending.landed(installed(null, 0, lastUpdateTime = 500)))
        assertFalse(pending.landed(null))
    }

    private fun pending(versionCode: Long, versionName: String?) = PendingInstall(
        packageName = "com.example",
        label = "Example",
        versionCode = versionCode,
        versionName = versionName,
        startedAt = 1_000,
    )

    private fun installed(
        versionName: String?,
        versionCode: Long,
        lastUpdateTime: Long = 0,
    ) = InstalledPackageVersion(
        versionName = versionName,
        versionCode = versionCode,
        lastUpdateTime = lastUpdateTime,
    )
}
