package com.example.data.local

import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun observeConversations(): Flow<List<ConversationEntity>>
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>
    suspend fun getMessagesOnce(conversationId: Long): List<MessageEntity>
    suspend fun createConversation(title: String, languageCode: String): Long
    suspend fun updateConversationTitle(conversationId: Long, title: String)
    suspend fun addMessage(
        conversationId: Long,
        role: MessageRole,
        content: String,
        isError: Boolean = false,
        actionTag: String? = null
    ): Long
    suspend fun deleteConversation(conversationId: Long)
    suspend fun clearAllConversations()
}

class RoomConversationRepository(
    private val dao: ConversationDao
) : ConversationRepository {

    override fun observeConversations(): Flow<List<ConversationEntity>> =
        dao.observeConversations()

    override fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        dao.observeMessages(conversationId)

    override suspend fun getMessagesOnce(conversationId: Long): List<MessageEntity> =
        dao.getMessagesOnce(conversationId)

    override suspend fun createConversation(title: String, languageCode: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertConversation(
            ConversationEntity(
                title = title.trim().take(64).ifBlank { "Aria Chat" },
                languageCode = languageCode,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    override suspend fun updateConversationTitle(conversationId: Long, title: String) {
        dao.updateConversationHeader(
            id = conversationId,
            title = title.trim().take(64),
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun addMessage(
        conversationId: Long,
        role: MessageRole,
        content: String,
        isError: Boolean,
        actionTag: String?
    ): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                timestamp = now,
                isError = isError,
                actionTag = actionTag
            )
        )
        dao.touchConversation(conversationId, now)
        return id
    }

    override suspend fun deleteConversation(conversationId: Long) {
        dao.deleteConversationById(conversationId)
    }

    override suspend fun clearAllConversations() {
        dao.deleteAllConversations()
    }
}
