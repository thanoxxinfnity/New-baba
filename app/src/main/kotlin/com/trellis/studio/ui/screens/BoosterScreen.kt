package com.trellis.studio.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.BoosterViewModel

/**
 * Game Booster: shows live RAM and frees background memory so games have more
 * headroom. Honest by design — it reports the RAM it actually recovers, and the
 * background mode is a visible foreground service, not a hidden drain.
 */
@Composable
fun BoosterScreen(
    vm: BoosterViewModel = viewModel(),
    onMenu: () -> Unit = {},
) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuButton(onMenu)
                Column(Modifier.weight(1f)) {
                    Text("Game Booster", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text("Free memory for smoother games", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                Icon(Icons.Default.Bolt, null, tint = Pink, modifier = Modifier.size(22.dp))
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- RAM gauge ----
            val target = s.mem.usedPercent / 100f
            val pct by animateFloatAsState(target, label = "ram")
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.mem.usedPercent}%", style = MaterialTheme.typography.displaySmall,
                        color = if (s.mem.usedPercent > 85) Pink else Cyan, fontWeight = FontWeight.Bold)
                    Text("RAM in use", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)),
                        color = if (s.mem.usedPercent > 85) Pink else Cyan,
                        trackColor = CardHigh,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("${s.mem.availMb} MB free of ${s.mem.totalMb} MB",
                        color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    if (s.mem.low) Text("Android reports low memory", color = Pink,
                        style = MaterialTheme.typography.labelSmall)
                }
            }

            // ---- Boost now ----
            Button(
                onClick = { vm.boostNow() },
                enabled = !s.boosting,
                colors = ButtonDefaults.buttonColors(containerColor = Purple40, disabledContainerColor = CardHigh),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                if (s.boosting) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Boosting…", color = TextPrimary)
                } else {
                    Icon(Icons.Default.CleaningServices, null, modifier = Modifier.size(20.dp), tint = TextPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Boost now", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }
            s.lastFreedMb?.let { freed ->
                Text(
                    if (freed > 0) "Freed ${freed} MB from ${s.lastTrimmed ?: 0} background apps."
                    else "Already lean — nothing extra to free right now.",
                    color = Teal, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- Auto boost ----
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-boost in background", color = TextPrimary,
                                style = MaterialTheme.typography.bodyLarge)
                            Text("Runs a boost every ${s.intervalMin} min and shows free RAM in a notification.",
                                color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(
                            checked = s.auto,
                            onCheckedChange = { vm.setAuto(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Cyan, checkedTrackColor = Cyan.copy(alpha = 0.35f),
                            ),
                        )
                    }
                    if (s.auto) {
                        Text("Interval: ${s.intervalMin} min", color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = s.intervalMin.toFloat(),
                            onValueChange = { vm.setInterval(it.toInt()) },
                            valueRange = 1f..15f,
                            steps = 13,
                            colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = BorderDark),
                        )
                    }
                }
            }

            // ---- Honest note ----
            Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = Cyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("How it really works", color = TextPrimary, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        "Boost frees other apps' cached background memory so your game has more " +
                            "room — it never closes the game or foreground apps. Android re-caches " +
                            "over time, so boosting helps for a while rather than permanently. The " +
                            "freed number is measured before/after, not invented.",
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
