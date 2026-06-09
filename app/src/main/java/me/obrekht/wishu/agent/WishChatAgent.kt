package me.obrekht.wishu.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import me.obrekht.wishu.network.ChatMessage
import me.obrekht.wishu.network.ChatRequest
import me.obrekht.wishu.network.StreamChunk
import me.obrekht.wishu.network.StreamOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val ENDPOINT = "https://api.deepseek.com/chat/completions"

const val CHAT_SYSTEM_PROMPT =
    "You are Wishu's wishlist assistant. Help the user brainstorm realistic, desirable " +
        "wishlist and gift ideas through natural conversation. Keep replies concise. " +
        "When you propose concrete items the user could add to their wishlist, put each on " +
        "its own line prefixed with \"- \" and keep each item under about 6 words. " +
        "Always reply in the same language as the user's most recent message."

/**
 * The agent: owns the multi-turn conversation history and the DeepSeek request/response logic.
 * Callers only see [send] / [transcript] — they never build a [ChatRequest] themselves.
 * History lives in memory only (no persistence); a new agent starts a fresh conversation.
 */
class WishChatAgent(
    private val client: OkHttpClient,
    systemPrompt: String = CHAT_SYSTEM_PROMPT
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val history = mutableListOf(ChatMessage(role = "system", content = systemPrompt))

    // User/assistant turns only (the system prompt stays hidden from the UI).
    val transcript: List<ChatMessage> get() = history.drop(1)

    /**
     * Appends the user turn, streams the assistant reply token-by-token (one emit per delta),
     * then records the full assistant turn in history so the next call has full context.
     */
    fun send(userMessage: String, model: String): Flow<String> = flow {
        history.add(ChatMessage(role = "user", content = userMessage))
        val payload = ChatRequest(
            model = model,
            messages = history.toList(),
            maxTokens = 1000,
            stream = true,
            streamOptions = StreamOptions(includeUsage = true)
        )
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()

        val full = StringBuilder()
        client.newCall(request).execute().use { response ->
            val source = response.body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val delta = json.decodeFromString<StreamChunk>(data)
                    .choices.firstOrNull()?.delta?.content ?: continue
                if (delta.isEmpty()) continue
                full.append(delta)
                emit(delta)
            }
        }
        history.add(ChatMessage(role = "assistant", content = full.toString()))
    }.flowOn(Dispatchers.IO)

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}

private fun String.isBulletLine(): Boolean {
    val t = trimStart()
    return t.startsWith("-") || t.startsWith("•") || t.startsWith("*")
}

// Bullet lines in an assistant reply -> addable wishlist items.
fun parseWishItems(content: String): List<String> =
    content.lines()
        .filter { it.isBulletLine() }
        .map { it.trim().trimStart('-', '•', '*', ' ').trim() }
        .filter { it.isNotBlank() }

// Conversational text with the bullet lines removed (they render as add buttons instead).
fun stripWishItems(content: String): String =
    content.lines()
        .filterNot { it.isBulletLine() }
        .joinToString("\n")
        .trim()
