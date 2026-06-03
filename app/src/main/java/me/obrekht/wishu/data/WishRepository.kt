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

    suspend fun generateWishIdea(prompt: String, model: String): String {
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(
                    role = "system",
                    content = "You are a helpful assistant that suggests realistic and desirable wishlist items. Always respond with a single item only — no lists, no explanations, no punctuation at the end. Always respond in the same language as the user's message."
                ),
                ChatMessage(role = "user", content = prompt)
            ),
            maxTokens = 500
        )
        return deepSeekApi.chatCompletions(request).choices.first().message.content.trim()
    }
}
