package com.rushi.modulesmanager.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSaveCenterTest {

    private fun request(label: String = "Wren") = ApkSaveCenter.Request(
        packageName = "com.example.wren",
        label = label,
        downloadUrl = "https://example.test/wren.apk",
        originalDownloadUrl = null,
        size = 1024L,
        exportName = "Wren-0123456789abcdef.apk",
        destination = File("unused.apk"),
    )

    @Test
    fun secondSaveIsRefusedUntilTheFirstFinishes() {
        val center = ApkSaveCenter()

        assertTrue(center.begin(request("First")))
        assertFalse(center.begin(request("Second")))
        assertEquals("First", center.state.value?.label)

        center.finish("Saved")

        assertNull(center.state.value)
        assertTrue(center.begin(request("Second")))
    }

    @Test
    fun progressUpdatesTheClaimedSave() {
        val center = ApkSaveCenter()
        center.begin(request())

        center.progress(512L, 1024L)

        val state = center.state.value
        assertEquals(512L, state?.downloaded)
        assertEquals(1024L, state?.total)
        assertEquals(0.5f, state?.fraction ?: -1f, 0.0001f)
    }

    @Test
    fun cancelReleasesTheSaveSlot() {
        val center = ApkSaveCenter()
        center.begin(request())

        center.cancel()

        assertNull(center.state.value)
        assertTrue(center.begin(request("Retry")))
    }
}
