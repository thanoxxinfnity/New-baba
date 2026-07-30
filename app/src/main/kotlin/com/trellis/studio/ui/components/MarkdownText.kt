package com.trellis.studio.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trellis.studio.ui.theme.*
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Markdown blocks
// ---------------------------------------------------------------------------

sealed interface MdBlock {
    data class Text(val content: String) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
}

private val FENCE = Regex("^\\s*```(\\w+)?\\s*$")

/** Splits assistant text into prose and fenced code blocks. */
fun parseMarkdown(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val text = StringBuilder()
    val code = StringBuilder()
    var inCode = false
    var lang: String? = null

    fun flushText() {
        val t = text.toString().trim('\n')
        if (t.isNotBlank()) blocks += MdBlock.Text(t)
        text.clear()
    }

    for (line in raw.lines()) {
        val fence = FENCE.matchEntire(line)
        if (fence != null) {
            if (inCode) {
                blocks += MdBlock.Code(lang, code.toString().trimEnd('\n'))
                code.clear()
                inCode = false
                lang = null
            } else {
                flushText()
                inCode = true
                lang = fence.groupValues[1].takeIf { it.isNotBlank() }
            }
        } else {
            if (inCode) code.appendLine(line) else text.appendLine(line)
        }
    }
    // Unterminated fence: still show what we have rather than losing it.
    if (inCode && code.isNotBlank()) blocks += MdBlock.Code(lang, code.toString().trimEnd('\n'))
    flushText()
    return blocks
}

/** Renders assistant markdown: prose plus copyable code blocks. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    onCopyFeedback: (String) -> Unit = {},
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Text -> Text(
                    inlineMarkdown(block.content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
                is MdBlock.Code -> CodeBlock(
                    code = block.code,
                    language = block.language,
                    onCopied = onCopyFeedback,
                )
            }
        }
    }
}

/** Handles **bold**, *italic*, `inline code` and ~~strike~~. */
fun inlineMarkdown(src: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    fun emit(marker: String, style: SpanStyle): Boolean {
        if (!src.startsWith(marker, i)) return false
        val end = src.indexOf(marker, i + marker.length)
        if (end < 0) return false
        withStyle(style) { append(src.substring(i + marker.length, end)) }
        i = end + marker.length
        return true
    }
    while (i < src.length) {
        val consumed = emit("**", SpanStyle(fontWeight = FontWeight.Bold)) ||
            emit("~~", SpanStyle(textDecoration = TextDecoration.LineThrough)) ||
            emit("`", SpanStyle(fontFamily = FontFamily.Monospace, background = CodeBg, color = Teal)) ||
            emit("*", SpanStyle(fontWeight = FontWeight.Medium, color = Purple80))
        if (!consumed) {
            append(src[i])
            i++
        }
    }
}

// ---------------------------------------------------------------------------
// Code block with one-tap copy
// ---------------------------------------------------------------------------

@Composable
fun CodeBlock(
    code: String,
    language: String? = null,
    onCopied: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var copied by remember(code) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CodeBg)
            .border(1.dp, CodeBorder, RoundedCornerShape(16.dp))
    ) {
        // Header: language + copy
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.03f))
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (language ?: "code").lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    copyToClipboard(context, code)
                    copied = true
                    onCopied("Code copied")
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                AnimatedContent(targetState = copied, label = "copy") { done ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (done) Icons.Default.Check else Icons.Default.ContentCopy,
                            null,
                            tint = if (done) Teal else TextSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (done) "Copied" else "Copy",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (done) Teal else TextSecondary,
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = CodeBorder, thickness = 0.5.dp)
        // Body — horizontally scrollable so long lines never wrap or clip.
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            Text(
                highlight(code, language),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                ),
                color = TextPrimary,
                softWrap = false,
            )
        }
    }
}

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("code", text))
}

// ---------------------------------------------------------------------------
// Lightweight syntax highlighting — good enough to read, cheap to run.
// ---------------------------------------------------------------------------

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when",
    "for", "while", "import", "package", "private", "public", "internal", "suspend",
    "override", "data", "sealed", "companion", "const", "true", "false", "null", "this",
    "def", "lambda", "elif", "try", "except", "finally", "with", "as", "from", "async",
    "await", "function", "let", "const", "export", "default", "new", "typeof", "int",
    "float", "double", "String", "Boolean", "void", "static", "final", "public", "print",
    "echo", "cd", "ls", "mkdir", "rm", "cat", "sudo", "apt", "npm", "git",
)

private val TOKEN = Regex("""("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(#[^\n]*|//[^\n]*)|(\b\d+(?:\.\d+)?\b)|(\b\w+\b)""")

private fun highlight(code: String, language: String?): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in TOKEN.findAll(code)) {
        if (m.range.first > last) append(code.substring(last, m.range.first))
        val (str, comment, num, word) = m.destructured
        when {
            str.isNotEmpty() -> withStyle(SpanStyle(color = SynString)) { append(str) }
            comment.isNotEmpty() -> withStyle(SpanStyle(color = SynComment)) { append(comment) }
            num.isNotEmpty() -> withStyle(SpanStyle(color = SynNumber)) { append(num) }
            word in KEYWORDS -> withStyle(SpanStyle(color = SynKeyword, fontWeight = FontWeight.Medium)) { append(word) }
            else -> append(m.value)
        }
        last = m.range.last + 1
    }
    if (last < code.length) append(code.substring(last))
}

