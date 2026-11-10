package com.siamaehwa.nimstudio

import android.app.Application
import com.siamaehwa.nimstudio.core.NimClient
import com.siamaehwa.nimstudio.core.NimRepository
import com.siamaehwa.nimstudio.core.NimStore

class NimStudioApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val store = NimStore(application)
    val client = NimClient()
    val repository = NimRepository(store, client)
}
