package me.obrekht.wishu.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import me.obrekht.wishu.network.ApiErrorEnvelope
import me.obrekht.wishu.network.ChatMessage
import me.obrekht.wishu.network.ChatRequest
import me.obrekht.wishu.network.StreamChunk
import me.obrekht.wishu.network.StreamOptions
import me.obrekht.wishu.network.Usage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val ENDPOINT = "https://api.deepseek.com/chat/completions"

const val CHAT_SYSTEM_PROMPT =
    "You are Wishu's wishlist assistant. Help the user brainstorm realistic, desirable " +
        "wishlist and gift ideas through natural conversation. Keep replies concise. " +
        "When you propose concrete items the user could add to their wishlist, put each on " +
        "its own line prefixed with \"- \" and keep each item under about 6 words. " +
        "Always reply in the same language as the user's most recent message."

/**
 * The agent: owns the multi-turn conversation history and the DeepSeek request/response logic.
 * Callers only see [send] / [transcript] / [restore] — they never build a [ChatRequest] themselves.
 * History lives in memory; [restore] re-seeds prior turns loaded from storage so context survives
 * an app restart.
 */
class WishChatAgent(
    private val client: OkHttpClient,
    private val systemPrompt: String = CHAT_SYSTEM_PROMPT
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val history = mutableListOf(ChatMessage(role = "system", content = systemPrompt))

    // Running token accounting across the dialog (resets each session, like history).
    val ledger = TokenLedger()

    // User/assistant turns only (the system prompt stays hidden from the UI).
    val transcript: List<ChatMessage> get() = history.drop(1)

    /** Re-seed prior user/assistant turns from storage (the system prompt stays at history[0]). */
    fun restore(messages: List<ChatMessage>) {
        history.addAll(messages)
    }

    /** Wipe the conversation back to a fresh session: only the system prompt + an empty ledger. */
    fun reset() {
        history.clear()
        history.add(ChatMessage(role = "system", content = systemPrompt))
        ledger.clear()
    }

    /**
     * Appends the user turn, streams the assistant reply ([ChatEvent.Token] per delta), then emits
     * one [ChatEvent.Complete] with the turn's token accounting and records the assistant turn.
     */
    fun send(
        userMessage: String,
        model: String
    ): Flow<ChatEvent> = flow {
        val userTurn = ChatMessage(role = "user", content = userMessage)
        val payloadMessages = history + userTurn

        // History is committed only AFTER a successful reply (below), so a rejected request —
        // e.g. the real context window overflowing — never leaves a dangling turn behind.
        val payload = ChatRequest(
            model = model,
            messages = payloadMessages,
            maxTokens = 8192,
            stream = true,
            streamOptions = StreamOptions(includeUsage = true)
        )
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()

        val full = StringBuilder()
        var usage: Usage? = null
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw mapApiError(response.code, response.body?.string().orEmpty())
            val source = response.body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val chunk = json.decodeFromString<StreamChunk>(data)
                // The final usage-only chunk has the exact counts and (usually) empty choices.
                chunk.usage?.let { usage = it }
                val delta = chunk.choices.firstOrNull()?.delta?.content ?: continue
                if (delta.isEmpty()) continue
                full.append(delta)
                emit(ChatEvent.Token(delta))
            }
        }
        history.add(userTurn)
        history.add(ChatMessage(role = "assistant", content = full.toString()))

        // Exact accounting from DeepSeek's usage chunk. With include_usage=true it's always present;
        // if it's somehow missing we just skip the token panel for this turn rather than guess.
        usage?.let { emit(ChatEvent.Complete(ledger.record(model, it))) }
    }.flowOn(Dispatchers.IO)

    // Map a non-2xx DeepSeek response to an exception. A 400 whose error message mentions the
    // model's maximum context length becomes the distinct [ContextWindowExceededException];
    // anything else is a generic [IOException] (surfaces as the plain "couldn't reach" error).
    private fun mapApiError(code: Int, body: String): Exception {
        val message = runCatching { json.decodeFromString<ApiErrorEnvelope>(body).error.message }
            .getOrNull()
        return if (message?.contains("maximum context length", ignoreCase = true) == true) {
            ContextWindowExceededException(message)
        } else {
            IOException("DeepSeek error $code: ${message ?: body}")
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}

/** DeepSeek rejected the request because the prompt exceeds the model's real context window. */
class ContextWindowExceededException(message: String) : Exception(message)

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
