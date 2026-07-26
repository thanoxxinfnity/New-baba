package com.trellis.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trellis.studio.ui.screens.HistoryScreen
import com.trellis.studio.ui.screens.ImageTo3dScreen
import com.trellis.studio.ui.screens.SettingsScreen
import com.trellis.studio.ui.screens.TextToImageScreen
import com.trellis.studio.ui.screens.ViewerScreen
import com.trellis.studio.ui.theme.TrellisTheme

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("text_to_image", "Image", Icons.Default.Image),
    Tab("image_to_3d", "3D", Icons.Default.ViewInAr),
    Tab("history", "History", Icons.Default.History),
    Tab("settings", "Settings", Icons.Default.Settings)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrellisTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute in tabs.map { it.route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "text_to_image",
            modifier = Modifier.padding(padding)
        ) {
            composable("text_to_image") {
                TextToImageScreen(onNavigateTo3d = { navigateToTab("image_to_3d") })
            }
            composable("image_to_3d") {
                ImageTo3dScreen()
            }
            composable("history") {
                HistoryScreen(
                    onOpenViewer = { id -> navController.navigate("viewer/$id") },
                    onNavigateTo3d = { navigateToTab("image_to_3d") }
                )
            }
            composable("settings") {
                SettingsScreen()
            }
            composable(
                "viewer/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                ViewerScreen(
                    generationId = id,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
