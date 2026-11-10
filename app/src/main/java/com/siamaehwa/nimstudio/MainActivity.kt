package com.siamaehwa.nimstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siamaehwa.nimstudio.ui.AppRoot
import com.siamaehwa.nimstudio.ui.theme.NimStudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = (application as NimStudioApp).container.store
        setContent {
            val systemDark = isSystemInDarkTheme()
            val darkMode by store.darkMode.collectAsStateWithLifecycle(initialValue = null)
            NimStudioTheme(darkTheme = darkMode ?: systemDark) {
                AppRoot()
            }
        }
    }
}
