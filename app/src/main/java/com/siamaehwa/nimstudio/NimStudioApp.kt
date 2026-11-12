package com.siamaehwa.nimstudio

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.siamaehwa.nimstudio.core.LibraryRepository
import com.siamaehwa.nimstudio.core.NimClient
import com.siamaehwa.nimstudio.core.NimRepository
import com.siamaehwa.nimstudio.core.NimStore
import com.siamaehwa.nimstudio.core.db.AgentHistoryRepository
import com.siamaehwa.nimstudio.core.db.AppDatabase
import com.siamaehwa.nimstudio.core.hf.HfClient
import com.siamaehwa.nimstudio.core.hf.HfRepository
import com.siamaehwa.nimstudio.core.hf.HfStore
import com.siamaehwa.nimstudio.core.plugin.PluginRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NimStudioApp : Application() {

    lateinit var container: AppContainer
        private set

    var isInForeground: Boolean = false
        private set

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            container.store.keepAlive.collect { enabled ->
                KeepAliveService.setPersistent(this@NimStudioApp, enabled)
            }
        }
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivities++
                    isInForeground = true
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities = (startedActivities - 1).coerceAtLeast(0)
                    if (startedActivities == 0) isInForeground = false
                }

                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}

class AppContainer(application: Application) {
    val appContext: Context = application.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = NimStore(application)
    val client = NimClient()
    val repository = NimRepository(store, client)
    val database = AppDatabase.build(application)
    val conversations = database.conversationDao()
    val library = LibraryRepository(application, database.libraryDao())
    val hfStore = HfStore(application)
    val hfClient = HfClient()
    val hfRepository = HfRepository(hfStore, hfClient, library)
    val agentHistory = AgentHistoryRepository(database.agentHistoryDao())
    val plugins = PluginRepository(application)
}
