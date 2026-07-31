package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.data.model.RemoteArtifact
import com.trellis.studio.ui.components.copyToClipboard
import com.trellis.studio.ui.theme.*
import com.trellis.studio.util.FileExport
import com.trellis.studio.viewmodel.BuildViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Everything the app has produced: APKs from the build server, local exports
 * (.zip, .glb, images), and the build server's own status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsScreen(vm: BuildViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = BgDark,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(BgDark)) {
            // Header
            Surface(color = SurfDark) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Artifacts",
                            style = MaterialTheme.typography.titleLarge.copy(brush = NeonBrush),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Default.Refresh, "Refresh", tint = TextSecondary)
                        }
                    }
                    ServerStatusStrip(state.serverOnline, state.canBuildApk, state.serverUrl)
                    TabRow(
                        selectedTabIndex = tab,
                        containerColor = SurfDark,
                        contentColor = Cyan,
                    ) {
                        listOf("APKs", "Files", "Build log").forEachIndexed { i, title ->
                            Tab(
                                selected = tab == i,
                                onClick = { tab = i },
                                text = {
                                    Text(
                                        title,
                                        color = if (tab == i) Cyan else TextSecondary,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            state.error?.let { err ->
                Card(
                    Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Red.copy(alpha = 0.14f)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = Red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(err, color = Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = vm::clearError, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, null, tint = Red, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            when (tab) {
                0 -> ApkList(
                    artifacts = state.artifacts,
                    downloading = state.downloadingName,
                    onDownload = { a ->
                        scope.launch {
                            vm.download(a)?.let { file ->
                                snackbar.showSnackbar("Saved ${file.name}")
                            }
                        }
                    },
                    onInstall = { a ->
                        scope.launch {
                            val file = vm.download(a)
                            if (file != null) {
                                FileExport.share(
                                    context, file,
                                    "application/vnd.android.package-archive",
                                )
                            }
                        }
                    },
                    onCopyLink = { a ->
                        copyToClipboard(context, a.url)
                        scope.launch { snackbar.showSnackbar("Link copied") }
                    },
                )
                1 -> LocalFileList(
                    files = state.localFiles,
                    onShare = { FileExport.share(context, it) },
                )
                2 -> BuildLog(state.buildLog)
            }
        }
    }
}

@Composable
private fun ServerStatusStrip(online: Boolean, canBuild: Boolean, url: String) {
    val tint = when {
        online && canBuild -> Teal
        online -> Amber
        else -> Red
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (online) PingDot(color = tint, size = 5.dp)
        else Icon(Icons.Default.CloudOff, null, tint = tint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(if (online) 4.dp else 7.dp))
        Text(
            when {
                url.isBlank() -> "No build server set — add the URL in Settings"
                online && canBuild -> "Build server online · Android SDK ready"
                online -> "Server online but no Android SDK — APK builds will fail"
                else -> "Build server offline — start it on your PC"
            },
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

@Composable
private fun ApkList(
    artifacts: List<RemoteArtifact>,
    downloading: String?,
    onDownload: (RemoteArtifact) -> Unit,
    onInstall: (RemoteArtifact) -> Unit,
    onCopyLink: (RemoteArtifact) -> Unit,
) {
    if (artifacts.isEmpty()) {
        EmptyHint(
            icon = Icons.Default.Android,
            title = "No APKs yet",
            body = "Ask the AI to build an app, or run a build from the Terminal tab. " +
                "Finished APKs appear here with a download link.",
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(artifacts, key = { it.name }) { a ->
            Column(
                Modifier.fillMaxWidth()
                    .glass(shape = RoundedCornerShape(18.dp), glow = Cyan)
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Android, null, tint = Teal, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            a.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            maxLines = 1,
                        )
                        Text(
                            FileExport.humanSize(a.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                        )
                    }
                    if (downloading == a.name) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Cyan, strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    a.url,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextDisabled,
                    maxLines = 1,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onInstall(a) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.InstallMobile, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Install", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = { onDownload(a) },
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = 0.4f)),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Icon(Icons.Default.Download, "Download", modifier = Modifier.size(16.dp)) }
                    OutlinedButton(
                        onClick = { onCopyLink(a) },
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Icon(Icons.Default.Link, "Copy link", modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LocalFileList(files: List<File>, onShare: (File) -> Unit) {
    if (files.isEmpty()) {
        EmptyHint(
            icon = Icons.Default.FolderOpen,
            title = "No files yet",
            body = "Generated images, 3D models and .zip exports are saved here.",
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(files, key = { it.absolutePath }) { f ->
            Row(
                Modifier.fillMaxWidth()
                    .glass(shape = RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (f.extension.lowercase()) {
                        "apk" -> Icons.Default.Android
                        "zip" -> Icons.Default.FolderZip
                        "glb" -> Icons.Default.ViewInAr
                        "png", "jpg", "jpeg" -> Icons.Default.Image
                        else -> Icons.Default.InsertDriveFile
                    },
                    null, tint = Purple60, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodySmall, color = TextPrimary, maxLines = 1)
                    Text(
                        FileExport.humanSize(f.length()),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                    )
                }
                IconButton(onClick = { onShare(f) }) {
                    Icon(Icons.Default.Share, "Share", tint = TextSecondary, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
private fun BuildLog(log: String) {
    if (log.isBlank()) {
        EmptyHint(
            icon = Icons.Default.Terminal,
            title = "No build log",
            body = "Run a build and the full Gradle output shows up here.",
        )
        return
    }
    val scroll = rememberScrollState()
    Box(
        Modifier.fillMaxSize().padding(12.dp)
            .glass(shape = RoundedCornerShape(14.dp), fill = TermBg.copy(alpha = 0.9f))
            .padding(12.dp),
    ) {
        Text(
            log,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = TermDim,
            modifier = Modifier.verticalScroll(scroll),
        )
    }
}

@Composable
private fun EmptyHint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = TextDisabled, modifier = Modifier.size(46.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = TextDisabled,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
