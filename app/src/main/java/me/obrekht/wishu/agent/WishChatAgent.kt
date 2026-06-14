package me.obrekht.wishu.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import me.obrekht.wishu.network.ApiErrorEnvelope
import me.obrekht.wishu.network.ChatMessage
import me.obrekht.wishu.network.ChatRequest
import me.obrekht.wishu.network.ChatResponse
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
        "Always reply in the same language as the user's most recent message."

// Compression is always summarized by the cheap flash model — the work is easy and this keeps the
// overhead tiny next to the savings on the main chat prompt.
const val SUMMARY_MODEL = "deepseek-v4-flash"

private const val SUMMARY_SYSTEM_PROMPT =
    "You compress a wishlist brainstorming chat into one compact running summary. Merge the " +
        "existing summary and the new messages into a single updated summary of at most ~120 words. " +
        "Capture: who the gift is for, the occasion, budget, stated likes/dislikes and constraints, " +
        "and concrete items already proposed. Be terse and factual, no preamble. " +
        "Output only the summary text, in the language the conversation uses."

// Keep the last 6 raw messages (3 exchanges) verbatim; once the un-folded tail grows past 10
// messages, fold the oldest down into the running summary.
private const val KEEP_RECENT = 6
private const val FOLD_THRESHOLD = 10

/**
 * The agent: owns the multi-turn conversation and the DeepSeek request/response logic.
 * Callers only see [send] / [restore] / [reset] — they never build a [ChatRequest] themselves.
 *
 * History compression: instead of re-sending the whole dialog every turn, the agent keeps only the
 * last [KEEP_RECENT] raw turns ([recent]) plus a running [summary] of everything folded away. When
 * compression is on and the raw tail grows past [FOLD_THRESHOLD], the oldest turns are summarized
 * (one cheap flash call) and dropped from [recent]. The request then carries `system + summary +
 * recent` instead of the full transcript, so the prompt stays bounded as the dialog grows.
 *
 * Everything lives in memory; [restore] re-seeds the summary + recent tail loaded from storage so
 * context survives an app restart.
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

    // Recent raw user/assistant turns (no system prompt). The compressed working set.
    private val recent = mutableListOf<ChatMessage>()

    // Running summary of the folded-away older turns, and how many persisted full-log messages it
    // already covers (so [restore] can split the saved transcript into folded + recent tail).
    var summary: String? = null
        private set
    var summarizedCount: Int = 0
        private set

    // Running token accounting across the dialog (resets each session, like the conversation).
    val ledger = TokenLedger()

    /** Re-seed the summary + recent tail from storage. */
    fun restore(summary: String?, summarizedCount: Int, recentMessages: List<ChatMessage>) {
        this.summary = summary
        this.summarizedCount = summarizedCount
        recent.clear()
        recent.addAll(recentMessages)
    }

    /** Wipe the conversation back to a fresh session: no summary, empty tail + ledger. */
    fun reset() {
        recent.clear()
        summary = null
        summarizedCount = 0
        ledger.clear()
    }

    /**
     * Appends the user turn, streams the assistant reply ([ChatEvent.Token] per delta), then emits
     * one [ChatEvent.Complete] with the turn's token accounting. When [compress] is on, older turns
     * may be folded into [summary] after a successful reply.
     */
    fun send(
        userMessage: String,
        model: String,
        compress: Boolean
    ): Flow<ChatEvent> = flow {
        val userTurn = ChatMessage(role = "user", content = userMessage)

        // The request carries the base prompt, the running summary (if any), the recent raw tail,
        // and the new user turn. With compression off no fold ever runs, so `recent` is the whole
        // dialog and this is the full transcript — the uncompressed baseline.
        val payloadMessages = buildList {
            add(ChatMessage(role = "system", content = systemPrompt))
            summary?.let { add(ChatMessage(role = "system", content = summaryContext(it))) }
            addAll(recent)
            add(userTurn)
        }

        // The turn is committed only AFTER a successful reply (below), so a rejected request —
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
        recent.add(userTurn)
        recent.add(ChatMessage(role = "assistant", content = full.toString()))

        // Fold older turns into the summary once the raw tail outgrows the window (compression on).
        val summaryUsage = if (compress && recent.size > FOLD_THRESHOLD) foldOldest() else null

        // Exact accounting from DeepSeek's usage chunk. With include_usage=true it's always present;
        // if it's somehow missing we just skip the token panel for this turn rather than guess.
        usage?.let {
            emit(ChatEvent.Complete(ledger.record(model, it, summaryUsage), summarized = summaryUsage != null))
        }
    }.flowOn(Dispatchers.IO)

    // Fold the oldest (recent.size - KEEP_RECENT) turns into the running summary and drop them from
    // the tail. Returns the summarization call's usage so the turn's accounting can include it.
    private fun foldOldest(): Usage? {
        val foldCount = recent.size - KEEP_RECENT
        if (foldCount <= 0) return null
        val toFold = recent.take(foldCount)
        val (updated, summaryUsage) = summarize(summary, toFold)
        summary = updated
        summarizedCount += foldCount
        val kept = recent.drop(foldCount)
        recent.clear()
        recent.addAll(kept)
        return summaryUsage
    }

    // One non-streaming flash call: merge the existing summary + the folded turns into a new summary.
    private fun summarize(existing: String?, toFold: List<ChatMessage>): Pair<String?, Usage?> {
        val instruction = buildString {
            existing?.let { append("Running summary so far:\n").append(it).append("\n\n") }
            append("New messages to fold into the summary:\n")
            toFold.forEach { append(it.role).append(": ").append(it.content).append('\n') }
        }
        val payload = ChatRequest(
            model = SUMMARY_MODEL,
            messages = listOf(
                ChatMessage(role = "system", content = SUMMARY_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = instruction)
            ),
            maxTokens = 1024,
            stream = false
        )
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw mapApiError(response.code, response.body?.string().orEmpty())
            val body = json.decodeFromString<ChatResponse>(response.body.string())
            val text = body.choices.firstOrNull()?.message?.content?.trim()
            // If the summary call returns nothing usable, keep the existing summary unchanged.
            return (text?.takeIf { it.isNotBlank() } ?: existing) to body.usage
        }
    }

    private fun summaryContext(text: String): String =
        "Summary of the earlier conversation (older turns were compressed to save tokens):\n$text"

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
