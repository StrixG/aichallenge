package me.obrekht.wishu.data

import me.obrekht.wishu.network.ChatMessage

// Persists the chat transcript (user/assistant turns) so context survives app restarts.
// Maps between the Room entity and the agent's network ChatMessage so Room types stay out of the agent.
class ChatHistoryRepository(private val dao: ChatMessageDao) {

    suspend fun load(): List<ChatMessage> =
        dao.getAll().map { ChatMessage(role = it.role, content = it.content) }

    suspend fun append(role: String, content: String) =
        dao.insert(ChatMessageEntity(role = role, content = content))

    suspend fun clear() = dao.clear()
}
