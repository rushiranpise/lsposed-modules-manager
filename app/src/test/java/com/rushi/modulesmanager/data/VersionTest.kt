package com.rushi.modulesmanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    @Test
    fun `parses version code and name tags`() {
        val version = ModuleVersion.parse("520-3.8.3.r520.9605640")

        assertEquals(520L, version?.code)
        assertEquals("3.8.3.r520.9605640", version?.name)
    }

    @Test
    fun `parses plain tags without a version code`() {
        val version = ModuleVersion.parse("v1.2.3")

        assertEquals(0L, version?.code)
        assertEquals("v1.2.3", version?.name)
    }

    @Test
    fun `strips a build hash from a plain release tag`() {
        val version = ModuleVersion.parse("1.6.3-ceca486e")!!

        assertEquals(0L, version.code)
        assertEquals("1.6.3", version.name)
        assertTrue(
            version.isUpdateOver(
                InstalledPackageVersion(versionName = "1.6.2", versionCode = 0),
                "2026-11-06T23:34:31Z",
            ),
        )
        assertFalse(
            version.isUpdateOver(
                InstalledPackageVersion(versionName = "1.6.3", versionCode = 0),
                "2026-11-06T23:34:31Z",
            ),
        )
    }

    @Test
    fun `a plain build-number tag matches the installed version code`() {
        val version = ModuleVersion.parse("v202")!!
        val installed = InstalledPackageVersion(
            versionName = "1.202",
            versionCode = 202,
            lastUpdateTime = java.time.Instant.parse("2026-11-07T08:44:39Z").toEpochMilli(),
        )

        assertFalse(version.isUpdateOver(installed, "2026-11-04T15:55:32Z"))
        assertFalse(version.isNewerThan(202, "1.202"))
        assertTrue(
            version.isUpdateOver(
                installed.copy(
                    lastUpdateTime = java.time.Instant
                        .parse("2026-11-01T00:00:00Z")
                        .toEpochMilli(),
                ),
                "2026-11-04T15:55:32Z",
            ),
        )
    }

    @Test
    fun `compares version codes and plain version names`() {
        assertTrue(ModuleVersion.parse("521-3.8.4")!!.isNewerThan(520, "3.8.3"))
        assertFalse(ModuleVersion.parse("520-3.8.3")!!.isNewerThan(520, "3.8.3"))

        assertTrue(ModuleVersion.parse("v1.3.0")!!.isNewerThan(0, "1.2.0"))
        assertFalse(ModuleVersion.parse("v1.2.0")!!.isNewerThan(0, "1.2.0"))
        assertTrue(ModuleVersion.parse("v1.2.1")!!.isNewerThan(0, "1.2"))
    }

    @Test
    fun `same-code releases compare by publish time`() {
        val daily = ModuleVersion.parse("8-260802")!!
        val live = ModuleVersion.parse("8-261101")!!

        assertTrue(live.isNewerThan(daily, "2026-08-02T18:17:38Z", "2026-11-01T13:24:42Z"))
        assertFalse(daily.isNewerThan(live, "2026-11-01T13:24:42Z", "2026-08-02T18:17:38Z"))
        assertTrue(live.isNewerThan(daily, null, null))
    }

    @Test
    fun `same-code re-upload counts as an update when it is newer than the install`() {
        val installed = InstalledPackageVersion(
            versionName = "260926",
            versionCode = 8,
            lastUpdateTime = java.time.Instant.parse("2026-09-26T17:43:33Z").toEpochMilli(),
        )
        val latest = ModuleVersion.parse("8-261101")!!

        assertTrue(latest.isUpdateOver(installed, "2026-11-01T13:24:42Z"))
        assertFalse(latest.isUpdateOver(installed, "2026-09-20T00:00:00Z"))
    }
}
