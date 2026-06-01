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

    suspend fun generateWishIdea(): String {
        val request = ChatRequest(
            model = "deepseek-chat",
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = "Suggest one wishlist item in one short sentence. Reply with just the item, no explanation."
                )
            ),
            maxTokens = 50
        )
        return deepSeekApi.chatCompletions(request).choices.first().message.content.trim()
    }
}
