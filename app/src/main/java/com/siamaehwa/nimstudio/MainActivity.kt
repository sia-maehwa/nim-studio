package com.siamaehwa.nimstudio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siamaehwa.nimstudio.core.Notifier
import com.siamaehwa.nimstudio.ui.AppRoot
import com.siamaehwa.nimstudio.ui.theme.NimStudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        val store = (application as NimStudioApp).container.store
        setContent {
            val systemDark = isSystemInDarkTheme()
            val darkMode by store.darkMode.collectAsStateWithLifecycle(initialValue = null)
            NimStudioTheme(darkTheme = darkMode ?: systemDark) {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Notifier.clear(this)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_REQUEST,
        )
    }

    private companion object {
        const val NOTIFICATION_REQUEST = 100
    }
}
