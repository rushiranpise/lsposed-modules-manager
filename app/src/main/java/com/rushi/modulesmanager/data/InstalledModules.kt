package com.rushi.modulesmanager.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import java.util.zip.ZipFile

data class InstalledModule(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val description: String?,
    val scope: List<String>,
    val icon: Drawable?,
    val hasLauncher: Boolean,
)

/** Version data for every installed package, used to match repository modules. */
data class InstalledPackageVersion(
    val versionName: String?,
    val versionCode: Long,
    /** PackageManager install/update time, used to notice same-code re-uploads. */
    val lastUpdateTime: Long = 0L,
)

data class InstalledModulesResult(
    val modules: List<InstalledModule>,
    val versions: Map<String, InstalledPackageVersion>,
)

class InstalledModuleScanner(private val context: Context) {
    private val pm get() = context.packageManager
    private val libxposedCache = HashMap<String, Boolean>()

    fun scan(): InstalledModulesResult {
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        val modules = ArrayList<InstalledModule>()
        val versions = HashMap<String, InstalledPackageVersion>(packages.size)
        for (info in packages) {
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            versions[info.packageName] = InstalledPackageVersion(
                versionName = info.versionName,
                versionCode = versionCode,
                lastUpdateTime = info.lastUpdateTime,
            )

            if (info.packageName == context.packageName) continue
            if (!isXposedModule(info)) continue
            modules += toInstalledModule(info)
        }
        return InstalledModulesResult(
            modules = modules.sortedBy { it.label.lowercase() },
            versions = versions,
        )
    }

    /**
     * Legacy modules declare themselves in the manifest. Modules built on the
     * libxposed API keep their metadata inside the APK instead, at
     * META-INF/xposed, so the manifest check alone misses them.
     */
    private fun isXposedModule(info: PackageInfo): Boolean {
        val app = info.applicationInfo ?: return false
        val meta = app.metaData
        if (meta?.getBoolean("xposedmodule", false) == true || meta?.containsKey("xposedminversion") == true) {
            return true
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        val key = "${info.packageName}:$versionCode:${info.lastUpdateTime}"
        return libxposedCache.getOrPut(key) { hasLibxposedMetadata(app.sourceDir) }
    }

    private fun hasLibxposedMetadata(sourceDir: String?): Boolean {
        if (sourceDir.isNullOrBlank()) return false
        return runCatching {
            ZipFile(sourceDir).use { zip ->
                zip.getEntry("META-INF/xposed/module.prop") != null ||
                    zip.getEntry("META-INF/xposed/java_init.list") != null ||
                    zip.getEntry("assets/xposed_init") != null
            }
        }.getOrDefault(false)
    }

    /** Basic app data for any installed package, including modules without Xposed metadata. */
    fun installedModule(packageName: String): InstalledModule? = try {
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
        toInstalledModule(info)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun toInstalledModule(info: PackageInfo): InstalledModule {
        val app = info.applicationInfo
        val meta = app?.metaData
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return InstalledModule(
            packageName = info.packageName,
            label = app?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
                ?: info.packageName,
            versionName = info.versionName,
            versionCode = versionCode,
            description = meta?.getString("xposeddescription"),
            scope = meta?.let { readScope(it) }.orEmpty(),
            icon = app?.let { runCatching { it.loadIcon(pm) }.getOrNull() },
            hasLauncher = pm.getLaunchIntentForPackage(info.packageName) != null,
        )
    }

    private fun readScope(meta: Bundle): List<String> = when (val value = meta.get("xposedscope")) {
        is Array<*> -> value.filterIsInstance<String>()
        is String -> value.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        is ArrayList<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }

    fun openModule(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }


}
