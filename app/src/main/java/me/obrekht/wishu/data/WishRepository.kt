package me.obrekht.wishu.data

import kotlinx.coroutines.flow.Flow
import me.obrekht.wishu.network.ChatMessage
import me.obrekht.wishu.network.ChatRequest
import me.obrekht.wishu.network.DeepSeekApi

class WishRepository(
    private val wishDao: WishDao,
    private val deepSeekApi: DeepSeekApi
) {
    val wishes: Flow<List<Wish>> = wishDao.getAllWishes()

    suspend fun addWish(text: String) {
        wishDao.insert(Wish(text = text))
    }

    suspend fun deleteWish(wish: Wish) {
        wishDao.delete(wish)
    }

    // Constrained: (1) explicit format, (2) length limit (max_tokens + per-item word cap),
    // (3) completion condition (stop param + explicit instruction).
    suspend fun generateWishIdeas(prompt: String, model: String): List<String> {
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(
                    role = "system",
                    content = "You are a helpful assistant that suggests realistic and desirable wishlist items. " +
                        "Always respond with exactly 3 distinct items, one per line, with no numbering, no bullets, " +
                        "no explanations, and no punctuation at the end. Each item must be at most 5 words. " +
                        "After the third item output the token \"END\" on its own line and stop. " +
                        "Always respond in the same language as the user's message."
                ),
                ChatMessage(role = "user", content = prompt)
            ),
            maxTokens = 300,
            stop = listOf("END")
        )
        val content = deepSeekApi.chatCompletions(request).choices.first().message.content
        return content.lines()
            .map { it.trim().removePrefix("-").removePrefix("•").trim().trimStart('1', '2', '3', '.', ')', ' ').trim() }
            .filter { it.isNotBlank() }
            .take(3)
    }

    // Unconstrained: bare user prompt, generous budget, no format/length/stop controls.
    suspend fun generateUnconstrained(prompt: String, model: String): String {
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            maxTokens = 2000
        )
        return deepSeekApi.chatCompletions(request).choices.first().message.content.trim()
    }
}
