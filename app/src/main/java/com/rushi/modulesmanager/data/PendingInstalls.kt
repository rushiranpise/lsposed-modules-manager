package com.rushi.modulesmanager.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** An install handed to the system installer or a PackageInstaller session. */
data class PendingInstall(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String?,
    val startedAt: Long,
) {
    /** True when the installed copy is the one this pending install was waiting for. */
    fun landed(installed: InstalledPackageVersion?): Boolean {
        if (installed == null) return false
        if (installed.lastUpdateTime >= startedAt) return true
        return when {
            versionCode > 0L && installed.versionCode > 0L ->
                installed.versionCode >= versionCode
            versionName != null -> {
                val expected = ModuleVersion(versionCode, versionName)
                !expected.isNewerThan(installed.versionCode, installed.versionName)
            }
            else -> false
        }
    }
}

/**
 * Survives process death: the system installer and the session confirmation can finish while
 * the app is gone, so the next scan has to know what to look for.
 */
class PendingInstalls private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("pending_installs", Context.MODE_PRIVATE)

    fun all(): List<PendingInstall> = read()

    fun isPending(packageName: String): Boolean = read().any { it.packageName == packageName }

    @Synchronized
    fun put(install: PendingInstall) {
        val updated = read().filterNot { it.packageName == install.packageName } + install
        write(updated)
    }

    @Synchronized
    fun remove(packageName: String) {
        val current = read()
        val updated = current.filterNot { it.packageName == packageName }
        if (updated.size != current.size) write(updated)
    }

    private fun read(): List<PendingInstall> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val packageName = obj.optString("package").takeIf { it.isNotBlank() } ?: continue
                add(
                    PendingInstall(
                        packageName = packageName,
                        label = obj.optString("label").ifBlank { packageName },
                        versionCode = obj.optLong("versionCode", 0L),
                        versionName = obj.optString("versionName")
                            .takeIf { it.isNotBlank() && it != "null" },
                        startedAt = obj.optLong("startedAt", 0L),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun write(items: List<PendingInstall>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("package", item.packageName)
                put("label", item.label)
                put("versionCode", item.versionCode)
                item.versionName?.let { put("versionName", it) }
                put("startedAt", item.startedAt)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        @Volatile
        private var instance: PendingInstalls? = null

        fun get(context: Context): PendingInstalls = instance ?: synchronized(this) {
            instance ?: PendingInstalls(context.applicationContext).also { instance = it }
        }

        private const val KEY = "pending"
    }
}
