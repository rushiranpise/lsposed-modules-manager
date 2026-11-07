package com.rushi.modulesmanager.data

import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

/**
 * libxposed modules declare themselves with files under META-INF/xposed and nothing in
 * the manifest. LSPosed checks the base APK and every split, so a module whose marker
 * lives only in a split, or a native-only module using native_init.list, still counts.
 */
internal object ModuleMarker {
    private val MARKERS = listOf(
        "META-INF/xposed/java_init.list",
        "META-INF/xposed/native_init.list",
        "META-INF/xposed/module.prop",
        "assets/xposed_init",
    )

    fun isModernModule(sourceDir: String?, splitSourceDirs: Array<String>?): Boolean {
        return apkPaths(sourceDir, splitSourceDirs).any(::hasAnyMarker)
    }

    /** Target libxposed API level declared in module.prop, or null for a legacy module. */
    fun targetApiVersion(sourceDir: String?, splitSourceDirs: Array<String>?): Int? =
        apkPaths(sourceDir, splitSourceDirs).firstNotNullOfOrNull(::readTargetApi)

    private fun apkPaths(sourceDir: String?, splitSourceDirs: Array<String>?): List<String> =
        buildList {
            if (!sourceDir.isNullOrBlank()) add(sourceDir)
            splitSourceDirs?.forEach { if (it.isNotBlank()) add(it) }
        }

    private fun hasAnyMarker(apkPath: String): Boolean {
        if (!File(apkPath).isFile) return false
        return runCatching {
            ZipFile(apkPath).use { zip -> MARKERS.any { zip.getEntry(it) != null } }
        }.getOrDefault(false)
    }

    private fun readTargetApi(apkPath: String): Int? {
        if (!File(apkPath).isFile) return null
        return runCatching {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(MODULE_PROP) ?: return@use null
                val properties = Properties()
                zip.getInputStream(entry).use { properties.load(it) }
                properties.getProperty("targetApiVersion")
                    ?.trim()
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
            }
        }.getOrNull()
    }

    private const val MODULE_PROP = "META-INF/xposed/module.prop"
}
