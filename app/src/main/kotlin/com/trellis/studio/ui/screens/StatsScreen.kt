package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.components.animatedCount
import com.trellis.studio.ui.components.rememberAura
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.StatsViewModel

/** Feature: Creation Stats & Achievements — animated counters + unlockable badges. */
@Composable
fun StatsScreen(vm: StatsViewModel = viewModel(), onMenu: () -> Unit = {}) {
    val s by vm.stats.collectAsStateWithLifecycle()
    val aura = rememberAura()

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MenuButton(onMenu)
                Text("Your Stats", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {

            // Hero total with an aura glow
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(150.dp).alpha(aura * 0.5f).clip(RoundedCornerShape(100))
                    .background(Brush.radialGradient(listOf(Purple60, BgDark))))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${animatedCount(s.total)}",
                        style = MaterialTheme.typography.displayMedium.copy(brush = NeonBrush),
                        fontWeight = FontWeight.Bold)
                    Text("things created", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
                }
            }

            // Stat tiles
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("🖼️", animatedCount(s.images), "Images", Modifier.weight(1f))
                StatTile("🧊", animatedCount(s.models), "3D", Modifier.weight(1f))
                StatTile("🎮", animatedCount(s.games), "Games", Modifier.weight(1f))
            }

            // Streak
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalFireDepartment, null, tint = Pink, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${s.streakDays}-day streak", color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (s.streakDays > 0) "Keep creating daily to grow it 🔥"
                            else "Create something today to start a streak",
                            color = TextDisabled, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Text("Badges", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                userScrollEnabled = false,
            ) {
                items(s.badges) { b ->
                    Column(
                        Modifier.aspectRatio(0.85f).clip(RoundedCornerShape(14.dp))
                            .background(if (b.unlocked) CardHigh else CardDark)
                            .alpha(if (b.unlocked) 1f else 0.4f)
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(b.emoji, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(if (b.unlocked) b.name else b.hint, color = if (b.unlocked) TextPrimary else TextDisabled,
                            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StatTile(emoji: String, value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(CardDark).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
        Text("$value", color = Cyan, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}
