package me.obrekht.wishu.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

// Both helper calls (Summary fold, Sticky Facts refresh) use the cheap flash model — the work is
// easy and this keeps the overhead tiny next to the savings/value on the main chat prompt.
const val SUMMARY_MODEL = "deepseek-v4-flash"

private const val SUMMARY_SYSTEM_PROMPT =
    "You compress a wishlist brainstorming chat into one compact running summary. Merge the " +
        "existing summary and the new messages into a single updated summary of at most ~120 words. " +
        "Capture: who the gift is for, the occasion, budget, stated likes/dislikes and constraints, " +
        "and concrete items already proposed. Be terse and factual, no preamble. " +
        "Output only the summary text, in the language the conversation uses."

private const val FACTS_SYSTEM_PROMPT =
    "You maintain a compact key-value memory of a wishlist brainstorming chat. Given the current " +
        "facts (a JSON object) and the latest user/assistant exchange, output the UPDATED facts as " +
        "a single JSON object mapping short snake_case keys to short string values. Capture durable " +
        "facts only: recipient, occasion, budget, stated likes, dislikes, constraints, and decisions " +
        "already made. Keep at most ~12 keys; keep everything still relevant. " +
        "Output ONLY the JSON object — no prose, no code fences."

// Summary: keep the last KEEP_RECENT raw turns verbatim; once the un-folded tail grows past
// FOLD_THRESHOLD, fold the oldest into the running summary. Sliding Window / Sticky Facts send the
// last WINDOW messages.
private const val KEEP_RECENT = 6
private const val FOLD_THRESHOLD = 10
private const val WINDOW = KEEP_RECENT

/**
 * The agent: owns the active conversation transcript and the DeepSeek request/response logic.
 * Callers only see [send] / [restore] / [loadHistory] / [reset] — never a [ChatRequest].
 *
 * Context management is selected per call via [ContextStrategy], and the agent branches on it in a
 * single `when` (no class hierarchy). It always retains the full active transcript in [history]; the
 * strategies differ only in what slice (plus optional [summary] / [facts]) they put in the request:
 *  - SLIDING_WINDOW: the last [WINDOW] messages.
 *  - SUMMARY: a running summary of the older turns + the un-folded tail.
 *  - STICKY_FACTS: a key-value facts block (refreshed each turn) + the last [WINDOW] messages.
 *  - BRANCHING: the whole transcript (which branch you're on is the context lever, set by [loadHistory]).
 *
 * Everything lives in memory; [restore] / [loadHistory] re-seed it from storage so context survives
 * an app restart and branch switches.
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

    // The full active transcript (no system prompt). Every strategy derives its payload from this.
    private val history = mutableListOf<ChatMessage>()

    // SUMMARY: running summary of the folded-away older turns, and how many leading [history]
    // messages it already covers.
    var summary: String? = null
        private set
    var summarizedCount: Int = 0
        private set

    // STICKY_FACTS: key-value memory refreshed after each turn.
    private val facts = linkedMapOf<String, String>()
    val factsJson: String get() = JsonObject(facts.mapValues { JsonPrimitive(it.value) }).toString()

    // Running token accounting across the dialog (resets each session, like the conversation).
    val ledger = TokenLedger()

    /** Re-seed the full session state from storage. */
    fun restore(history: List<ChatMessage>, summary: String?, summarizedCount: Int, factsJson: String?) {
        this.history.clear()
        this.history.addAll(history)
        this.summary = summary
        this.summarizedCount = summarizedCount
        facts.clear()
        factsJson?.let { facts.putAll(parseFacts(it)) }
    }

    /** Swap the active transcript (Branching: switching to another branch's line). */
    fun loadHistory(history: List<ChatMessage>) {
        this.history.clear()
        this.history.addAll(history)
    }

    /** Wipe the conversation back to a fresh session. */
    fun reset() {
        history.clear()
        summary = null
        summarizedCount = 0
        facts.clear()
        ledger.clear()
    }

    /**
     * Appends the user turn, streams the assistant reply ([ChatEvent.Token] per delta), then emits
     * one [ChatEvent.Complete] with the turn's token accounting (and which helper call, if any, ran).
     */
    fun send(
        userMessage: String,
        model: String,
        strategy: ContextStrategy
    ): Flow<ChatEvent> = flow {
        val userTurn = ChatMessage(role = "user", content = userMessage)

        // Build the request body per strategy. The turn isn't committed to [history] until AFTER a
        // successful reply, so a rejected request never leaves a dangling turn behind.
        val payloadMessages = buildList {
            add(ChatMessage(role = "system", content = systemPrompt))
            when (strategy) {
                ContextStrategy.SUMMARY -> {
                    summary?.let { add(ChatMessage(role = "system", content = summaryContext(it))) }
                    addAll(history.drop(summarizedCount))
                }
                ContextStrategy.SLIDING_WINDOW -> addAll(history.takeLast(WINDOW))
                ContextStrategy.STICKY_FACTS -> {
                    if (facts.isNotEmpty()) {
                        add(ChatMessage(role = "system", content = factsContext()))
                    }
                    addAll(history.takeLast(WINDOW))
                }
                ContextStrategy.BRANCHING -> addAll(history)
            }
            add(userTurn)
        }

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

        // The strategy's optional helper call, run only after a successful reply.
        var auxKind = AuxKind.NONE
        val auxUsage: Usage? = when (strategy) {
            ContextStrategy.SUMMARY ->
                foldOldest()?.also { auxKind = AuxKind.SUMMARY }
            ContextStrategy.STICKY_FACTS ->
                refreshFacts(userTurn.content, full.toString())?.also { auxKind = AuxKind.FACTS }
            else -> null
        }

        // Exact accounting from DeepSeek's usage chunk. With include_usage=true it's always present;
        // if it's somehow missing we just skip the token panel for this turn rather than guess.
        usage?.let {
            emit(ChatEvent.Complete(ledger.record(model, it, auxUsage), aux = auxKind))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Streams a new assistant reply for the last user turn already in [history] — used by the
     * invisible-branching regenerate flow, where the caller has already forked to a fresh branch
     * and loaded the transcript (ending with the user turn) via [loadHistory]. Unlike [send], this
     * does not append a user turn; it only appends the assistant reply.
     */
    fun regenerate(model: String, strategy: ContextStrategy): Flow<ChatEvent> = flow {
        val payloadMessages = buildList {
            add(ChatMessage(role = "system", content = systemPrompt))
            when (strategy) {
                ContextStrategy.SUMMARY -> {
                    summary?.let { add(ChatMessage(role = "system", content = summaryContext(it))) }
                    addAll(history.drop(summarizedCount))
                }
                ContextStrategy.SLIDING_WINDOW -> addAll(history.takeLast(WINDOW))
                ContextStrategy.STICKY_FACTS -> {
                    if (facts.isNotEmpty()) {
                        add(ChatMessage(role = "system", content = factsContext()))
                    }
                    addAll(history.takeLast(WINDOW))
                }
                ContextStrategy.BRANCHING -> addAll(history)
            }
            // No add(userTurn) — history already ends with the user turn to answer.
        }

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
                chunk.usage?.let { usage = it }
                val delta = chunk.choices.firstOrNull()?.delta?.content ?: continue
                if (delta.isEmpty()) continue
                full.append(delta)
                emit(ChatEvent.Token(delta))
            }
        }
        // Only the assistant turn is committed; the user turn is already in history.
        history.add(ChatMessage(role = "assistant", content = full.toString()))

        var auxKind = AuxKind.NONE
        val auxUsage: Usage? = when (strategy) {
            ContextStrategy.SUMMARY ->
                foldOldest()?.also { auxKind = AuxKind.SUMMARY }
            ContextStrategy.STICKY_FACTS ->
                refreshFacts(history.dropLast(1).last().content, full.toString())
                    ?.also { auxKind = AuxKind.FACTS }
            else -> null
        }

        usage?.let {
            emit(ChatEvent.Complete(ledger.record(model, it, auxUsage), aux = auxKind))
        }
    }.flowOn(Dispatchers.IO)

    // SUMMARY: fold the oldest un-folded turns (beyond the KEEP_RECENT tail) into the running summary
    // once the un-folded tail outgrows FOLD_THRESHOLD. Returns the summarization call's usage.
    private fun foldOldest(): Usage? {
        val unsummarized = history.drop(summarizedCount)
        if (unsummarized.size <= FOLD_THRESHOLD) return null
        val foldCount = unsummarized.size - KEEP_RECENT
        if (foldCount <= 0) return null
        val (updated, summaryUsage) = summarize(summary, unsummarized.take(foldCount))
        summary = updated
        summarizedCount += foldCount
        return summaryUsage
    }

    // STICKY_FACTS: merge the latest exchange into the key-value facts via one flash call.
    private fun refreshFacts(userText: String, assistantText: String): Usage? {
        val instruction = buildString {
            append("Current facts:\n").append(factsJson).append("\n\n")
            append("Latest exchange:\n")
            append("user: ").append(userText).append('\n')
            append("assistant: ").append(assistantText).append('\n')
        }
        val (text, usage) = helperCall(FACTS_SYSTEM_PROMPT, instruction)
        text?.let {
            val parsed = parseFacts(it)
            if (parsed.isNotEmpty()) {
                facts.clear()
                facts.putAll(parsed)
            }
        }
        return usage
    }

    // One non-streaming flash call: merge the existing summary + the folded turns into a new summary.
    private fun summarize(existing: String?, toFold: List<ChatMessage>): Pair<String?, Usage?> {
        val instruction = buildString {
            existing?.let { append("Running summary so far:\n").append(it).append("\n\n") }
            append("New messages to fold into the summary:\n")
            toFold.forEach { append(it.role).append(": ").append(it.content).append('\n') }
        }
        val (text, usage) = helperCall(SUMMARY_SYSTEM_PROMPT, instruction)
        // If the summary call returns nothing usable, keep the existing summary unchanged.
        return (text?.takeIf { it.isNotBlank() } ?: existing) to usage
    }

    // Shared non-streaming flash request used by the summary fold and the facts refresh.
    private fun helperCall(system: String, userInstruction: String): Pair<String?, Usage?> {
        val payload = ChatRequest(
            model = SUMMARY_MODEL,
            messages = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user", content = userInstruction)
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
            return body.choices.firstOrNull()?.message?.content?.trim() to body.usage
        }
    }

    private fun summaryContext(text: String): String =
        "Summary of the earlier conversation (older turns were compressed to save tokens):\n$text"

    private fun factsContext(): String = buildString {
        append("Known facts about this conversation (carried across the whole chat):\n")
        facts.forEach { (k, v) -> append("- ").append(k).append(": ").append(v).append('\n') }
    }

    // Parse a JSON object of string values, tolerating code fences / surrounding prose by slicing
    // from the first '{' to the last '}'. Returns empty on any failure (caller keeps prior facts).
    private fun parseFacts(raw: String): Map<String, String> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        return runCatching {
            json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
                .mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

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
