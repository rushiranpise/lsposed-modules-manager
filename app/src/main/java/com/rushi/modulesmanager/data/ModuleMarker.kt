package com.rushi.modulesmanager.data

import java.io.File
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
        val apks = buildList {
            if (!sourceDir.isNullOrBlank()) add(sourceDir)
            splitSourceDirs?.forEach { if (it.isNotBlank()) add(it) }
        }
        return apks.any(::hasAnyMarker)
    }

    private fun hasAnyMarker(apkPath: String): Boolean {
        if (!File(apkPath).isFile) return false
        return runCatching {
            ZipFile(apkPath).use { zip -> MARKERS.any { zip.getEntry(it) != null } }
        }.getOrDefault(false)
    }
}
