package com.rushi.modulesmanager

import android.app.Application
import android.content.Context
import com.rushi.modulesmanager.data.DownloadCenter
import com.rushi.modulesmanager.data.InstalledModuleScanner
import com.rushi.modulesmanager.data.LiveReleaseApi
import com.rushi.modulesmanager.data.ModuleStateStore
import com.rushi.modulesmanager.data.ReadmeApi
import com.rushi.modulesmanager.data.RepoApi
import com.rushi.modulesmanager.data.RepoRepository
import com.rushi.modulesmanager.data.Settings

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settings = Settings(appContext)
    val moduleState = ModuleStateStore(appContext)
    val api = RepoApi(RepoApi.createClient())
    val liveApi = LiveReleaseApi(RepoApi.createClient())
    val readmeApi = ReadmeApi(RepoApi.createClient())
    val repo = RepoRepository(appContext, api)
    val scanner = InstalledModuleScanner(appContext)
    val downloads = DownloadCenter()
}

class ModulesManagerApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
