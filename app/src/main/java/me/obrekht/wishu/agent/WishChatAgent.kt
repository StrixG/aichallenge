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

// Both helper calls (Summary fold, Sticky Facts refresh) use the cheap flash model — the work is
// easy and this keeps the overhead tiny next to the savings/value on the main chat prompt.
const val SUMMARY_MODEL = "deepseek-v4-flash"

private const val SUMMARY_SYSTEM_PROMPT =
    "You compress a wishlist brainstorming chat into one compact running summary. Merge the " +
        "existing summary and the new messages into a single updated summary of at most ~120 words. " +
        "Capture: who the gift is for, the occasion, budget, stated likes/dislikes and constraints, " +
        "and concrete items already proposed. Be terse and factual, no preamble. " +
        "Output only the summary text, in the language the conversation uses."

private const val WORKING_MEMORY_PROMPT =
    "You maintain a compact key-value memory of a wishlist brainstorming chat. Given the current " +
        "facts and the latest user/assistant exchange, output the UPDATED facts in TOON " +
        "(Token-Oriented Object Notation): one 'key: value' per line, short snake_case keys, short " +
        "single-line string values, no braces/quotes/commas. Capture durable facts only: recipient, " +
        "occasion, budget, stated likes, dislikes, constraints, and decisions already made. Keep at " +
        "most ~12 keys; keep everything still relevant. Output ONLY the TOON lines — no prose, no " +
        "code fences. Example:\nrecipient: mom\noccasion: birthday\nbudget: ~3000 RUB"

private const val LONG_TERM_MEMORY_PROMPT =
    "You maintain a persistent profile of THE USER (the person chatting), across wishlist " +
        "conversations. Given the current profile (a JSON object) and the latest user/assistant " +
        "exchange, output the UPDATED profile as a single JSON object. " +
        "Use ONLY these keys when the info is known (omit any you don't know): " +
        "user_name, language, communication_style, typical_budget, user_interests. " +
        "Definitions: communication_style = how the USER wants replies (e.g. concise, formal); " +
        "user_interests = the USER's OWN hobbies/tastes. " +
        "CRITICAL: a gift recipient is NOT the user. A recipient's traits, likes, occasion, or " +
        "relationship (mom, friend, birthday, their love of gardening) are task data and must NEVER " +
        "enter this profile. Only record a trait under user_interests if the USER states it about " +
        "THEMSELVES. If the exchange reveals nothing durable about the user, return the profile " +
        "unchanged. Output the profile in TOON (Token-Oriented Object Notation): one 'key: value' " +
        "per line, no braces/quotes/commas. Output ONLY the TOON lines — no prose, no code fences. " +
        "Example:\nuser_name: Nikita\nlanguage: ru\ncommunication_style: concise"

// Summary: keep the last KEEP_RECENT raw turns verbatim; once the un-folded tail grows past
// FOLD_THRESHOLD, fold the oldest into the running summary. Sliding Window sends the last WINDOW
// messages; Sticky Facts sends only the last STICKY_WINDOW (leaning on the working-memory layer).
private const val KEEP_RECENT = 6
private const val FOLD_THRESHOLD = 10
private const val WINDOW = KEEP_RECENT

// STICKY_FACTS sends only a tiny raw tail — it leans on the always-on working-memory layer to
// carry the rest of the context, instead of replaying recent turns verbatim.
private const val STICKY_WINDOW = 2

/**
 * The agent: owns the active conversation transcript and the DeepSeek request/response logic.
 * Callers only see [send] / [restore] / [loadHistory] / [reset] — never a [ChatRequest].
 *
 * Three explicit memory layers, all owned here (see [MemorySnapshot]):
 *  - Short-term: the active transcript ([history]); in-memory, session-scoped.
 *  - Working: task-specific key-value [facts]; refreshed every turn, cleared on [reset].
 *  - Long-term: durable user profile [longTerm]; refreshed every turn, SURVIVES [reset].
 * Working + long-term are always-on — injected on every request and refreshed every turn,
 * independent of the [ContextStrategy].
 *
 * The [ContextStrategy] is a separate axis: it only decides how the raw transcript is trimmed.
 * The agent branches on it in a single `when` (no class hierarchy):
 *  - SLIDING_WINDOW: the last [WINDOW] messages.
 *  - SUMMARY: a running summary of the older turns + the un-folded tail.
 *  - STICKY_FACTS: only the last [STICKY_WINDOW] messages (leans on the working-memory layer).
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

    // Working memory: task-specific key-value facts, refreshed every turn (all strategies).
    // Serialized as TOON (not JSON) for the helper prompts + storage — fewer tokens for a flat map.
    private val facts = linkedMapOf<String, String>()
    val factsToon: String get() = Toon.encode(facts)

    // Long-term memory: durable user profile, refreshed every turn, survives session resets.
    private val longTerm = linkedMapOf<String, String>()
    val longTermToon: String get() = Toon.encode(longTerm)
    val longTermFacts: Map<String, String> get() = longTerm.toMap()

    /** A read-only snapshot of all three memory layers, for the chat's memory panel. */
    fun memorySnapshot(): MemorySnapshot =
        MemorySnapshot(shortTerm = history.toList(), working = facts.toMap(), longTerm = longTerm.toMap())

    /** Drop the in-memory long-term profile (e.g. after it's wiped from Settings). */
    fun clearLongTermMemory() = longTerm.clear()

    // Running token accounting across the dialog (resets each session, like the conversation).
    val ledger = TokenLedger()

    /** Re-seed the full session state from storage. */
    fun restore(
        history: List<ChatMessage>,
        summary: String?,
        summarizedCount: Int,
        factsToon: String?,
        longTermFacts: Map<String, String> = emptyMap()
    ) {
        this.history.clear()
        this.history.addAll(history)
        this.summary = summary
        this.summarizedCount = summarizedCount
        facts.clear()
        factsToon?.let { facts.putAll(Toon.decode(it)) }
        longTerm.clear()
        longTerm.putAll(longTermFacts)
    }

    /** Swap the active transcript (Branching: switching to another branch's line). */
    fun loadHistory(history: List<ChatMessage>) {
        this.history.clear()
        this.history.addAll(history)
    }

    /** Wipe the conversation back to a fresh session. Long-term memory is NOT cleared — it's user
     *  profile data, not session data. */
    fun reset() {
        history.clear()
        summary = null
        summarizedCount = 0
        facts.clear()
        ledger.clear()
        // longTerm intentionally preserved — persists across session resets
    }

    /**
     * Appends the user turn, streams the assistant reply ([ChatEvent.Token] per delta), then emits
     * one [ChatEvent.Complete] with the turn's token accounting (and which helper call, if any, ran).
     */
    fun send(
        userMessage: String,
        model: String,
        strategy: ContextStrategy,
        profile: UserProfile = UserProfile.EMPTY
    ): Flow<ChatEvent> = flow {
        val userTurn = ChatMessage(role = "user", content = userMessage)

        // Build the request body per strategy. The turn isn't committed to [history] until AFTER a
        // successful reply, so a rejected request never leaves a dangling turn behind.
        val payloadMessages = buildList {
            add(ChatMessage(role = "system", content = systemPrompt))
            // Declared user profile — what the user explicitly told us to do (Settings). Sits above
            // the learned long-term memory so stated preferences win.
            if (!profile.isEmpty) add(ChatMessage(role = "system", content = profileContext(profile)))
            // Always-on memory layers — injected on every strategy, orthogonal to context strategy.
            // Long-term skips keys the declared profile overrides, so it may render empty — guard on that.
            if (longTerm.keys.any { it !in overriddenLongTermKeys(profile) }) {
                add(ChatMessage(role = "system", content = longTermContext(profile)))
            }
            if (facts.isNotEmpty()) add(ChatMessage(role = "system", content = factsContext()))
            // The strategy only decides how the raw transcript is trimmed.
            when (strategy) {
                ContextStrategy.SUMMARY -> {
                    summary?.let { add(ChatMessage(role = "system", content = summaryContext(it))) }
                    addAll(history.drop(summarizedCount))
                }
                ContextStrategy.SLIDING_WINDOW -> addAll(history.takeLast(WINDOW))
                ContextStrategy.STICKY_FACTS -> addAll(history.takeLast(STICKY_WINDOW))
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
            if (!response.isSuccessful) throw mapApiError(response.code, response.body.string())
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

        // Always-on memory layers refresh every turn (all strategies). Long-term is background/untracked.
        // These are blocking helper LLM calls — signal the UI so it can show a memory-updating spinner.
        emit(ChatEvent.MemoryUpdating)
        val workingUsage = refreshWorkingMemory(userTurn.content, full.toString())
        refreshLongTermMemory(userTurn.content, full.toString())

        // Headline helper call shown in the token panel: the SUMMARY fold, else the working refresh.
        var auxKind = AuxKind.NONE
        val auxUsage: Usage? = when (strategy) {
            ContextStrategy.SUMMARY -> foldOldest()?.also { auxKind = AuxKind.SUMMARY }
            else -> workingUsage?.also { auxKind = AuxKind.FACTS }
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
    fun regenerate(
        model: String,
        strategy: ContextStrategy,
        profile: UserProfile = UserProfile.EMPTY
    ): Flow<ChatEvent> = flow {
        val payloadMessages = buildList {
            add(ChatMessage(role = "system", content = systemPrompt))
            // Declared user profile — see [send]; kept identical so a regenerate honors it too.
            if (!profile.isEmpty) add(ChatMessage(role = "system", content = profileContext(profile)))
            // Always-on memory layers — injected on every strategy, orthogonal to context strategy.
            // Long-term skips keys the declared profile overrides, so it may render empty — guard on that.
            if (longTerm.keys.any { it !in overriddenLongTermKeys(profile) }) {
                add(ChatMessage(role = "system", content = longTermContext(profile)))
            }
            if (facts.isNotEmpty()) add(ChatMessage(role = "system", content = factsContext()))
            // The strategy only decides how the raw transcript is trimmed.
            when (strategy) {
                ContextStrategy.SUMMARY -> {
                    summary?.let { add(ChatMessage(role = "system", content = summaryContext(it))) }
                    addAll(history.drop(summarizedCount))
                }
                ContextStrategy.SLIDING_WINDOW -> addAll(history.takeLast(WINDOW))
                ContextStrategy.STICKY_FACTS -> addAll(history.takeLast(STICKY_WINDOW))
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
            if (!response.isSuccessful) throw mapApiError(response.code, response.body.string())
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

        // Always-on memory layers refresh every turn (all strategies). Long-term is background/untracked.
        // These are blocking helper LLM calls — signal the UI so it can show a memory-updating spinner.
        emit(ChatEvent.MemoryUpdating)
        val userText = history.dropLast(1).last().content
        val workingUsage = refreshWorkingMemory(userText, full.toString())
        refreshLongTermMemory(userText, full.toString())

        // Headline helper call shown in the token panel: the SUMMARY fold, else the working refresh.
        var auxKind = AuxKind.NONE
        val auxUsage: Usage? = when (strategy) {
            ContextStrategy.SUMMARY -> foldOldest()?.also { auxKind = AuxKind.SUMMARY }
            else -> workingUsage?.also { auxKind = AuxKind.FACTS }
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

    // Working memory: merge the latest exchange into the task-specific key-value facts. Runs every
    // turn on every strategy; the returned usage is metered as the headline aux on non-SUMMARY turns.
    private fun refreshWorkingMemory(userText: String, assistantText: String): Usage? {
        val instruction = buildString {
            append("Current facts (TOON):\n").append(factsToon).append("\n\n")
            append("Latest exchange:\n")
            append("user: ").append(userText).append('\n')
            append("assistant: ").append(assistantText).append('\n')
        }
        val (text, usage) = helperCall(WORKING_MEMORY_PROMPT, instruction)
        text?.let {
            val parsed = Toon.decode(it)
            if (parsed.isNotEmpty()) {
                facts.clear()
                facts.putAll(parsed)
            }
        }
        return usage
    }

    // Long-term memory: extract durable user-level facts from the exchange into the persistent profile.
    // Runs on every turn regardless of context strategy. Errors are swallowed — a failed extraction
    // must not abort the turn.
    private fun refreshLongTermMemory(userText: String, assistantText: String) {
        runCatching {
            val instruction = buildString {
                append("Current long-term profile (TOON):\n").append(longTermToon).append("\n\n")
                append("Latest exchange:\n")
                append("user: ").append(userText).append('\n')
                append("assistant: ").append(assistantText).append('\n')
            }
            val (text, _) = helperCall(LONG_TERM_MEMORY_PROMPT, instruction)
            text?.let {
                val parsed = Toon.decode(it)
                if (parsed.isNotEmpty()) {
                    longTerm.clear()
                    longTerm.putAll(parsed)
                }
            }
        }
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
            if (!response.isSuccessful) throw mapApiError(response.code, response.body.string())
            val body = json.decodeFromString<ChatResponse>(response.body.string())
            return body.choices.firstOrNull()?.message?.content?.trim() to body.usage
        }
    }

    private fun summaryContext(text: String): String =
        "Summary of the earlier conversation (older turns were compressed to save tokens):\n$text"

    private fun factsContext(): String = buildString {
        append("Working memory — current task context (cleared each session):\n")
        facts.forEach { (k, v) -> append("- ").append(k).append(": ").append(v).append('\n') }
    }

    private fun longTermContext(profile: UserProfile): String = buildString {
        append("Long-term memory — persistent user profile (remembered across sessions):\n")
        val omit = overriddenLongTermKeys(profile)
        longTerm.forEach { (k, v) ->
            if (k !in omit) append("- ").append(k).append(": ").append(v).append('\n')
        }
    }

    // Long-term keys the declared profile supersedes — omitted from the injected long-term block so
    // the model never sees a learned value contradicting a declared one. Stored memory is untouched.
    private fun overriddenLongTermKeys(p: UserProfile): Set<String> = buildSet {
        if (p.name.isNotBlank()) add("user_name")
        if (p.style != ReplyStyle.DEFAULT) add("communication_style")
    }

    // The user's *declared* preferences (set in Settings). Only set fields become instruction
    // lines; DEFAULT/blank are omitted so they don't constrain the model.
    private fun profileContext(p: UserProfile): String = buildString {
        append(
            "User profile — preferences the user set in Settings. These are AUTHORITATIVE and " +
                "OVERRIDE long-term memory and any name or preference mentioned in the conversation:\n"
        )
        if (p.name.isNotBlank()) {
            append("- Always address the user as ").append(p.name)
                .append(", even if a different name appears in the conversation or in memory\n")
        }
        when (p.style) {
            ReplyStyle.DEFAULT -> {}
            ReplyStyle.CONCISE -> append("- Style: keep replies concise and to the point\n")
            ReplyStyle.DETAILED -> append("- Style: give thorough, detailed replies with reasoning\n")
            ReplyStyle.FORMAL -> append("- Style: use a formal, professional tone\n")
            ReplyStyle.PLAYFUL -> append("- Style: use a warm, playful, casual tone\n")
        }
        when (p.format) {
            ReplyFormat.DEFAULT -> {}
            ReplyFormat.BULLETS -> append("- Format: present ideas as short bullet-point lists\n")
            ReplyFormat.PROSE -> append("- Format: write in flowing prose paragraphs, not lists\n")
        }
        if (p.constraints.isNotBlank()) {
            append("- Constraints: ").append(p.constraints.replace('\n', ' ')).append('\n')
        }
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
