package com.trellis.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.trellis.studio.ui.screens.*
import com.trellis.studio.ui.theme.*
import com.trellis.studio.ui.theme.BgDark
import com.trellis.studio.ui.theme.BorderDark
import com.trellis.studio.ui.theme.Purple60
import com.trellis.studio.ui.theme.SurfDark
import com.trellis.studio.ui.theme.TextDisabled
import com.trellis.studio.ui.theme.TextSecondary
import com.trellis.studio.ui.theme.TextPrimary

sealed class NavRoute(val route: String, val label: String, val icon: ImageVector) {
    object Chat     : NavRoute("chat",     "Chat",     Icons.Filled.Chat)
    object Generate : NavRoute("generate", "Generate", Icons.Filled.AutoAwesome)
    object Gallery  : NavRoute("gallery",  "Gallery",  Icons.Filled.Collections)
    object Voice    : NavRoute("voice",    "Voice",    Icons.Filled.RecordVoiceOver)
    object Settings : NavRoute("settings", "Settings", Icons.Filled.Settings)
}

val bottomNavItems = listOf(
    NavRoute.Chat,
    NavRoute.Generate,
    NavRoute.Gallery,
    NavRoute.Voice,
    NavRoute.Settings,
)

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

    Scaffold(
        // imePadding is required: enableEdgeToEdge() stops the window from resizing,
        // so without it the soft keyboard covers the chat input box.
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = BgDark,
        bottomBar = {
            NavigationBar(
                containerColor = SurfDark,
                tonalElevation = 0.dp,
            ) {
                bottomNavItems.forEach { item ->
                    val selected = currentRoute == item.route
                    NavigationBarItem(
                        icon = {
                            Icon(item.icon, contentDescription = item.label,
                                tint = if (selected) Purple60 else TextDisabled)
                        },
                        label = {
                            Text(item.label,
                                color = if (selected) Purple60 else TextDisabled,
                                style = MaterialTheme.typography.labelMedium)
                        },
                        selected = selected,
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
                            selectedIconColor = Purple60,
                            indicatorColor = Purple60.copy(alpha = 0.15f),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoute.Chat.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(NavRoute.Chat.route)     { ChatScreen() }
            composable(NavRoute.Generate.route) { GenerateScreen() }
            composable(NavRoute.Gallery.route)  { GalleryScreen() }
            composable(NavRoute.Voice.route)    { VoiceScreen() }
            composable(NavRoute.Settings.route) { SettingsScreen() }
        }
    }
}
