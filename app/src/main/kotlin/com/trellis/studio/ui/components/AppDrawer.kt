package com.trellis.studio.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trellis.studio.ui.theme.*

/** One row in the navigation drawer. */
data class DrawerEntry(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val hint: String = "",
    val group: String = "",
)

/**
 * Side navigation panel: every destination in one place, with a glass surface,
 * gradient brand header and a neon rail marking the active row.
 */
@Composable
fun AppDrawer(
    entries: List<DrawerEntry>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier.width(300.dp),
        drawerContainerColor = Color.Transparent,
        drawerShape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF12121C),
                            Color(0xFF0C0C14),
                            Color(0xFF0A0A11),
                        )
                    )
                )
        ) {
            BrandHeader()

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                var lastGroup = ""
                entries.forEach { entry ->
                    if (entry.group != lastGroup) {
                        lastGroup = entry.group
                        if (entry.group.isNotBlank()) {
                            Text(
                                entry.group.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    letterSpacing = 1.6.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = TextDisabled,
                                modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 6.dp),
                            )
                        }
                    }
                    DrawerRow(
                        entry = entry,
                        selected = currentRoute == entry.route,
                        onClick = { onSelect(entry.route) },
                    )
                }
            }

            HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PingDot(color = Teal, size = 5.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "NVIDIA NIM · on-device TTS · remote builds",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled,
                )
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .background(
                Brush.linearGradient(
                    listOf(
                        Purple40.copy(alpha = 0.55f),
                        Cyan.copy(alpha = 0.18f),
                        Color.Transparent,
                    )
                )
            )
    ) {
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(Purple40, Cyan))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "T",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text(
                        "TRELLIS",
                        style = MaterialTheme.typography.titleLarge.copy(
                            brush = NeonBrush,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 3.sp,
                        ),
                    )
                    Text(
                        "STUDIO",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 5.sp),
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(entry: DrawerEntry, selected: Boolean, onClick: () -> Unit) {
    val railWidth by animateDpAsState(if (selected) 3.dp else 0.dp, label = "rail")
    val labelColor by animateColorAsState(
        if (selected) TextPrimary else TextSecondary, label = "label",
    )
    val iconColor by animateColorAsState(
        if (selected) Cyan else TextDisabled, label = "icon",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) Modifier.glass(
                    shape = RoundedCornerShape(14.dp),
                    fill = Purple40.copy(alpha = 0.22f),
                    border = Cyan.copy(alpha = 0.35f),
                    glow = Cyan,
                ) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Neon rail on the active row
        Box(
            Modifier
                .width(railWidth)
                .height(20.dp)
                .clip(CircleShape)
                .background(if (selected) Brush.verticalGradient(listOf(Cyan, Purple60)) else SolidNone)
        )
        if (selected) Spacer(Modifier.width(9.dp))

        Icon(entry.icon, null, tint = iconColor, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = labelColor,
            )
            if (entry.hint.isNotBlank()) {
                Text(
                    entry.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled,
                    maxLines = 1,
                )
            }
        }
        if (selected) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(Cyan))
        }
    }
}

private val SolidNone = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))

/** The ☰ button that opens the drawer. Shared by every screen's header. */
@Composable
fun MenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Default.Menu, "Open menu", tint = TextPrimary, modifier = Modifier.size(23.dp))
    }
}
