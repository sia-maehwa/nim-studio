package com.siamaehwa.nimstudio.core.db

import kotlinx.coroutines.flow.Flow

data class AgentSessionSummary(
    val id: Long,
    val title: String,
    val model: String,
    val workspaceName: String,
    val updatedAt: Long,
    val messageCount: Int,
)

class AgentHistoryRepository(private val dao: AgentHistoryDao) {

    fun observeSessions(): Flow<List<AgentSessionSummary>> = dao.observeSessions()

    suspend fun createSession(
        title: String,
        model: String,
        workspaceName: String,
        workspaceUri: String? = null,
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insertSession(
            AgentSessionEntity(
                title = title,
                model = model,
                workspaceName = workspaceName,
                workspaceUri = workspaceUri,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun append(sessionId: Long, kind: String, text: String, toolName: String? = null) {
        val now = System.currentTimeMillis()
        dao.insertMessage(
            AgentMessageEntity(
                sessionId = sessionId,
                kind = kind,
                text = text.take(MAX_TEXT),
                toolName = toolName,
                createdAt = now,
            ),
        )
        dao.touch(sessionId, now)
    }

    suspend fun messages(sessionId: Long): List<AgentMessageEntity> = dao.messages(sessionId)

    suspend fun session(sessionId: Long): AgentSessionEntity? = dao.session(sessionId)

    suspend fun saveApiHistory(sessionId: Long, apiJson: String) {
        dao.updateApiHistory(sessionId, apiJson, System.currentTimeMillis())
    }

    suspend fun delete(sessionId: Long) = dao.delete(sessionId)

    private companion object {
        const val MAX_TEXT = 8000
    }
}
