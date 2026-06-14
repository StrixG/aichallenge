package me.obrekht.wishu.data

import me.obrekht.wishu.network.ChatMessage

// The agent's persisted compression state: the running summary text and how many of the saved
// chat_messages rows it already folds in.
data class SavedSummary(val summary: String, val summarizedCount: Int)

// Persists the chat transcript (user/assistant turns) and the compression summary so context
// survives app restarts. Maps between Room entities and the agent's network ChatMessage so Room
// types stay out of the agent.
class ChatHistoryRepository(
    private val dao: ChatMessageDao,
    private val summaryDao: ChatSummaryDao
) {

    suspend fun load(): List<ChatMessage> =
        dao.getAll().map { ChatMessage(role = it.role, content = it.content) }

    suspend fun append(role: String, content: String) =
        dao.insert(ChatMessageEntity(role = role, content = content))

    suspend fun loadSummary(): SavedSummary? =
        summaryDao.get()?.let { SavedSummary(it.summary, it.summarizedCount) }

    suspend fun saveSummary(summary: String, summarizedCount: Int) =
        summaryDao.upsert(ChatSummaryEntity(summary = summary, summarizedCount = summarizedCount))

    suspend fun clear() {
        dao.clear()
        summaryDao.clear()
    }
}
