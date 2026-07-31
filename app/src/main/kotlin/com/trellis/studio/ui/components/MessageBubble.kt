package com.trellis.studio.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.trellis.studio.ui.theme.*

@Composable
fun MessageBubble(
    role: String,        // "user" | "assistant"
    content: String,
    reasoning: String? = null,
    imagePath: String? = null,
    onCopy: (String) -> Unit = {},
) {
    val isUser = role == "user"
    val context = LocalContext.current
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
            // Images render inline for both sides: the user's attachment and
            // any picture the assistant generated.
            if (imagePath != null) {
                AsyncImage(
                    model = imagePath,
                    contentDescription = if (isUser) "Attached image" else "Generated image",
                    modifier = Modifier
                        .then(if (isUser) Modifier.size(150.dp) else Modifier.size(260.dp))
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(6.dp))
            }
            // Reasoning block — collapsed "Thought for …" chip, tap to expand.
            if (!isUser && !reasoning.isNullOrBlank()) {
                ThinkingBubble(reasoning = reasoning, streaming = false, elapsedSeconds = 0)
                Spacer(Modifier.height(4.dp))
            }
            // Main bubble
            if (content.isNotBlank()) {
                if (isUser) {
                    Box(
                        Modifier
                            .widthIn(max = 300.dp)
                            .glass(
                                shape = RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp),
                                fill = UserBubble.copy(alpha = 0.85f),
                                border = Purple60.copy(alpha = 0.30f),
                            )
                            .padding(12.dp)
                    ) {
                        Text(content, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    }
                } else {
                    // Assistant text is markdown: prose plus copyable code blocks.
                    Box(
                        Modifier
                            .widthIn(max = 330.dp)
                            .glass(shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp))
                            .padding(12.dp)
                    ) {
                        MarkdownText(text = content, onCopyFeedback = onCopy)
                    }
                    // Copy the whole reply
                    TextButton(
                        onClick = { copyToClipboard(context, content); onCopy("Message copied") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Default.ContentCopy, null, tint = TextDisabled, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
                    }
                }
            }
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
