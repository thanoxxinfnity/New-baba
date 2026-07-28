package com.trellis.studio.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** "user" or "assistant". */
    val role: String,
    val content: String,
    /** Populated only for reasoning models — shown as a collapsible "Thinking" section. */
    val reasoningContent: String?,
    val createdAt: Long,
    /** Local file path of an image attached to this message (user side only, nullable). */
    val imagePath: String? = null
) {
    companion object {
        const val ROLE_USER      = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
