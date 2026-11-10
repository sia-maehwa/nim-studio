package com.siamaehwa.nimstudio.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.siamaehwa.nimstudio.ui.chat.ChatScreen
import com.siamaehwa.nimstudio.ui.image.ImageScreen
import com.siamaehwa.nimstudio.ui.library.LibraryScreen
import com.siamaehwa.nimstudio.ui.models.ModelsScreen
import com.siamaehwa.nimstudio.ui.settings.SettingsScreen

private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    Destination("chat", "채팅", Icons.Filled.Chat),
    Destination("image", "이미지", Icons.Filled.Image),
    Destination("models", "모델", Icons.Filled.Memory),
    Destination("library", "라이브러리", Icons.Filled.Folder),
    Destination("settings", "설정", Icons.Filled.Settings),
)

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            if (currentRoute != destination.route) {
                                navController.switchTab(destination.route)
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("chat") {
                ChatScreen(onOpenSettings = { navController.switchTab("settings") })
            }
            composable("image") {
                ImageScreen()
            }
            composable("models") {
                ModelsScreen(onOpenChat = { navController.switchTab("chat") })
            }
            composable("library") {
                LibraryScreen()
            }
            composable("settings") {
                SettingsScreen()
            }
        }
    }
}
