package com.trellis.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trellis.studio.ui.screens.*
import com.trellis.studio.ui.theme.*
import java.net.URLDecoder
import java.net.URLEncoder

sealed class NavRoute(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    object Chat : NavRoute("chat", "Chat", Icons.Outlined.ChatBubbleOutline, Icons.AutoMirrored.Filled.Chat)
    object Generate : NavRoute("generate", "Create", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome)
    object Terminal : NavRoute("terminal", "Terminal", Icons.Outlined.Terminal, Icons.Filled.Terminal)
    object Browser : NavRoute("browser", "Browser", Icons.Outlined.Public, Icons.Filled.Public)
    object Gallery : NavRoute("gallery", "Gallery", Icons.Outlined.Collections, Icons.Filled.Collections)
    object Voice : NavRoute("voice", "Voice", Icons.Outlined.GraphicEq, Icons.Filled.GraphicEq)
    object Artifacts : NavRoute("artifacts", "Builds", Icons.Outlined.Android, Icons.Filled.Android)
    object Settings : NavRoute("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
}

/** Bottom bar holds the five primary destinations; the rest live in Gallery/Settings. */
val bottomNavItems = listOf(
    NavRoute.Chat,
    NavRoute.Generate,
    NavRoute.Terminal,
    NavRoute.Browser,
    NavRoute.Artifacts,
)

const val ROUTE_VIEWER = "viewer"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrellisTheme {
                MainContent()
            }
        }
    }
}

@Composable
fun MainContent() {
    val navController: NavHostController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // The 3D viewer is full-screen: no bottom bar behind it.
    val showBottomBar = currentRoute?.startsWith(ROUTE_VIEWER) != true

    Scaffold(
        // imePadding is required: enableEdgeToEdge() stops the window from resizing,
        // so without it the soft keyboard covers the chat input box.
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = BgDark,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = SurfDark, tonalElevation = 0.dp) {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    if (selected) item.selectedIcon else item.icon,
                                    contentDescription = item.label,
                                    modifier = Modifier.size(22.dp),
                                )
                            },
                            label = {
                                Text(item.label, style = MaterialTheme.typography.labelSmall)
                            },
                            selected = selected,
                            alwaysShowLabel = false,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TextPrimary,
                                selectedTextColor = Purple60,
                                unselectedIconColor = TextDisabled,
                                unselectedTextColor = TextDisabled,
                                indicatorColor = Purple40.copy(alpha = 0.30f),
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoute.Chat.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(NavRoute.Chat.route) { ChatScreen() }
            composable(NavRoute.Generate.route) {
                GenerateScreen(onOpenModel = { path, name -> navController.openViewer(path, name) })
            }
            composable(NavRoute.Terminal.route) { TerminalScreen() }
            composable(NavRoute.Browser.route) { BrowserScreen() }
            composable(NavRoute.Gallery.route) {
                GalleryScreen(
                    onOpenModel = { path, name -> navController.openViewer(path, name) },
                    onOpenVoice = { navController.navigate(NavRoute.Voice.route) },
                    onOpenSettings = { navController.navigate(NavRoute.Settings.route) },
                )
            }
            composable(NavRoute.Artifacts.route) { ArtifactsScreen() }
            composable(NavRoute.Voice.route) { VoiceScreen() }
            composable(NavRoute.Settings.route) { SettingsScreen() }

            composable(
                route = "$ROUTE_VIEWER/{path}/{name}",
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType },
                ),
            ) { entry ->
                val path = entry.arguments?.getString("path").orEmpty().decodeArg()
                val name = entry.arguments?.getString("name").orEmpty().decodeArg()
                ModelViewerScreen(
                    modelPath = path,
                    modelName = name.ifBlank { "3D Model" },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun NavHostController.openViewer(path: String, name: String) {
    navigate("$ROUTE_VIEWER/${path.encodeArg()}/${name.encodeArg()}")
}

// File paths contain "/", which would break route matching — encode both args.
private fun String.encodeArg(): String = URLEncoder.encode(this, "UTF-8")
private fun String.decodeArg(): String = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
