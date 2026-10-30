package com.rushi.modulesmanager

import android.app.Application
import android.content.Context
import com.rushi.modulesmanager.data.AndroidNetworkMonitor
import com.rushi.modulesmanager.data.DownloadCenter
import com.rushi.modulesmanager.data.DownloadRetry
import com.rushi.modulesmanager.data.DownloadSlots
import com.rushi.modulesmanager.data.InstalledModuleScanner
import com.rushi.modulesmanager.data.LibraryStore
import com.rushi.modulesmanager.data.LiveReleaseApi
import com.rushi.modulesmanager.data.ModuleStateStore
import com.rushi.modulesmanager.data.PendingInstalls
import com.rushi.modulesmanager.data.ReadmeApi
import com.rushi.modulesmanager.data.RepoApi
import com.rushi.modulesmanager.data.RepoRepository
import com.rushi.modulesmanager.data.Settings

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settings = Settings(appContext)
    val library = LibraryStore(appContext)
    val moduleState = ModuleStateStore(appContext)
    val pendingInstalls = PendingInstalls.get(appContext)
    val batchState = BatchStateStore(appContext)
    val downloadSlots = DownloadSlots { settings.concurrentDownloads.value }
    val api = RepoApi(
        RepoApi.createClient(),
        DownloadRetry(network = AndroidNetworkMonitor(appContext)),
    )
    val liveApi = LiveReleaseApi(RepoApi.createClient())
    val readmeApi = ReadmeApi(RepoApi.createClient())
    val repo = RepoRepository(appContext, api, moduleState)
    val scanner = InstalledModuleScanner(appContext)
    val downloads = DownloadCenter()
}

class ModulesManagerApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
