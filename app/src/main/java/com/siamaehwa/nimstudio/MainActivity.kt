package com.siamaehwa.nimstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.siamaehwa.nimstudio.ui.AppRoot
import com.siamaehwa.nimstudio.ui.theme.NimStudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NimStudioTheme {
                AppRoot()
            }
        }
    }
}
