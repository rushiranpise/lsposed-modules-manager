package com.rushi.modulesmanager.data

import android.content.Context
import java.io.File

/** Sizes and cleanup for the files this app keeps outside its databases. */
object AppStorage {

    fun indexCacheSize(context: Context): Long = size(indexDir(context))

    fun readmeCacheSize(context: Context): Long = size(readmeDir(context))

    fun downloadedApkSize(context: Context): Long = size(apkDir(context))

    fun clearIndexCache(context: Context) {
        indexDir(context).deleteRecursively()
    }

    fun clearReadmeCache(context: Context) {
        readmeDir(context).deleteRecursively()
    }

    fun clearDownloadedApks(context: Context) {
        apkDir(context).deleteRecursively()
    }

    private fun indexDir(context: Context) = File(context.filesDir, "repo-cache")

    private fun readmeDir(context: Context) = File(context.filesDir, "readme-cache")

    private fun apkDir(context: Context) = File(context.cacheDir, "apk")

    private fun size(dir: File): Long = runCatching {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)
}
