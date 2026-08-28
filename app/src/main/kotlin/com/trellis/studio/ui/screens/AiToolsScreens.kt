package com.trellis.studio.ui.screens

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trellis.studio.ui.components.ToolHeader
import com.trellis.studio.ui.theme.*
import com.trellis.studio.viewmodel.AiFunViewModel

@Composable
private fun AiOutput(vm: AiFunViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val clip = LocalClipboardManager.current
    if (s.busy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Cyan, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp)); Text("Thinking…", color = Cyan)
        }
    }
    s.error?.let { Text(it, color = Pink, style = MaterialTheme.typography.bodySmall) }
    if (s.output.isNotBlank()) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Box(Modifier.fillMaxWidth().background(CardDark, RoundedCornerShape(16.dp)).padding(18.dp)) {
                Text(s.output, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = { clip.setText(AnnotatedString(s.output)) }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copy")
            }
        }
    }
}

// ─────────────────────────── Gamertag Generator ───────────────────────────
@Composable
fun GamertagScreen(vm: AiFunViewModel = viewModel(), onMenu: () -> Unit = {}) {
    var vibe by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Gamertag Gen", "Cool names for games & socials", onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(value = vibe, onValueChange = { vibe = it },
                label = { Text("A vibe or word (e.g. dragon, sniper, chill)", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            Button(onClick = {
                vm.run("You generate cool, memorable gamertags/usernames. Output 10 as a numbered list, " +
                    "short and stylish, mixing the theme with cool spellings. No explanations.",
                    "Theme: ${vibe.ifBlank { "anything cool" }}")
            }, colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.SportsEsports, null); Spacer(Modifier.width(8.dp)); Text("Generate 10 names")
            }
            AiOutput(vm)
        }
    }
}

// ─────────────────────────── Meme Caption ─────────────────────────────────
@Composable
fun MemeCaptionScreen(vm: AiFunViewModel = viewModel(), onMenu: () -> Unit = {}) {
    var topic by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("Meme Captions", "Instant funny captions", onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(value = topic, onValueChange = { topic = it },
                label = { Text("What's the meme about?", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            Button(onClick = {
                vm.run("You are a meme caption writer. Give 5 short, punchy, funny captions as a numbered " +
                    "list for the topic. Keep them relatable and clean.", topic.ifBlank { "Monday mornings" })
            }, colors = ButtonDefaults.buttonColors(containerColor = Purple40),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.EmojiEmotions, null); Spacer(Modifier.width(8.dp)); Text("Make captions")
            }
            AiOutput(vm)
        }
    }
}

// ─────────────────────── Quick AI (Roast/Joke/Fortune) ────────────────────
@Composable
fun QuickAiScreen(vm: AiFunViewModel = viewModel(), onMenu: () -> Unit = {}) {
    var subject by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader("AI Fun", "Roast, joke, fortune & more", onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = subject, onValueChange = { subject = it },
                label = { Text("A name or topic (optional)", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            val subj = subject.ifBlank { "me" }
            FlowButtons(
                "🔥 Roast" to {
                    vm.run("Give a short, hilarious but light-hearted roast. Keep it playful, never truly mean.", subj)
                },
                "😂 Joke" to {
                    vm.run("Tell one short, clever joke about the topic.", subj)
                },
                "🔮 Fortune" to {
                    vm.run("Give a fun, mysterious fortune-cookie style prediction (1-2 sentences).", subj)
                },
                "💪 Pep talk" to {
                    vm.run("Give a short, powerful motivational pep talk (2-3 sentences).", subj)
                },
                "✨ Compliment" to {
                    vm.run("Give a warm, creative, specific compliment (1-2 sentences).", subj)
                },
                "🧠 Fun fact" to {
                    vm.run("Share one surprising, true fun fact about the topic.", subj)
                },
            )
            AiOutput(vm)
        }
    }
}

@Composable
private fun FlowButtons(vararg items: Pair<String, () -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.toList().chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, action) ->
                    FilledTonalButton(onClick = action, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = CardHigh),
                        shape = RoundedCornerShape(14.dp)) {
                        Text(label, color = TextPrimary, fontWeight = FontWeight.Medium)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
