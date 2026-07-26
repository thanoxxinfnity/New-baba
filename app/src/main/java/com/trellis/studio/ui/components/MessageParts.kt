package com.trellis.studio.ui.components

sealed interface MessagePart {
    data class Text(val text: String) : MessagePart
    data class Code(val language: String, val code: String) : MessagePart
}

private val CODE_FENCE_REGEX = Regex("```(\\w*)\\n?([\\s\\S]*?)```")

/** Splits chat message content into plain-text and fenced-code-block segments. */
fun parseMessageParts(content: String): List<MessagePart> {
    if (content.isEmpty()) return emptyList()
    val parts = mutableListOf<MessagePart>()
    var lastIndex = 0
    for (match in CODE_FENCE_REGEX.findAll(content)) {
        if (match.range.first > lastIndex) {
            parts.add(MessagePart.Text(content.substring(lastIndex, match.range.first)))
        }
        parts.add(MessagePart.Code(match.groupValues[1], match.groupValues[2]))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < content.length) {
        parts.add(MessagePart.Text(content.substring(lastIndex)))
    }
    if (parts.isEmpty()) parts.add(MessagePart.Text(content))
    return parts
}
