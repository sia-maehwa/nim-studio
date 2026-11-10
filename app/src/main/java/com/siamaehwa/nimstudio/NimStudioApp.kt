package com.siamaehwa.nimstudio

import android.app.Application
import android.content.Context
import com.siamaehwa.nimstudio.core.LibraryRepository
import com.siamaehwa.nimstudio.core.NimClient
import com.siamaehwa.nimstudio.core.NimRepository
import com.siamaehwa.nimstudio.core.NimStore
import com.siamaehwa.nimstudio.core.db.AppDatabase

class NimStudioApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val appContext: Context = application.applicationContext
    val store = NimStore(application)
    val client = NimClient()
    val repository = NimRepository(store, client)
    val database = AppDatabase.build(application)
    val conversations = database.conversationDao()
    val library = LibraryRepository(application, database.libraryDao())
}
