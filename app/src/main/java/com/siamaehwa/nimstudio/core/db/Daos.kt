package com.siamaehwa.nimstudio.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun messages(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LibraryDao {

    @Query(
        "SELECT * FROM library_items " +
            "WHERE (:kind = '' OR kind = :kind) " +
            "AND (:query = '' OR title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') " +
            "ORDER BY createdAt DESC",
    )
    fun observe(kind: String, query: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE id = :id")
    suspend fun find(id: Long): LibraryItemEntity?

    @Insert
    suspend fun insert(item: LibraryItemEntity): Long

    @Query("DELETE FROM library_items WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AgentHistoryDao {

    @Query(
        "SELECT s.id, s.title, s.model, s.workspaceName, s.updatedAt, " +
            "(SELECT COUNT(*) FROM agent_messages m WHERE m.sessionId = s.id) AS messageCount " +
            "FROM agent_sessions s ORDER BY s.updatedAt DESC",
    )
    fun observeSessions(): Flow<List<AgentSessionSummary>>

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun messages(sessionId: Long): List<AgentMessageEntity>

    @Query("SELECT * FROM agent_sessions WHERE id = :id")
    suspend fun session(id: Long): AgentSessionEntity?

    @Insert
    suspend fun insertSession(session: AgentSessionEntity): Long

    @Insert
    suspend fun insertMessage(message: AgentMessageEntity): Long

    @Query("UPDATE agent_sessions SET apiJson = :apiJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateApiHistory(id: Long, apiJson: String, updatedAt: Long)

    @Query("UPDATE agent_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM agent_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}
