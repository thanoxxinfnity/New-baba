package com.trellis.studio.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trellis.studio.ui.theme.SurfDark
import com.trellis.studio.ui.theme.TextPrimary
import com.trellis.studio.ui.theme.TextSecondary

/** Shared top bar for the small tool screens. */
@Composable
fun ToolHeader(title: String, subtitle: String? = null, onMenu: () -> Unit) {
    Surface(color = SurfDark) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuButton(onMenu)
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                if (subtitle != null)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}
