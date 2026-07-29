package com.trellis.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trellis.studio.ui.screens.AiBrowserScreen
import com.trellis.studio.ui.screens.CanvasScreen
import com.trellis.studio.ui.screens.ChatHistoryScreen
import com.trellis.studio.ui.screens.ChatScreen
import com.trellis.studio.ui.screens.GameBuilderScreen
import com.trellis.studio.ui.screens.HistoryScreen
import com.trellis.studio.ui.screens.ImageTo3dScreen
import com.trellis.studio.ui.screens.SettingsScreen
import com.trellis.studio.ui.screens.TerminalScreen
import com.trellis.studio.ui.screens.TextToImageScreen
import com.trellis.studio.ui.screens.TtsStudioScreen
import com.trellis.studio.ui.screens.ViewerScreen
import com.trellis.studio.ui.theme.NimOrange
import com.trellis.studio.ui.theme.TrellisTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TrellisTheme { MainScreen() } }
    }
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

@Composable
private fun MainScreen() {
    val navController  = rememberNavController()
    val drawerState    = rememberDrawerState(DrawerValue.Closed)
    val scope          = rememberCoroutineScope()
    val backStack      by navController.currentBackStackEntryAsState()
    val currentRoute   = backStack?.destination?.route

    fun go(route: String) {
        navController.navigate(route) { launchSingleTop = true }
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState    = drawerState,
        drawerContent  = {
            ModalDrawerSheet(Modifier.width(290.dp)) {

                // ── Header ──────────────────────────────────────────────────
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(20.dp, 28.dp, 20.dp, 16.dp)
                ) {
                    Column {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint     = NimOrange,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("NIM AI Agent", style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground)
                        Text("Powered by NVIDIA NIM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider()

                // ── Core ──────────────────────────────────────────────────
                Spacer(Modifier.height(4.dp))
                DrawerNav(
                    icon     = Icons.Default.Add,
                    label    = "New Chat",
                    selected = false,
                    onClick  = {
                        navController.navigate("chat") {
                            popUpTo("chat") { inclusive = true }
                        }
                        scope.launch { drawerState.close() }
                    }
                )
                DrawerNav(
                    icon     = Icons.Default.History,
                    label    = "Chat History",
                    selected = currentRoute == "chat_history",
                    onClick  = { go("chat_history") }
                )

                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                Text(
                    "Tools",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, bottom = 4.dp)
                )

                DrawerNav(Icons.Default.Image,        "Image Generator", currentRoute == "text_to_image") { go("text_to_image") }
                DrawerNav(Icons.Default.ViewInAr,     "3D Models",       currentRoute == "image_to_3d")   { go("image_to_3d")   }
                DrawerNav(Icons.Default.SportsEsports,"Game Builder",    currentRoute == "game_builder")  { go("game_builder")  }
                DrawerNav(Icons.Default.Mic,          "Voice Studio",    currentRoute == "tts_studio")    { go("tts_studio")    }
                DrawerNav(Icons.Default.Language,     "AI Browser",      currentRoute == "ai_browser")    { go("ai_browser")    }
                DrawerNav(Icons.Default.Terminal,     "Linux Terminal",  currentRoute == "terminal")      { go("terminal")      }

                Spacer(Modifier.weight(1f))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))

                DrawerNav(Icons.Default.Settings, "Settings", currentRoute == "settings") { go("settings") }
                Spacer(Modifier.height(8.dp))
            }
        }
    ) {
        NavHost(navController = navController, startDestination = "chat") {

            composable("chat") {
                ChatScreen(
                    onOpenDrawer         = { scope.launch { drawerState.open() } },
                    onOpenHistory        = { navController.navigate("chat_history") },
                    onOpenSessionRequest = null,
                    onOpenCanvas         = { navController.navigate("canvas") },
                    onNavigateToTerminal = { go("terminal") }
                )
            }

            composable("chat_history") {
                ChatHistoryScreen(
                    onBack        = { navController.popBackStack() },
                    onOpenSession = { id ->
                        navController.popBackStack()
                        navController.navigate("chat_open/$id")
                    }
                )
            }

            composable(
                "chat_open/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                ChatScreen(
                    onOpenDrawer         = { scope.launch { drawerState.open() } },
                    onOpenHistory        = { navController.navigate("chat_history") },
                    onOpenSessionRequest = id,
                    onOpenCanvas         = { navController.navigate("canvas") },
                    onNavigateToTerminal = { go("terminal") }
                )
            }

            composable("canvas") {
                CanvasScreen(onBack = { navController.popBackStack() })
            }

            composable("text_to_image") {
                TextToImageScreen(
                    onNavigateTo3d = { navController.navigate("image_to_3d") },
                    onBack         = { navController.popBackStack() }
                )
            }

            composable("image_to_3d") {
                ImageTo3dScreen(onBack = { navController.popBackStack() })
            }

            composable("game_builder") {
                GameBuilderScreen(onBack = { navController.popBackStack() })
            }

            composable("tts_studio") {
                TtsStudioScreen(onBack = { navController.popBackStack() })
            }

            composable("ai_browser") {
                AiBrowserScreen(onBack = { navController.popBackStack() })
            }

            composable("terminal") {
                TerminalScreen(onBack = { navController.popBackStack() })
            }

            composable("history") {
                HistoryScreen(
                    onOpenViewer   = { id -> navController.navigate("viewer/$id") },
                    onNavigateTo3d = { navController.navigate("image_to_3d") }
                )
            }

            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(
                "viewer/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                ViewerScreen(
                    generationId = id,
                    onBack       = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
private fun DrawerNav(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        icon     = { Icon(icon, contentDescription = null) },
        label    = { Text(label) },
        selected = selected,
        onClick  = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}
