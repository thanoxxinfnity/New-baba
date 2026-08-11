package com.trellis.studio.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Feature: Idea Lab — tap the dice for a fresh, vivid prompt to create from. */
@Composable
fun IdeaLabScreen(onMenu: () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val spin = remember { Animatable(0f) }
    var idea by remember { mutableStateOf(roll()) }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MenuButton(onMenu)
                Column(Modifier.weight(1f)) {
                    Text("Idea Lab", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text("Never stare at a blank prompt again", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {

            Spacer(Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
            ) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(idea, color = TextPrimary,
                        style = MaterialTheme.typography.headlineSmall.copy(brush = NeonBrush),
                        fontWeight = FontWeight.SemiBold)
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        spin.snapTo(0f)
                        idea = roll()
                        spin.animateTo(360f, androidx.compose.animation.core.tween(500))
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Default.Casino, null, tint = TextPrimary,
                    modifier = Modifier.size(24.dp).rotate(spin.value))
                Spacer(Modifier.width(10.dp))
                Text("Surprise me", color = TextPrimary, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(idea)) },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
            ) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy — paste it into Create or Game Studio")
            }
            Text("Tip: copy an idea, then generate an image, a 3D model, or a game from it.",
                color = TextDisabled, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private val subjects = listOf(
    "a neon samurai", "a cozy floating island", "a cyberpunk street food cart", "a crystal dragon",
    "a retro space station", "an ancient robot guardian", "a mushroom village", "a glass koi fish",
    "a desert nomad's hover-bike", "a haunted lighthouse", "a tiny astronaut", "a jungle temple",
    "a chrome sports car", "a magic bookstore", "a steampunk owl", "a lava golem",
)
private val styles = listOf(
    "in cinematic lighting", "as a low-poly game asset", "in watercolor", "in vaporwave colors",
    "as a Pixar-style render", "in dramatic noir", "with volumetric fog", "in golden hour",
    "as a holographic sculpture", "in bioluminescent glow",
)
private val extras = listOf(
    "highly detailed, 8k", "ultra sharp, trending", "soft depth of field", "rich textures",
    "epic wide shot", "studio product shot",
)

private fun roll(): String {
    val s = subjects[Random.nextInt(subjects.size)]
    val st = styles[Random.nextInt(styles.size)]
    val ex = extras[Random.nextInt(extras.size)]
    return "$s $st, $ex"
}
