package com.trellis.studio.data.db

import androidx.room.*
import com.trellis.studio.data.entity.ChatMessageEntity
import com.trellis.studio.data.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // Sessions
    @Query("SELECT * FROM chat_sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Insert
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun updateSessionTitle(id: Long, title: String)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()

    // Messages
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun getMessages(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET content = :content, reasoningContent = :reasoning WHERE id = :id")
    suspend fun updateMessage(id: Long, content: String, reasoning: String?)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: Long)
}
