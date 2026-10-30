package com.rushi.modulesmanager.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
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
        val packages = installedPackages()
        val modules = ArrayList<InstalledModule>()
        val versions = HashMap<String, InstalledPackageVersion>(packages.size)
        for (base in packages) {
            val versionCode = versionCodeOf(base)
            versions[base.packageName] = InstalledPackageVersion(
                versionName = base.versionName,
                versionCode = versionCode,
                lastUpdateTime = base.lastUpdateTime,
            )

            if (base.packageName == context.packageName) continue
            val info = packageInfoWithMetadata(base.packageName) ?: base
            if (!isXposedModule(info)) continue
            modules += toInstalledModule(info)
        }
        return InstalledModulesResult(
            modules = modules.sortedBy { it.label.lowercase() },
            versions = versions,
        )
    }

    /**
     * The metadata bundle of every installed package does not fit in one binder
     * reply on phones with many apps, and the truncated parcel kills the package
     * manager connection. The bulk call therefore asks for no metadata, and the
     * few packages that need it are resolved one at a time above.
     */
    private fun installedPackages(): List<PackageInfo> {
        @Suppress("DEPRECATION")
        return try {
            pm.getInstalledPackages(0)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Package list without metadata failed, using app list", e)
            @Suppress("DEPRECATION")
            try {
                pm.getInstalledApplications(0).mapNotNull { app ->
                    runCatching {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(app.packageName, 0)
                    }.getOrNull()
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not read the installed package list", e)
                emptyList()
            }
        }
    }

    private fun packageInfoWithMetadata(packageName: String): PackageInfo? = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Skipping metadata for $packageName", e)
        null
    }

    private fun versionCodeOf(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
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
        val versionCode = versionCodeOf(info)
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

    /** Current version of one package, used to confirm an install without a full rescan. */
    fun installedVersion(packageName: String): InstalledPackageVersion? = try {
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        InstalledPackageVersion(
            versionName = info.versionName,
            versionCode = versionCode,
            lastUpdateTime = info.lastUpdateTime,
        )
    } catch (_: PackageManager.NameNotFoundException) {
        null
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
        return InstalledModule(
            packageName = info.packageName,
            label = app?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
                ?: info.packageName,
            versionName = info.versionName,
            versionCode = versionCodeOf(info),
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

    private companion object {
        const val TAG = "InstalledModuleScanner"
    }
}
