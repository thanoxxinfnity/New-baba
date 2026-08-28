package com.trellis.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
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

/** One data-driven AI text tool: a system prompt + input, optionally with a
 *  choice chip row (e.g. a language or a tone) folded into the prompt. */
data class AiToolSpec(
    val key: String,
    val title: String,
    val subtitle: String,
    val placeholder: String,
    val system: String,          // may contain {opt}
    val options: List<String> = emptyList(),
    val temperature: Double = 0.9,
)

val AI_TOOLS: List<AiToolSpec> = listOf(
    AiToolSpec("ai_translate", "Translator", "Any language, instantly",
        "Text to translate…",
        "Translate the user's text into {opt}. Output ONLY the translation, nothing else.",
        listOf("Hindi", "English", "Spanish", "French", "German", "Japanese", "Arabic", "Chinese"), 0.3),
    AiToolSpec("ai_grammar", "Grammar Fixer", "Fix & polish your writing",
        "Paste text with mistakes…",
        "Fix all spelling and grammar in the user's text and improve clarity. Output ONLY the corrected text.", emptyList(), 0.3),
    AiToolSpec("ai_rewrite", "Rewriter", "Change the tone",
        "Text to rewrite…",
        "Rewrite the user's text in a {opt} tone, keeping the meaning. Output only the rewrite.",
        listOf("Formal", "Casual", "Confident", "Friendly", "Shorter", "Funnier"), 0.7),
    AiToolSpec("ai_summarize", "Summarizer", "Long text → key points",
        "Paste a long text or article…",
        "Summarize the user's text into 4-6 clear bullet points.", emptyList(), 0.4),
    AiToolSpec("ai_email", "Email Writer", "Professional emails fast",
        "What's the email about? (e.g. ask boss for leave)",
        "Write a clear, polite, professional email for the user's situation, with a subject line.", emptyList(), 0.7),
    AiToolSpec("ai_code", "Code Helper", "Explain or write code",
        "Paste code, or ask a coding question…",
        "You are an expert programmer. Help with the user's code question: explain, debug or write clean code with a short explanation.", emptyList(), 0.3),
    AiToolSpec("ai_story", "Story Writer", "Short stories on demand",
        "A story idea, genre or characters…",
        "Write a short, vivid, engaging story (200-300 words) from the user's idea.", emptyList(), 1.0),
    AiToolSpec("ai_shayari", "Shayari / Poem", "Words for every mood",
        "A topic or feeling (love, dosti, sad)…",
        "Write a beautiful {opt} shayari/poem on the user's topic. Keep it heartfelt.",
        listOf("Hindi", "Romantic", "Sad", "Funny", "Motivational", "English"), 1.0),
    AiToolSpec("ai_recipe", "Recipe Maker", "Cook with what you have",
        "Ingredients you have…",
        "Create a simple, tasty recipe using mainly the user's ingredients. Give steps and rough times.", emptyList(), 0.7),
    AiToolSpec("ai_notes", "Study Notes", "Learn any topic fast",
        "A topic to study…",
        "Explain the topic as clear, well-structured study notes with headings, simple language and a 3-point recap.", emptyList(), 0.4),
    AiToolSpec("ai_bio", "Bio Writer", "Standout profile bios",
        "About you (job, hobbies, vibe)…",
        "Write 3 short, catchy bio options for the user's social profile in a {opt} style.",
        listOf("Cool", "Professional", "Funny", "Aesthetic", "Gamer"), 0.9),
    AiToolSpec("ai_caption", "Captions & Tags", "Instagram-ready",
        "What's the photo/post about?",
        "Write 5 catchy social-media captions plus a set of 15 relevant hashtags for the topic.", emptyList(), 0.9),
    AiToolSpec("ai_pickup", "Pickup Lines", "Smooth (or cheesy)",
        "A theme (optional): coffee, gamer, gym…",
        "Give 5 clever, fun, clean pickup lines on the theme.", emptyList(), 1.1),
    AiToolSpec("ai_dream", "Dream Decoder", "What did it mean?",
        "Describe your dream…",
        "Interpret the user's dream in a thoughtful, symbolic, positive way (a few short paragraphs).", emptyList(), 0.9),
    AiToolSpec("ai_ideas", "Idea Generator", "Startup & project ideas",
        "A niche or interest (e.g. fitness, AI)…",
        "Generate 6 creative, practical app/business/project ideas for the niche, each with a one-line pitch.", emptyList(), 1.0),
    AiToolSpec("ai_rap", "Rap / Lyrics", "Bars on any topic",
        "A topic or vibe…",
        "Write a short, punchy {opt} rap/song verse (8-12 lines) on the topic with rhythm and rhyme.",
        listOf("Rap", "Hindi Rap", "Pop", "Sad", "Hype"), 1.1),
    AiToolSpec("ai_interview", "Interview Prep", "Ace the questions",
        "The job/role you're applying for…",
        "List 8 likely interview questions for this role, each with a concise strong sample answer.", emptyList(), 0.5),
    AiToolSpec("ai_horoscope", "Horoscope", "Your day ahead",
        "Anything specific? (optional)",
        "Give a fun, uplifting daily horoscope for {opt}, covering mood, luck and one tip.",
        listOf("Aries", "Taurus", "Gemini", "Cancer", "Leo", "Virgo", "Libra", "Scorpio", "Sagittarius", "Capricorn", "Aquarius", "Pisces"), 0.9),
    AiToolSpec("ai_eli5", "Explain Simply", "Understand anything",
        "Something confusing to explain…",
        "Explain the user's topic like they're 5 years old — simple words, a everyday analogy, short.", emptyList(), 0.5),
    AiToolSpec("ai_names", "Name Generator", "Names with meaning",
        "What to name? (baby, brand, pet, character)",
        "Suggest 10 great names for the user's request, each with a short meaning or reason.", emptyList(), 1.0),
)

@Composable
fun AiToolScreen(spec: AiToolSpec, vm: AiFunViewModel = viewModel(), onMenu: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    val clip = LocalClipboardManager.current
    var input by remember(spec.key) { mutableStateOf("") }
    var opt by remember(spec.key) { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        ToolHeader(spec.title, spec.subtitle, onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            if (spec.options.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    spec.options.forEachIndexed { i, o ->
                        FilterChip(selected = opt == i, onClick = { opt = i }, label = { Text(o) })
                    }
                }
            }
            OutlinedTextField(value = input, onValueChange = { input = it },
                placeholder = { Text(spec.placeholder, color = TextDisabled) },
                label = { Text("Input", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth().height(130.dp), shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Purple60, unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedContainerColor = CardDark, unfocusedContainerColor = CardDark))

            Button(onClick = {
                val sys = spec.system.replace("{opt}", spec.options.getOrNull(opt) ?: "")
                val user = input.ifBlank { spec.options.getOrNull(opt) ?: "surprise me" }
                vm.run(sys, user, spec.temperature)
            }, enabled = !s.busy, colors = ButtonDefaults.buttonColors(containerColor = Purple40, disabledContainerColor = CardHigh),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (s.busy) { CircularProgressIndicator(Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Thinking…", color = TextPrimary) }
                else { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Generate", fontWeight = FontWeight.Bold) }
            }
            s.error?.let { Text(it, color = Pink, style = MaterialTheme.typography.bodySmall) }
            if (s.output.isNotBlank()) {
                Box(Modifier.fillMaxWidth().background(CardDark, RoundedCornerShape(16.dp)).padding(18.dp)) {
                    Text(s.output, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                }
                TextButton(onClick = { clip.setText(AnnotatedString(s.output)) }) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copy")
                }
            }
        }
    }
}
