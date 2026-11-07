package com.rushi.modulesmanager.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModuleMarkerTest {

    @Test
    fun `java init list marks the archive`() {
        assertMarkers(tempApk("META-INF/xposed/java_init.list"))
    }

    @Test
    fun `module prop marks the archive`() {
        assertMarkers(tempApk("META-INF/xposed/module.prop"))
    }

    @Test
    fun `native init list marks the archive`() {
        assertMarkers(tempApk("META-INF/xposed/native_init.list"))
    }

    @Test
    fun `marker in a split apk marks the module`() {
        val base = tempApk("META-INF/androidx.core_core.version")
        val split = tempApk("META-INF/xposed/java_init.list")
        try {
            assertTrue(
                ModuleMarker.isModernModule(base.absolutePath, arrayOf(split.absolutePath)),
            )
        } finally {
            base.delete()
            split.delete()
        }
    }

    @Test
    fun `unrelated archive is not a module`() {
        val apk = tempApk("META-INF/androidx.core_core.version")
        try {
            assertFalse(ModuleMarker.isModernModule(apk.absolutePath, null))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `missing or blank path is not a module`() {
        assertFalse(ModuleMarker.isModernModule(null, null))
        assertFalse(ModuleMarker.isModernModule("  ", emptyArray()))
        assertFalse(
            ModuleMarker.isModernModule("/does/not/exist.apk", arrayOf("/also/missing.apk")),
        )
    }

    @Test
    fun `reads target api from module prop`() {
        val apk = tempApk("META-INF/xposed/module.prop")
        try {
            assertEquals(102, ModuleMarker.targetApiVersion(apk.absolutePath, null))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun `reads target api from a split apk`() {
        val base = tempApk("META-INF/androidx.core_core.version")
        val split = tempApk("META-INF/xposed/module.prop")
        try {
            assertEquals(
                102,
                ModuleMarker.targetApiVersion(base.absolutePath, arrayOf(split.absolutePath)),
            )
        } finally {
            base.delete()
            split.delete()
        }
    }

    @Test
    fun `legacy archive has no target api`() {
        val apk = tempApk("assets/xposed_init")
        try {
            assertNull(ModuleMarker.targetApiVersion(apk.absolutePath, null))
        } finally {
            apk.delete()
        }
    }

    private fun assertMarkers(apk: File) {
        try {
            assertTrue(ModuleMarker.isModernModule(apk.absolutePath, null))
        } finally {
            apk.delete()
        }
    }

    private fun tempApk(entry: String): File {
        val file = File.createTempFile("module", ".apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entry))
            zip.write("minApiVersion=101\ntargetApiVersion=102\n".toByteArray())
            zip.closeEntry()
        }
        return file
    }
}
