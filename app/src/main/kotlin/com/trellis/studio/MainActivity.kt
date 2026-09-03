package com.trellis.studio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trellis.studio.ui.components.AppDrawer
import com.trellis.studio.ui.components.DrawerEntry
import com.trellis.studio.ui.screens.*
import com.trellis.studio.ui.theme.*
import kotlinx.coroutines.launch
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
    object Animate : NavRoute("animate", "Animate", Icons.Outlined.Animation, Icons.Filled.Animation)
    object Artifacts : NavRoute("artifacts", "Builds", Icons.Outlined.Android, Icons.Filled.Android)
    object Game : NavRoute("game", "Game", Icons.Outlined.SportsEsports, Icons.Filled.SportsEsports)
    object Video : NavRoute("video", "Video", Icons.Outlined.Movie, Icons.Filled.Movie)
    object LiveVoice : NavRoute("livevoice", "Live Voice", Icons.Outlined.RecordVoiceOver, Icons.Filled.RecordVoiceOver)
    object Booster : NavRoute("booster", "Booster", Icons.Outlined.Bolt, Icons.Filled.Bolt)
    object IdeaLab : NavRoute("idealab", "Idea Lab", Icons.Outlined.Casino, Icons.Filled.Casino)
    object Stats : NavRoute("stats", "Stats", Icons.Outlined.EmojiEvents, Icons.Filled.EmojiEvents)
    object Device : NavRoute("device", "Device", Icons.Outlined.Memory, Icons.Filled.Memory)
    object Personas : NavRoute("personas", "AI Vibes", Icons.Outlined.TheaterComedy, Icons.Filled.TheaterComedy)
    object Settings : NavRoute("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
}

/** Bottom bar keeps the four most-used screens; the drawer holds everything. */
val bottomNavItems = listOf(
    NavRoute.Chat,
    NavRoute.Generate,
    NavRoute.Terminal,
    NavRoute.Artifacts,
)

/** Every destination, grouped, for the side drawer. */
val drawerEntries = listOf(
    DrawerEntry("chat", "Chat", Icons.AutoMirrored.Filled.Chat, "Talk, code, generate images", "Workspace"),
    DrawerEntry("spark", "Spark", Icons.Filled.AutoAwesome, "Describe an app — get a real one", "Workspace"),
    DrawerEntry("generate", "Create", Icons.Filled.AutoAwesome, "Images and 3D models", "Workspace"),
    DrawerEntry("rigstudio", "Rig Studio", Icons.Filled.Accessibility, "Upload your model, add bones, download it", "Workspace"),
    DrawerEntry("sketchfab", "Sketchfab", Icons.Filled.Download, "Find models by name or link, download them", "Workspace"),
    DrawerEntry("gallery", "Gallery", Icons.Filled.Collections, "Everything you've made", "Workspace"),
    DrawerEntry("animate", "Animate", Icons.Filled.Animation, "Give your 3D models motion", "Workspace"),
    DrawerEntry("game", "Game Studio", Icons.Filled.SportsEsports, "Make a Godot game from your 3D models", "Workspace"),
    DrawerEntry("booster", "Game Booster", Icons.Filled.Bolt, "Free memory for smoother games", "Workspace"),
    DrawerEntry("idealab", "Idea Lab", Icons.Filled.Casino, "Surprise-me creative prompts", "Workspace"),
    DrawerEntry("personas", "AI Vibes", Icons.Filled.TheaterComedy, "Give the chat a personality", "Workspace"),
    DrawerEntry("stats", "Your Stats", Icons.Filled.EmojiEvents, "Creations, streaks and badges", "Workspace"),
    DrawerEntry("device", "Device", Icons.Filled.Memory, "Live RAM, battery, storage", "Developer"),
    DrawerEntry("video", "Video Studio", Icons.Filled.Movie, "Text → a real video, scene by scene", "Workspace"),
    DrawerEntry("aivideo", "AI Video", Icons.Filled.Movie, "Text/image → real AI video (free)", "Workspace"),
    DrawerEntry("voice", "Voice", Icons.Filled.GraphicEq, "NVIDIA cloud TTS and voice cloning", "Workspace"),
    DrawerEntry("livevoice", "Live Voice", Icons.Filled.RecordVoiceOver, "Talk to the AI out loud, in your voice", "Workspace"),

    DrawerEntry("terminal", "Terminal", Icons.Filled.Terminal, "Local shell or your machine", "Developer"),
    DrawerEntry("artifacts", "Builds", Icons.Filled.Android, "APKs, files and build logs", "Developer"),
    DrawerEntry("browser", "Browser", Icons.Filled.Public, "Real Google search", "Developer"),

    // ── Tools & fun ──
    DrawerEntry("focustimer", "Focus Timer", Icons.Filled.Timer, "Pomodoro for gaming & study", "Tools"),
    DrawerEntry("stopwatch", "Stopwatch", Icons.Filled.Timelapse, "Time with laps", "Tools"),
    DrawerEntry("dicecoin", "Dice & Coin", Icons.Filled.Casino, "Roll or flip", "Tools"),
    DrawerEntry("randompicker", "Random Picker", Icons.Filled.Shuffle, "Let fate decide", "Tools"),
    DrawerEntry("passwordgen", "Password Gen", Icons.Filled.Password, "Strong random passwords", "Tools"),
    DrawerEntry("unitconverter", "Unit Converter", Icons.Filled.SwapHoriz, "Length, weight, temp", "Tools"),
    DrawerEntry("fancytext", "Fancy Text", Icons.Filled.TextFields, "Cool fonts for your bio", "Tools"),
    DrawerEntry("colorpalette", "Color Palette", Icons.Filled.Palette, "Fresh color schemes", "Tools"),
    DrawerEntry("gamertag", "Gamertag Gen", Icons.Filled.SportsEsports, "AI names for games", "Tools"),
    DrawerEntry("memecaption", "Meme Captions", Icons.Filled.EmojiEmotions, "AI funny captions", "Tools"),
    DrawerEntry("quickai", "AI Fun", Icons.Filled.AutoAwesome, "Roast, joke, fortune", "Tools"),
    DrawerEntry("compass", "Compass", Icons.Filled.Explore, "Find your bearing", "Tools"),
    DrawerEntry("level", "Bubble Level", Icons.Filled.Straighten, "Is it straight?", "Tools"),
    DrawerEntry("soundmeter", "Sound Meter", Icons.Filled.GraphicEq, "How loud is it?", "Tools"),
    DrawerEntry("flashlight", "Flashlight", Icons.Filled.FlashlightOn, "Light it up", "Tools"),
    DrawerEntry("shakeboost", "Shake to Boost", Icons.Filled.Vibration, "Shake to free RAM", "Tools"),
    DrawerEntry("ttsreader", "Read Aloud", Icons.Filled.VolumeUp, "Text to speech", "Tools"),
    DrawerEntry("gradientwall", "Wallpaper Studio", Icons.Filled.Wallpaper, "Neon gradient wallpapers", "Tools"),
    DrawerEntry("charging", "Charging Display", Icons.Filled.BatteryChargingFull, "Bedside battery screen", "Tools"),
    DrawerEntry("screentest", "Screen Test", Icons.Filled.Smartphone, "Check for dead pixels", "Tools"),
    DrawerEntry("tempmail", "Temp Inbox", Icons.Filled.AlternateEmail, "Throwaway email, keep yours private", "Tools"),
    DrawerEntry("vault", "Vault", Icons.Filled.Lock, "Encrypted locker for your passwords", "Tools"),
    DrawerEntry("fakecall", "Fake Call", Icons.Filled.Call, "Prank a friend with a fake call", "Tools"),
    DrawerEntry("calculator", "Calculator", Icons.Filled.Calculate, "Quick math", "Tools"),
    DrawerEntry("quicknotes", "Quick Notes", Icons.Filled.EditNote, "Auto-saved scratchpad", "Tools"),
    DrawerEntry("texttools", "Text Tools", Icons.Filled.TextFields, "Count & transform text", "Tools"),
    DrawerEntry("devtools", "Dev Tools", Icons.Filled.Code, "Base64, hash, URL encode", "Tools"),
    DrawerEntry("agecalc", "Age Calculator", Icons.Filled.Cake, "How old are you exactly", "Tools"),
    DrawerEntry("metronome", "Metronome", Icons.Filled.MusicNote, "Keep the beat", "Tools"),
    DrawerEntry("whiteboard", "Whiteboard", Icons.Filled.Brush, "Doodle & sketch", "Tools"),
    DrawerEntry("breathe", "Breathe", Icons.Filled.SelfImprovement, "Calm-down breathing", "Tools"),
    DrawerEntry("tictactoe", "Tic-Tac-Toe", Icons.Filled.GridOn, "Two-player game", "Tools"),
    DrawerEntry("magic8", "Magic 8-Ball", Icons.Filled.Casino, "Ask for wisdom", "Tools"),

    DrawerEntry("settings", "Settings", Icons.Filled.Settings, "Keys, models, build server", "System"),
) + AI_TOOLS.map { DrawerEntry(it.key, it.title, Icons.Filled.AutoAwesome, it.subtitle, "AI Tools") } +
    DEV_AI_TOOLS.map { DrawerEntry(it.key, it.title, Icons.Filled.Code, it.subtitle, "Dev Tools") } +
    listOf(
        DrawerEntry("files", "Code Folder", Icons.Filled.FolderOpen, "Edit files + run AI on them", "Dev Tools"),
        DrawerEntry("apitester", "API Tester", Icons.Filled.Send, "REST client — test any endpoint", "Dev Tools"),
        DrawerEntry("json", "JSON Formatter", Icons.Filled.Code, "Pretty-print & validate", "Dev Tools"),
        DrawerEntry("jwt", "JWT Decoder", Icons.Filled.Key, "Decode header & payload", "Dev Tools"),
        DrawerEntry("uuid", "UUID Generator", Icons.Filled.Fingerprint, "Random v4 IDs", "Dev Tools"),
        DrawerEntry("timestamp", "Timestamp", Icons.Filled.Schedule, "Epoch ⇄ date", "Dev Tools"),
        DrawerEntry("baseconv", "Base Converter", Icons.Filled.Numbers, "Bin/Oct/Dec/Hex", "Dev Tools"),
        DrawerEntry("caseconv", "Case Converter", Icons.Filled.TextFields, "camelCase, snake_case…", "Dev Tools"),
        DrawerEntry("regextest", "Regex Tester", Icons.Filled.Search, "Live match testing", "Dev Tools"),
        DrawerEntry("colorconv", "Color Converter", Icons.Filled.Palette, "HEX/RGB/HSL", "Dev Tools"),
        DrawerEntry("diff", "Diff Checker", Icons.Filled.List, "Compare two texts", "Dev Tools"),
        DrawerEntry("lorem", "Lorem Ipsum", Icons.Filled.Article, "Placeholder text", "Dev Tools"),
    )

const val ROUTE_VIEWER = "viewer"
const val ROUTE_IMAGE = "image"

class MainActivity : ComponentActivity() {

    /**
     * Android 13 denies notifications until asked. Without the permission the
     * generation service's notification never appears, so background work looks
     * like it is doing nothing — and an invisible foreground notification is the
     * first thing aggressive battery managers kill.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotifications()
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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // The 3D viewer is full-screen: no chrome behind it.
    val immersive = currentRoute?.startsWith(ROUTE_VIEWER) == true ||
        currentRoute?.startsWith(ROUTE_IMAGE) == true

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    fun go(route: String) {
        scope.launch { drawerState.close() }
        if (currentRoute != route) {
            navController.navigate(route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !immersive,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        drawerContent = {
            AppDrawer(
                entries = drawerEntries,
                currentRoute = currentRoute,
                onSelect = ::go,
            )
        },
    ) {
        Scaffold(
            // imePadding is required: enableEdgeToEdge() stops the window from resizing,
            // so without it the soft keyboard covers the chat input box.
            modifier = Modifier.fillMaxSize().imePadding(),
            containerColor = Color.Transparent,
            bottomBar = {
                if (!immersive) {
                    NavigationBar(
                        containerColor = SurfDark.copy(alpha = 0.96f),
                        tonalElevation = 0.dp,
                    ) {
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
                                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                                selected = selected,
                                alwaysShowLabel = false,
                                onClick = { go(item.route) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Cyan,
                                    selectedTextColor = Cyan,
                                    unselectedIconColor = TextDisabled,
                                    unselectedTextColor = TextDisabled,
                                    indicatorColor = Purple40.copy(alpha = 0.32f),
                                ),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            // Ambient glow behind every screen, so surfaces read as glass.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(BgDark)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Purple40.copy(alpha = 0.16f),
                                Cyan.copy(alpha = 0.05f),
                                Color.Transparent,
                            ),
                            radius = 1500f,
                        )
                    )
            ) {
                NavHost(
                    navController = navController,
                    startDestination = NavRoute.Chat.route,
                    modifier = Modifier.padding(innerPadding),
                ) {
                    composable(NavRoute.Chat.route) { ChatScreen(onMenu = openDrawer) }
                    composable(NavRoute.Generate.route) {
                        GenerateScreen(
                            onMenu = openDrawer,
                            onOpenModel = { path, name -> navController.openViewer(path, name) },
                        )
                    }
                    composable(NavRoute.Terminal.route) { TerminalScreen(onMenu = openDrawer) }
                    composable(NavRoute.Browser.route) { BrowserScreen(onMenu = openDrawer) }
                    composable(NavRoute.Gallery.route) {
                        GalleryScreen(
                            onMenu = openDrawer,
                            onOpenModel = { path, name -> navController.openViewer(path, name) },
                            onOpenImage = { path, name -> navController.openImage(path, name) },
                            onOpenVoice = { go("voice") },
                            onOpenSettings = { go("settings") },
                        )
                    }
                    composable(NavRoute.Artifacts.route) { ArtifactsScreen(onMenu = openDrawer) }
                    composable(NavRoute.Voice.route) { VoiceScreen(onMenu = openDrawer) }
                    composable(NavRoute.Animate.route) {
                        AnimateScreen(
                            onMenu = openDrawer,
                            onOpenModel = { path, name -> navController.openViewer(path, name) },
                            onCreateModel = { go(NavRoute.Generate.route) },
                        )
                    }
                    composable("spark") { SparkScreen(onMenu = openDrawer) }
                    composable("sketchfab") {
                        SketchfabScreen(
                            onMenu = openDrawer,
                            onOpenModel = { path, name -> navController.openViewer(path, name) },
                        )
                    }
                    composable("rigstudio") {
                        RigStudioScreen(
                            onMenu = openDrawer,
                            onOpenModel = { path, name -> navController.openViewer(path, name) },
                        )
                    }
                    composable(NavRoute.Game.route) {
                        GameScreen(
                            onMenu = openDrawer,
                            onCreateModel = { go(NavRoute.Generate.route) },
                        )
                    }
                    composable(NavRoute.Video.route) { VideoScreen(onMenu = openDrawer) }
                    composable("aivideo") { AiVideoScreen(onMenu = openDrawer) }
                    composable(NavRoute.LiveVoice.route) { LiveVoiceScreen(onMenu = openDrawer) }
                    composable(NavRoute.Booster.route) { BoosterScreen(onMenu = openDrawer) }
                    composable(NavRoute.IdeaLab.route) { IdeaLabScreen(onMenu = openDrawer) }
                    composable(NavRoute.Stats.route) { StatsScreen(onMenu = openDrawer) }
                    composable(NavRoute.Device.route) { DeviceScreen(onMenu = openDrawer) }
                    composable("focustimer") { FocusTimerScreen(onMenu = openDrawer) }
                    composable("stopwatch") { StopwatchScreen(onMenu = openDrawer) }
                    composable("dicecoin") { DiceCoinScreen(onMenu = openDrawer) }
                    composable("randompicker") { RandomPickerScreen(onMenu = openDrawer) }
                    composable("passwordgen") { PasswordGenScreen(onMenu = openDrawer) }
                    composable("unitconverter") { UnitConverterScreen(onMenu = openDrawer) }
                    composable("fancytext") { FancyTextScreen(onMenu = openDrawer) }
                    composable("colorpalette") { ColorPaletteScreen(onMenu = openDrawer) }
                    composable("gamertag") { GamertagScreen(onMenu = openDrawer) }
                    composable("memecaption") { MemeCaptionScreen(onMenu = openDrawer) }
                    composable("quickai") { QuickAiScreen(onMenu = openDrawer) }
                    composable("compass") { CompassScreen(onMenu = openDrawer) }
                    composable("level") { LevelScreen(onMenu = openDrawer) }
                    composable("soundmeter") { SoundMeterScreen(onMenu = openDrawer) }
                    composable("flashlight") { FlashlightScreen(onMenu = openDrawer) }
                    composable("shakeboost") { ShakeBoostScreen(onMenu = openDrawer) }
                    composable("ttsreader") { TtsReaderScreen(onMenu = openDrawer) }
                    composable("gradientwall") { GradientWallpaperScreen(onMenu = openDrawer) }
                    composable("charging") { ChargingDisplayScreen(onMenu = openDrawer) }
                    composable("screentest") { ScreenTestScreen(onMenu = openDrawer) }
                    composable("tempmail") { TempMailScreen(onMenu = openDrawer) }
                    composable("fakecall") { FakeCallScreen(onMenu = openDrawer) }
                    composable("vault") { VaultScreen(onMenu = openDrawer) }
                    composable("calculator") { CalculatorScreen(onMenu = openDrawer) }
                    composable("quicknotes") { QuickNotesScreen(onMenu = openDrawer) }
                    composable("texttools") { TextToolsScreen(onMenu = openDrawer) }
                    composable("devtools") { DevToolsScreen(onMenu = openDrawer) }
                    composable("agecalc") { AgeCalculatorScreen(onMenu = openDrawer) }
                    composable("metronome") { MetronomeScreen(onMenu = openDrawer) }
                    composable("whiteboard") { WhiteboardScreen(onMenu = openDrawer) }
                    composable("breathe") { BreathingScreen(onMenu = openDrawer) }
                    composable("tictactoe") { TicTacToeScreen(onMenu = openDrawer) }
                    composable("magic8") { Magic8BallScreen(onMenu = openDrawer) }
                    AI_TOOLS.forEach { spec ->
                        composable(spec.key) { AiToolScreen(spec, onMenu = openDrawer) }
                    }
                    DEV_AI_TOOLS.forEach { spec ->
                        composable(spec.key) { AiToolScreen(spec, onMenu = openDrawer) }
                    }
                    composable("files") { FilesScreen(onMenu = openDrawer) }
                    composable("apitester") { ApiTesterScreen(onMenu = openDrawer) }
                    composable("json") { JsonFormatterScreen(onMenu = openDrawer) }
                    composable("jwt") { JwtDecoderScreen(onMenu = openDrawer) }
                    composable("uuid") { UuidScreen(onMenu = openDrawer) }
                    composable("timestamp") { TimestampScreen(onMenu = openDrawer) }
                    composable("baseconv") { BaseConverterScreen(onMenu = openDrawer) }
                    composable("caseconv") { CaseConverterScreen(onMenu = openDrawer) }
                    composable("regextest") { RegexTesterScreen(onMenu = openDrawer) }
                    composable("colorconv") { ColorConverterScreen(onMenu = openDrawer) }
                    composable("diff") { DiffScreen(onMenu = openDrawer) }
                    composable("lorem") { LoremScreen(onMenu = openDrawer) }
                    composable(NavRoute.Personas.route) {
                        PersonasScreen(onMenu = openDrawer, onOpenChat = { go(NavRoute.Chat.route) })
                    }
                    composable(NavRoute.Settings.route) { SettingsScreen(onMenu = openDrawer) }

                    // The image viewer existed but had no route, so tapping a
                    // generated image in the gallery did nothing.
                    composable(
                        route = "$ROUTE_IMAGE/{path}/{name}",
                        arguments = listOf(
                            navArgument("path") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        ImageViewerScreen(
                            imagePath = entry.arguments?.getString("path").orEmpty().decodeArg(),
                            title = entry.arguments?.getString("name").orEmpty().decodeArg()
                                .ifBlank { "Image" },
                            onBack = { navController.popBackStack() },
                            onEdited = { path, name -> navController.openImage(path, name) },
                        )
                    }

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
    }
}

private fun NavHostController.openViewer(path: String, name: String) {
    navigate("$ROUTE_VIEWER/${path.encodeArg()}/${name.encodeArg()}")
}

private fun NavHostController.openImage(path: String, name: String) {
    navigate("$ROUTE_IMAGE/${path.encodeArg()}/${name.encodeArg()}")
}

// File paths contain "/", which would break route matching — encode both args.
private fun String.encodeArg(): String = URLEncoder.encode(this, "UTF-8")
private fun String.decodeArg(): String = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
