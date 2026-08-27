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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.DeviceViewModel
import java.util.Locale

/** Feature: Device Monitor — live RAM, battery, storage, CPU with animated bars. */
@Composable
fun DeviceScreen(vm: DeviceViewModel = viewModel(), onMenu: () -> Unit = {}) {
    val v by vm.vitals.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Surface(color = SurfDark) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MenuButton(onMenu)
                Column(Modifier.weight(1f)) {
                    Text("Device", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text("${v.model} · ${v.android}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                Icon(Icons.Default.Memory, null, tint = Cyan, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Meter("RAM", Icons.Default.Memory, v.ramUsedPct,
                "${v.ramAvailMb} MB free of ${v.ramTotalMb} MB", Cyan)
            Meter("Battery", if (v.charging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                v.batteryPct, buildString {
                    append(if (v.charging) "Charging" else "On battery")
                    if (v.tempC > 0) append(" · ${String.format(Locale.US, "%.1f", v.tempC)}°C")
                }, if (v.batteryPct < 20) Pink else Teal, invert = true)
            Meter("Storage", Icons.Default.Storage, v.storageUsedPct,
                "${String.format(Locale.US, "%.1f", v.storageFreeGb)} GB free of ${String.format(Locale.US, "%.0f", v.storageTotalGb)} GB", Purple60)

            Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeveloperBoard, null, tint = Pink, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("${v.cpuCores}-core CPU", color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Processor cores available to apps", color = TextDisabled,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Meter(label: String, icon: ImageVector, pct: Int, sub: String, color: Color, invert: Boolean = false) {
    val target = (pct / 100f).coerceIn(0f, 1f)
    val anim by animateFloatAsState(target, label = label)
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(label, color = TextPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("$pct%", color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { anim },
                modifier = Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(6.dp)),
                color = color, trackColor = CardHigh,
            )
            Text(sub, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}
