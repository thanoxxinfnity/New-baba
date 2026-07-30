package com.trellis.studio.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.trellis.studio.ui.theme.*

@Composable
fun MessageBubble(
    role: String,        // "user" | "assistant"
    content: String,
    reasoning: String? = null,
    imagePath: String? = null,
) {
    val isUser = role == "user"
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Purple40),
                contentAlignment = Alignment.Center,
            ) { Text("T", style = MaterialTheme.typography.labelMedium, color = Color.White) }
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            // Attached image (user only)
            if (isUser && imagePath != null) {
                AsyncImage(
                    model = imagePath,
                    contentDescription = "Image",
                    modifier = Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(4.dp))
            }
            // Reasoning block (collapsible)
            if (!isUser && !reasoning.isNullOrBlank()) {
                ReasoningBlock(reasoning)
                Spacer(Modifier.height(4.dp))
            }
            // Main bubble
            if (content.isNotBlank()) {
                Box(
                    Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(
                            topStart = if (isUser) 18.dp else 4.dp,
                            topEnd   = if (isUser) 4.dp  else 18.dp,
                            bottomStart = 18.dp, bottomEnd = 18.dp,
                        ))
                        .background(if (isUser) UserBubble else AssistBubble)
                        .padding(12.dp)
                ) {
                    Text(
                        content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(reasoning: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.widthIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardDark)
            .padding(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = Teal, modifier = Modifier.weight(1f))
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(20.dp)) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = TextSecondary, modifier = Modifier.size(16.dp),
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Text(reasoning, style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Typing indicator dots */
@Composable
fun TypingIndicator() {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Purple40), contentAlignment = Alignment.Center) {
            Text("T", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(18.dp)).background(AssistBubble).padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) {
                    Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(TextSecondary))
                }
            }
        }
    }
}
