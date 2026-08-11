package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trellis.studio.data.prefs.AppPrefs
import com.trellis.studio.ui.components.MenuButton
import com.trellis.studio.ui.theme.*
import kotlinx.coroutines.launch

/** Feature: AI Personas — one tap swaps the AI's vibe, then opens Chat. */
@Composable
fun PersonasScreen(onMenu: () -> Unit = {}, onOpenChat: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPrefs(context) }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = BgDark) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(BgDark)) {
            Surface(color = SurfDark) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MenuButton(onMenu)
                    Column {
                        Text("AI Vibes", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        Text("Pick a personality for the chat", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(PERSONAS) { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardDark)
                            .clickable {
                                scope.launch {
                                    prefs.setSystemPrompt(p.prompt)
                                    snackbar.showSnackbar("${p.name} vibe on — opening chat")
                                    onOpenChat()
                                }
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.emoji, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(p.tagline, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Text("Tip: switch back anytime — Settings › System Prompt resets the vibe.",
                        color = TextDisabled, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

private data class Persona(val emoji: String, val name: String, val tagline: String, val prompt: String)

private val PERSONAS = listOf(
    Persona("🧙", "Sensei", "Wise, patient mentor",
        "You are a calm, wise mentor. Explain clearly with simple analogies, encourage the user, and end with one actionable step."),
    Persona("🔥", "Roast Master", "Funny, savage (but kind)",
        "You are a witty roast comedian. Tease the user playfully and hilariously, but keep it light and never truly mean. Always end on an encouraging note."),
    Persona("💻", "Hacker Terminal", "Cyberpunk coding buddy",
        "You are a cyberpunk hacker AI. Speak in a cool, concise, techy tone with terminal vibes. Be genuinely helpful with code and tech, no illegal or harmful hacking."),
    Persona("📚", "Study Buddy", "Explains anything simply",
        "You are an upbeat study partner. Break topics into tiny steps, quiz the user gently, and use memory tricks. Keep it encouraging."),
    Persona("🚀", "Hype Coach", "Maximum motivation",
        "You are an over-the-top motivational hype coach. Fire the user up with energy and confidence, then give a concrete plan. Keep it positive."),
    Persona("🎭", "Shakespeare", "Speaks in poetic English",
        "You are a poetic bard who answers in eloquent, slightly archaic English with vivid imagery, while still being genuinely helpful and clear."),
    Persona("🕵️", "Detective", "Sharp, deductive",
        "You are a sharp detective. Reason step by step out loud, notice details, and lay out deductions before the conclusion."),
    Persona("😎", "Chill Bro", "Relaxed Hinglish buddy",
        "You are a chill, friendly buddy who talks in casual Hinglish (Hindi in English letters). Keep it warm, simple and real."),
)
