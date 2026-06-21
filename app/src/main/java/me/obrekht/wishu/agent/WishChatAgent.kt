package me.obrekht.wishu.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import me.obrekht.wishu.invariant.Invariant
import me.obrekht.wishu.invariant.InvariantChecker
import me.obrekht.wishu.invariant.InvariantResult
import me.obrekht.wishu.invariant.InvariantValidator
import me.obrekht.wishu.invariant.Severity
import me.obrekht.wishu.network.ApiErrorEnvelope
import me.obrekht.wishu.network.ChatMessage
import me.obrekht.wishu.network.ChatRequest
import me.obrekht.wishu.network.ChatResponse
import me.obrekht.wishu.network.StreamChunk
import me.obrekht.wishu.network.StreamOptions
import me.obrekht.wishu.network.Thinking
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
        "occasion, budget, stated likes, dislikes, constraints, and decisions already made. Prior " +
        "facts are RETAINED automatically — you only need to output facts that are NEW or CHANGED " +
        "this turn (re-emit a key only to correct its value). Never re-state the recipient just to " +
        "keep it; it is kept for you. Output ONLY the TOON lines — no prose, no code fences. If " +
        "nothing is new or changed, output nothing. Example:\nrecipient: mom\noccasion: birthday\nbudget: ~3000 RUB"

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
        "THEMSELVES. Prior profile facts are RETAINED automatically — output ONLY keys that are NEW " +
        "or CHANGED this turn (re-emit a key only to correct its value); never re-state a key just to " +
        "keep it. If the exchange reveals nothing new or changed about the user, output nothing. " +
        "Output the profile in TOON (Token-Oriented Object Notation): one 'key: value' " +
        "per line, no braces/quotes/commas. Output ONLY the TOON lines — no prose, no code fences. " +
        "Example:\nuser_name: Nikita\nlanguage: ru\ncommunication_style: concise"

// Day 13: the pre-turn task-state helper. Given the FIXED current stage and the user's latest message,
// it reports ONLY whether the current stage's goal is met. It must NOT name or choose the next stage —
// the code advances by exactly one step (TaskStage.next), so the model can never make the task skip.
private const val TASK_STATE_PROMPT =
    "You track a gift-planning task through four FIXED stages, strictly in this order: " +
        "planning -> execution -> validation -> done. Stage goals: " +
        "planning = the requirements are gathered (recipient, occasion, budget, the recipient's tastes) " +
        "AND the user has confirmed the proposed search strategy; " +
        "execution = concrete gift ideas have been proposed; " +
        "validation = the user has confirmed or picked among the ideas; done = the task is closed. " +
        "You are told the CURRENT stage, the known facts gathered so far, and the user's latest message. " +
        "From the facts AND the user's latest message together, report what is known. Be STRICT — mark " +
        "something 'yes' ONLY if it is EXPLICITLY stated; if it is missing, vague, or merely implied, " +
        "mark 'no'. You CANNOT skip ahead or choose the next stage — just report. " +
        "First, the four PLANNING requirements about the gift — for each, is it explicitly known: " +
        "recipient_known (who the gift is for), occasion_known (the occasion), budget_known (a budget " +
        "or price range), tastes_known (the recipient's tastes, hobbies, or preferences). " +
        "Then strategy_confirmed: whether the user has explicitly confirmed (agreed to / approved) the " +
        "proposed search strategy in their latest message — mark 'yes' ONLY if the user says yes/ok/ " +
        "confirm or equivalent; mark 'no' if no strategy has been proposed yet or the user has not " +
        "responded to it. " +
        "Then stage_complete: whether the CURRENT stage's own goal is met — for execution = concrete " +
        "gift ideas have actually been proposed; for validation = the user has confirmed or picked an " +
        "idea; for done = n/a (report no). (For planning the app decides completion from the five flags " +
        "above, so stage_complete is ignored there — you may report no.) " +
        "Output in TOON (Token-Oriented Object Notation): exactly these eight lines, no braces/quotes/commas, " +
        "no prose, no code fences:\n" +
        "recipient_known: yes|no\n" +
        "occasion_known: yes|no\n" +
        "budget_known: yes|no\n" +
        "tastes_known: yes|no\n" +
        "strategy_confirmed: yes|no\n" +
        "stage_complete: yes|no\n" +
        "current_step: <short description of what is happening now, in the conversation's language>\n" +
        "expected_action: <short description of what is expected next, in the conversation's language>"

// Day 14: phrase a HARD refusal for the user — the reason it can't be met PLUS one in-constraint
// alternative — and crucially BOTH in the user's own language. The raw invariant explanation is English
// (code diagnostics / English seeds / a validator reason with no language directive), so showing it
// verbatim made the refusal card English even in a Russian chat. One flash call (replaces the old
// alternative-only call), TOON out.
private const val REFUSAL_PROMPT =
    "A user's gift request breaks a hard INVARIANT and must be refused. Given the INVARIANT and the " +
        "request, output exactly three TOON lines, no prose, no code fences:\n" +
        "rule: <the broken rule restated as one short phrase>\n" +
        "reason: <one short sentence saying you can't help with this and why>\n" +
        "alternative: <one brief, concrete gift idea that fully respects the invariant>\n" +
        "Keep the line keys (rule, reason, alternative) EXACTLY as written, in English. Translate only " +
        "the VALUES after the colon into the SAME LANGUAGE as the request."

// Day 14: a refusal phrased in the user's language. Any field may be null when the helper call fails —
// callers fall back to the raw (English) invariant text.
private data class RefusalText(val rule: String?, val reason: String?, val alternative: String?)

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

    // Day 14: the semantic invariant layer (the LLM "проверяла"). Shares the agent's client.
    private val validator = InvariantValidator(client)

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

    // Task state machine (Day 13): a formal FSM over the task's progress, orthogonal to memory and to
    // the context strategy. Owned here; transitions are code-gated (see [TaskStateMachine]) so the
    // model can never make the task skip a stage. Session-scoped — reset() takes it back to PLANNING.
    private val taskMachine = TaskStateMachine()

    /** The current task-state snapshot, for the chat's task panel + persistence. */
    fun taskState(): TaskState = taskMachine.state

    // Running token accounting across the dialog (resets each session, like the conversation).
    val ledger = TokenLedger()

    /** Re-seed the full session state from storage. */
    fun restore(
        history: List<ChatMessage>,
        summary: String?,
        summarizedCount: Int,
        factsToon: String?,
        longTermFacts: Map<String, String> = emptyMap(),
        taskState: TaskState = TaskState.EMPTY
    ) {
        this.history.clear()
        this.history.addAll(history)
        this.summary = summary
        this.summarizedCount = summarizedCount
        facts.clear()
        factsToon?.let { facts.putAll(Toon.decode(it)) }
        longTerm.clear()
        longTerm.putAll(longTermFacts)
        taskMachine.restore(taskState)
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
        taskMachine.reset() // session-scoped: a fresh session starts a fresh task at PLANNING
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
        profile: UserProfile = UserProfile.EMPTY,
        invariants: List<Invariant> = emptyList(),
        // True when the user already confirmed a SOFT-invariant warning and wants to proceed — the
        // SOFT gate is skipped this turn (HARD invariants always apply).
        overrideSoft: Boolean = false
    ): Flow<ChatEvent> = flow {
        val userTurn = ChatMessage(role = "user", content = userMessage)

        // Day 14 — pre-stream invariant gate on the REQUEST. Refuse a blatantly violating ask BEFORE
        // the main call, so a rejected request costs no main-model tokens and never advances the FSM.
        firstViolation(userMessage, invariants, includeSoft = !overrideSoft)?.let { v ->
            if (v.invariant.severity == Severity.HARD) {
                val r = refusalDetails(userMessage, v.invariant)
                emit(ChatEvent.Refused(v.invariant, r.rule ?: v.invariant.rule, r.reason ?: v.explanation, r.alternative))
            } else {
                emit(ChatEvent.SoftViolation(v.invariant, v.explanation, preGate = true))
            }
            return@flow
        }

        // Decide the task stage BEFORE generating: if the user's message completes the current stage,
        // advance now so the reply is produced under the NEW stage. Doing this post-turn made the stage
        // lag the content by a turn — ideas leaked into PLANNING, then the stage flipped to EXECUTION.
        advanceTaskState(userMessage, invariants)
        // Push the (possibly advanced) stage to the UI now, before a single token streams.
        emit(ChatEvent.TaskAdvanced(taskMachine.state))

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
            // Day 14 — invariants: the rules the model must reason within and never break. Injected
            // after the memory layers, only the relevant ones (not the whole transcript).
            if (invariants.isNotEmpty()) add(ChatMessage(role = "system", content = invariantsContext(invariants)))
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
            // Task state machine: tell the model the CURRENT stage and to work only on it. Injected
            // LAST — right before the user turn — so the stage constraint is the most recent instruction
            // the model reads and isn't buried under the transcript. This is the behavior-alignment
            // layer; the no-skip guarantee itself is in code (TaskStateMachine).
            add(ChatMessage(role = "system", content = taskStageContext(taskMachine.state)))
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
        // Day 14 — post-stream gate on the REPLY: catch the model violating an invariant in its OWN
        // output. Computed once; a HARD violation discards the reply (never committed to history) and
        // refuses instead, a SOFT one keeps the (already useful) reply but flags a warning banner.
        val reply = full.toString()
        val postViolations = checkViolations(reply, invariants, includeSoft = !overrideSoft)
        postViolations.firstOrNull { it.invariant.severity == Severity.HARD }?.let { v ->
            val r = refusalDetails(userMessage, v.invariant)
            emit(ChatEvent.Refused(v.invariant, r.rule ?: v.invariant.rule, r.reason ?: v.explanation, r.alternative))
            return@flow
        }

        history.add(userTurn)
        history.add(ChatMessage(role = "assistant", content = reply))

        postViolations.firstOrNull { it.invariant.severity == Severity.SOFT }?.let { v ->
            emit(ChatEvent.SoftViolation(v.invariant, v.explanation, preGate = false))
        }

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
        profile: UserProfile = UserProfile.EMPTY,
        invariants: List<Invariant> = emptyList()
    ): Flow<ChatEvent> = flow {
        // A regenerate reproduces the reply under the CURRENT stage — it does not re-run the stage
        // transition (that's driven by the user's message in [send], which already ran).
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
            // Day 14 — invariants: same rules as [send], so a regenerated reply honors them too.
            if (invariants.isNotEmpty()) add(ChatMessage(role = "system", content = invariantsContext(invariants)))
            // Task state machine: tell the model the CURRENT stage and to work only on it. This is the
            // behavior-alignment layer; the no-skip guarantee itself is in code (TaskStateMachine).
            add(ChatMessage(role = "system", content = taskStageContext(taskMachine.state)))
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
        // Day 14 — post-stream gate: a regenerated reply is subject to the same invariants.
        val regenUserText = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        val reply = full.toString()
        val postViolations = checkViolations(reply, invariants, includeSoft = true)
        postViolations.firstOrNull { it.invariant.severity == Severity.HARD }?.let { v ->
            val r = refusalDetails(regenUserText, v.invariant)
            emit(ChatEvent.Refused(v.invariant, r.rule ?: v.invariant.rule, r.reason ?: v.explanation, r.alternative))
            return@flow
        }

        // Only the assistant turn is committed; the user turn is already in history.
        history.add(ChatMessage(role = "assistant", content = reply))

        postViolations.firstOrNull { it.invariant.severity == Severity.SOFT }?.let { v ->
            emit(ChatEvent.SoftViolation(v.invariant, v.explanation, preGate = false))
        }

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
        // Mechanical extract/merge — no chain-of-thought needed; disable thinking to save tokens.
        val (text, usage) = helperCall(WORKING_MEMORY_PROMPT, instruction, thinking = false)
        text?.let {
            // MERGE, never replace. The flash helper is fed the current facts and told to keep them,
            // but on a turn that doesn't mention an earlier fact (e.g. the recipient) it routinely
            // omits it from its output. A clear()+putAll would then drop that fact — the agent
            // "forgetting the gift recipient". Merging lets the model overwrite a value (recipient
            // mom -> dad) while guaranteeing an unmentioned key is never silently lost.
            facts.putAll(Toon.decode(it))
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
                // MERGE, never replace — same reason as working memory (refreshWorkingMemory): the flash
                // helper omits an unmentioned key (e.g. user_name) on a turn that doesn't touch it, and a
                // clear()+putAll would drop it. Merging keeps the key and still lets the model overwrite
                // a value. Wiping long-term entirely is a separate, explicit path (Settings clear memory).
                longTerm.putAll(Toon.decode(it))
            }
        }
    }

    // Task state (pre-turn): ask the cheap flash model whether the user's latest message completes the
    // CURRENT stage's goal, plus a short description of the step / expected action. Run BEFORE the reply
    // so a completed stage advances now and the reply is generated under the new stage — content never
    // lags the stage by a turn. The model only reports completion (a boolean); the CODE decides the
    // destination by calling advance() — exactly one TaskStage.next, never a jump. Errors are swallowed
    // so a failed check never aborts the turn.
    private fun advanceTaskState(userText: String, invariants: List<Invariant> = emptyList()) {
        runCatching {
            val s = taskMachine.state
            if (s.stage.next == null) return@runCatching // terminal stage: nothing to advance

            val instruction = buildString {
                append("Current stage: ").append(s.stage.name.lowercase()).append("\n\n")
                // Inject working memory so the check sees requirements (recipient, budget, tastes)
                // gathered in earlier turns — the latest message alone is not enough under context
                // strategies that trim history (sliding window, sticky facts).
                if (facts.isNotEmpty()) {
                    append("Known facts so far (TOON):\n").append(factsToon).append("\n\n")
                }
                // Recent transcript tail. Working memory alone is NOT enough: its capture is a lossy,
                // one-turn-lagged flash call, so a slot stated a turn or two ago (e.g. the occasion)
                // can be missing from facts AND from the latest message — the helper then reports that
                // slot 'no' and the task gets stuck re-asking for something already given. The raw tail
                // lets the helper see what was actually said, independent of capture. (history does NOT
                // yet contain the current user turn here — it's appended only after a successful reply —
                // so the latest message below is not duplicated.)
                val recent = history.takeLast(WINDOW)
                if (recent.isNotEmpty()) {
                    append("Recent conversation:\n")
                    recent.forEach { append(it.role).append(": ").append(it.content).append('\n') }
                    append('\n')
                }
                append("The user's latest message:\n")
                append("user: ").append(userText).append('\n')
            }
            // Mechanical completion check — no chain-of-thought needed; disable thinking to save tokens.
            val (text, _) = helperCall(TASK_STATE_PROMPT, instruction, thinking = false)
            text?.let {
                val parsed = Toon.decode(it)
                taskMachine.updateProgress(
                    currentStep = parsed["current_step"].orEmpty(),
                    expectedAction = parsed["expected_action"].orEmpty()
                )
                fun known(key: String) = parsed[key].equals("yes", ignoreCase = true)

                // Day 15 — PLANNING has two sub-phases:
                //   Phase 1: gather all four data slots (recipient, occasion, budget, tastes).
                //   Phase 2: once all four are filled, the agent proposes a search strategy; the user
                //            must confirm it (strategy_confirmed: yes) before advancing to EXECUTION.
                val complete = when (s.stage) {
                    TaskStage.PLANNING -> {
                        val allFourFilled = known("recipient_known") && known("occasion_known") &&
                            known("budget_known") && known("tastes_known")
                        if (allFourFilled) {
                            if (known("strategy_confirmed")) {
                                taskMachine.setStrategyPending(false)
                                true
                            } else {
                                // Phase 2 begins or is ongoing: signal the model to propose/await strategy.
                                taskMachine.setStrategyPending(true)
                                false
                            }
                        } else {
                            if (s.strategyPending) taskMachine.setStrategyPending(false)
                            false
                        }
                    }
                    else -> known("stage_complete")
                }
                if (complete) {
                    // Day 14/15 — FSM coupling: ONLY on the finalizing turn (the one that would move
                    // VALIDATION → DONE), verify the proposed pick honors the invariants. A HARD
                    // violation regresses to EXECUTION — code-gated (TaskStateMachine.regressTo), not
                    // the model's call — so a bad pick can never slip through to DONE.
                    //
                    // This deliberately runs on the finalizing turn only, NOT on every VALIDATION turn:
                    // a brainstorm legitimately lists pricier ALTERNATIVES (ranges, "stretch" picks)
                    // above the budget ceiling, and re-scanning that whole blob on a mere refinement
                    // turn (e.g. the user just choosing a color) spuriously tripped the budget ceiling
                    // on an option the user never selected, bouncing the task back to EXECUTION.
                    if (s.stage == TaskStage.VALIDATION && invariants.isNotEmpty()) {
                        val proposed = history.lastOrNull { it.role == "assistant" }?.content.orEmpty()
                        if (proposed.isNotBlank() &&
                            checkViolations(proposed, invariants, includeSoft = false).isNotEmpty()
                        ) {
                            taskMachine.regressTo(TaskStage.EXECUTION)
                            return@runCatching
                        }
                    }
                    // Capture the stage artifact BEFORE advancing so the snapshot is available in
                    // the new stage's context immediately on the same turn.
                    when (s.stage) {
                        TaskStage.PLANNING -> taskMachine.captureArtifact(brief = factsToon)
                        TaskStage.EXECUTION -> {
                            val lastAssistant = history.lastOrNull { it.role == "assistant" }?.content
                            taskMachine.captureArtifact(ideas = lastAssistant)
                        }
                        else -> Unit
                    }
                    taskMachine.advance()
                }
            }
        }
    }

    // ---- Day 14: invariant enforcement ----------------------------------------------------------

    // Run the invariant layers over [text]: the deterministic checker (pure Kotlin, the budget fact)
    // first, then the LLM validator (semantic) ONLY when needed. Returns the violations ordered
    // HARD-first; SOFT ones are dropped when [includeSoft] is false. Validator failures are already
    // swallowed (fail-open) inside it.
    //
    // The deterministic layer short-circuits the network call: a deterministic HARD hit already
    // refuses the turn, so the validator is skipped entirely; otherwise the validator only re-checks
    // invariants the deterministic layer didn't already flag. So a request the local checker can
    // settle never "flies to the AI".
    private fun checkViolations(
        text: String,
        invariants: List<Invariant>,
        includeSoft: Boolean
    ): List<InvariantResult.Violated> {
        if (invariants.isEmpty()) return emptyList()
        val deterministic = InvariantChecker.check(text, facts["budget"], invariants)
        val deterministicHard = deterministic.any { it.invariant.severity == Severity.HARD }
        // Don't re-validate invariants already caught locally; skip the LLM call entirely on a HARD hit.
        val flaggedIds = deterministic.map { it.invariant.id }.toSet()
        val semantic = if (deterministicHard) emptyList()
            else validator.validate(text, invariants.filterNot { it.id in flaggedIds })
        // De-dupe by invariant id (BOTH-checked rules can surface in both layers); keep first hit.
        return (deterministic + semantic)
            .distinctBy { it.invariant.id }
            .filter { includeSoft || it.invariant.severity == Severity.HARD }
            .sortedBy { if (it.invariant.severity == Severity.HARD) 0 else 1 }
    }

    private fun firstViolation(
        text: String,
        invariants: List<Invariant>,
        includeSoft: Boolean
    ): InvariantResult.Violated? = checkViolations(text, invariants, includeSoft).firstOrNull()

    // Best-effort refusal text in the USER'S language: the broken rule restated + a short reason + one
    // in-constraint alternative, all inferred to the language of [userText]. One cheap flash call. On any
    // failure, returns nulls — callers fall back to the raw (English) invariant rule/explanation, so the
    // refusal still names the rule.
    private fun refusalDetails(userText: String, invariant: Invariant): RefusalText = runCatching {
        val instruction = buildString {
            append("INVARIANT that must be respected: ").append(invariant.rule).append('\n')
            append("The request that breaks it: ").append(userText).append('\n')
        }
        val raw = helperCall(REFUSAL_PROMPT, instruction, thinking = false).first
            ?: return@runCatching RefusalText(null, null, null)
        val parsed = Toon.decode(raw)
        // The model occasionally translates the TOON keys too (rule -> правило), so a keyed lookup
        // misses. Fall back to the values in line order (rule, reason, alternative) so the localized
        // text is still used instead of the English fallback.
        val values = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") && it.contains(':') }
            .map { it.substringAfter(':').trim() }
            .filter { it.isNotEmpty() }
            .toList()
        RefusalText(
            rule = (parsed["rule"]?.takeIf { it.isNotBlank() } ?: values.getOrNull(0))?.capitalizeFirst(),
            reason = (parsed["reason"]?.takeIf { it.isNotBlank() } ?: values.getOrNull(1))?.capitalizeFirst(),
            alternative = (parsed["alternative"]?.takeIf { it.isNotBlank() } ?: values.getOrNull(2))?.capitalizeFirst()
        )
    }.getOrElse { RefusalText(null, null, null) }

    // Uppercase the first character so the rule/reason read as proper sentences regardless of how the
    // helper cased its output.
    private fun String.capitalizeFirst(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    // The invariants block injected into every request: the rules + the instruction to reason within
    // them and refuse anything that breaks one. Only the rule text is sent — not the internal check
    // type / keywords — so the model reasons about meaning, not the enforcement mechanics.
    private fun invariantsContext(invariants: List<Invariant>): String = buildString {
        append(
            "Invariants — hard rules you must NEVER break. Before answering, check your reply against " +
                "them and reason strictly within them. If the user's request or any idea you'd propose " +
                "would break one, do NOT comply: refuse plainly, name which rule it breaks and why, and " +
                "offer an alternative that respects it. HARD rules are absolute; for SOFT rules, warn " +
                "and let the user decide.\n"
        )
        invariants.forEach { inv ->
            append("- [").append(inv.severity.name).append("] ").append(inv.rule).append('\n')
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
    // thinking=false disables the hybrid model's reasoning for purely mechanical checks.
    private fun helperCall(
        system: String,
        userInstruction: String,
        thinking: Boolean = true
    ): Pair<String?, Usage?> {
        val payload = ChatRequest(
            model = SUMMARY_MODEL,
            messages = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user", content = userInstruction)
            ),
            maxTokens = 1024,
            stream = false,
            thinking = if (thinking) null else Thinking(type = "disabled")
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

    // Inject the FIXED current task stage so the model focuses on it and doesn't run ahead. Behavior
    // alignment only — the structural no-skip guarantee lives in [TaskStateMachine].
    // Day 15: PLANNING has two sub-phases driven by [TaskState.strategyPending]; EXECUTION and
    // VALIDATION inject artifact snapshots so context is available even under aggressive trimming.
    private fun taskStageContext(s: TaskState): String = buildString {
        append("Task state machine — the task moves through fixed stages in order: ")
        append("planning -> execution -> validation -> done. ")
        append("The CURRENT stage is: ").append(s.stage.name).append(".\n")
        when (s.stage) {
            TaskStage.PLANNING -> {
                if (!s.strategyPending) {
                    // Sub-phase 1: gather the four data slots, one question at a time.
                    append(
                        "Work ONLY on planning: gather the missing requirements (recipient, occasion, budget, " +
                            "the recipient's tastes) by asking ONE question at a time. " +
                            "HARD RULE: do NOT name, propose, list, hint at, or finalize ANY concrete gift, " +
                            "product, or brand yet — not even an example. Even if an idea is obvious to you, " +
                            "keep it to yourself and keep asking until the requirements are complete.\n"
                    )
                } else {
                    // Sub-phase 2: all four slots are filled — propose a strategy and await confirmation.
                    // HARD RULE first, then the task: the earlier "search strategy for gift ideas" wording
                    // primed the model into naming concrete products, so the ban is stated up front and the
                    // strategy is framed as an ABSTRACT approach (directions/types), never example items.
                    append(
                        "All requirements are now gathered, but you are STILL in planning. " +
                            "HARD RULE: name NO concrete gift, product, brand, model, or specific item yet — " +
                            "not even one, not as an example, not to illustrate a category. If you catch " +
                            "yourself about to write a product name, stop and describe the direction instead. " +
                            "Concrete ideas come only AFTER the user confirms the strategy, in the next stage.\n" +
                            "Work ONLY on strategy confirmation: briefly summarize the gift brief (recipient, " +
                            "occasion, budget, tastes) so the user sees it at a glance, then propose a SEARCH " +
                            "STRATEGY described ABSTRACTLY — the broad approach only: which TYPES of categories " +
                            "to explore, how to split the budget, what style/qualities to favor. Then ask the " +
                            "user to confirm or refine the strategy before you go further.\n"
                    )
                }
            }
            TaskStage.EXECUTION -> {
                append(
                    "Work ONLY on execution: propose concrete gift ideas that fit the gathered requirements. " +
                        "HARD RULE: present them as OPTIONS to consider — do NOT declare a single final " +
                        "choice or say 'Финальный выбор' / 'final pick'. Picking is the next stage, the " +
                        "user's job, not yours.\n"
                )
                // Inject the brief snapshot so requirements are in scope even under aggressive trimming.
                s.briefSnapshot?.takeIf { it.isNotBlank() }?.let { brief ->
                    append("Requirements brief (captured when planning was complete):\n")
                    append(brief).append('\n')
                }
            }
            TaskStage.VALIDATION -> {
                append(
                    "Work ONLY on validation: help the user confirm or pick among the ideas ALREADY proposed; " +
                        "refine on request. Do NOT invent a brand-new idea set or start an unrelated task.\n"
                )
                // Inject brief + ideas snapshots so validation always has both the requirements and
                // the proposed options in context, even if the transcript is trimmed.
                s.briefSnapshot?.takeIf { it.isNotBlank() }?.let { brief ->
                    append("Requirements brief:\n").append(brief).append('\n')
                }
                s.ideasSnapshot?.takeIf { it.isNotBlank() }?.let { ideas ->
                    append("Ideas proposed in execution:\n").append(ideas).append('\n')
                }
            }
            TaskStage.DONE -> append(
                "The task is done. Acknowledge completion; offer to start a new task if the user wants.\n"
            )
        }
        append("This stage boundary is a hard constraint, not a suggestion. Do not skip ahead to a ")
        append("later stage's work on your own, even if you feel ready.\n")
        // The staging is internal machinery. The app advances stages in code based on the conversation;
        // the model must never expose it or ask the user to move between stages — that creates a
        // confusing second approval channel next to the app's own stage-gate UI.
        append("IMPORTANT: this staging is INTERNAL. Never mention stages, never name 'planning' / ")
        append("'execution' / 'validation', and never ask the user whether to move to the next stage. ")
        append("The app handles stage transitions itself. Just do the current stage's work naturally, ")
        append("as an ordinary helpful conversation.")
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
