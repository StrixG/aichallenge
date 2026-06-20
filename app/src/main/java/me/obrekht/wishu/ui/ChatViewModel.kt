package me.obrekht.wishu.ui

import android.app.Application
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.obrekht.wishu.R
import me.obrekht.wishu.WishuApplication
import me.obrekht.wishu.agent.AuxKind
import me.obrekht.wishu.agent.ChatEvent
import me.obrekht.wishu.agent.ContextStrategy
import me.obrekht.wishu.agent.ContextWindowExceededException
import me.obrekht.wishu.agent.TaskState
import me.obrekht.wishu.agent.TurnTokens
import me.obrekht.wishu.agent.WishChatAgent
import me.obrekht.wishu.data.Branch
import me.obrekht.wishu.data.ChatHistoryRepository
import me.obrekht.wishu.data.ROOT_BRANCH_ID
import me.obrekht.wishu.invariant.Invariant
import me.obrekht.wishu.network.ChatMessage

data class ChatUiMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    // Token accounting for this assistant turn (null for user turns / restored bubbles).
    val tokens: TurnTokens? = null,
    // Which helper call (if any) ran on this turn — drives the per-turn note.
    val aux: AuxKind = AuxKind.NONE,
    // Day 14: a HARD-invariant refusal rendered in place of an assistant reply.
    val refusal: RefusalInfo? = null,
    // Day 14: a SOFT-invariant warning shown under a reply that still stands.
    val softNote: String? = null
)

// Day 14: what a refusal card shows — the broken rule, why, and an in-constraint alternative.
data class RefusalInfo(val rule: String, val explanation: String, val alternative: String?)

// Day 14: a pending SOFT-invariant confirmation — the user can proceed (re-send with override) or cancel.
data class SoftConfirm(val text: String, val rule: String, val explanation: String)

// Pager info at a single message index: current version, total, and which branch ids to navigate to.
data class VersionInfo(val current: Int, val total: Int, val prevId: Long?, val nextId: Long?)

data class ChatUiState(
    val messages: List<ChatUiMessage> = emptyList(),
    val inputText: TextFieldValue = TextFieldValue(),
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
    // Per-turn token accounting; the last entry carries the cumulative totals/cost.
    val tokenTurns: List<TurnTokens> = emptyList(),
    // Mirrors the Settings context strategy so the chat shows + can switch the active mode.
    val strategy: ContextStrategy = ContextStrategy.DEFAULT,
    // Active branch id — used internally and kept for version group computation.
    val activeBranchId: Long = ROOT_BRANCH_ID,
    // Keyed by message index; present only where alternative versions exist (pager shown there).
    val versionGroups: Map<Int, VersionInfo> = emptyMap(),
    // Mirrors the Settings model choice; drives pricing, so it's shown next to the token panel.
    val model: String = "",
    // Memory layers for the debug panel.
    val workingMemory: Map<String, String> = emptyMap(),
    val longTermMemory: Map<String, String> = emptyMap(),
    // True for the layer(s) whose content changed on the last completed turn — drives the
    // "updated" badge in the memory panel. Reset when the next user turn starts.
    val workingChanged: Boolean = false,
    val longTermChanged: Boolean = false,
    // True while the memory-layer helper calls run (after the reply streamed) — drives the
    // memory panel's loading spinner.
    val memoryUpdating: Boolean = false,
    // The formal task state machine (Day 13): stage + step + expected action, for the
    // task panel. Advanced in code (never skips); see WishChatAgent / TaskStateMachine.
    val taskState: TaskState = TaskState.EMPTY,
    // Day 14: a pending SOFT-invariant confirmation dialog (null = none).
    val pendingSoftConfirm: SoftConfirm? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WishuApplication
    private val agent = WishChatAgent(app.streamingHttpClient)
    private val chatHistoryRepository = ChatHistoryRepository(
        app.database.chatMessageDao(),
        app.database.chatSummaryDao(),
        app.database.chatFactsDao(),
        app.database.chatBranchDao(),
        app.database.longTermMemoryDao(),
        app.database.taskStateDao()
    )
    private val settingsRepository = app.settingsRepository
    private val invariantRepository = app.invariantRepository

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Full branch tree; kept private — the UI only needs the derived versionGroups.
    private var allBranches: List<Branch> = emptyList()

    // Day 14: the live invariants, mirrored from the repository and passed to every agent call.
    private var invariants: List<Invariant> = emptyList()

    init {
        // Restore the saved session: re-seed the agent (active branch transcript + summary + facts)
        // and rebuild the on-screen bubbles + the version groups.
        viewModelScope.launch {
            allBranches = chatHistoryRepository.branches()
            val activeId = settingsRepository.activeBranchId.value
                .takeIf { id -> allBranches.any { it.id == id } } ?: ROOT_BRANCH_ID
            val transcript = chatHistoryRepository.transcript(activeId)
            val savedSummary = chatHistoryRepository.loadSummary()
            val savedFacts = chatHistoryRepository.loadFacts()
            val savedLongTerm = chatHistoryRepository.loadLongTermMemory()
            val savedTaskState = chatHistoryRepository.loadTaskState() ?: TaskState.EMPTY
            agent.restore(
                history = transcript,
                summary = savedSummary?.summary,
                summarizedCount = savedSummary?.summarizedCount ?: 0,
                factsToon = savedFacts,
                longTermFacts = savedLongTerm,
                taskState = savedTaskState
            )
            val mem = agent.memorySnapshot()
            _uiState.update {
                it.copy(
                    messages = transcript.map(::toUiMessage),
                    activeBranchId = activeId,
                    versionGroups = computeVersionGroups(activeId, allBranches),
                    workingMemory = mem.working,
                    longTermMemory = mem.longTerm,
                    taskState = agent.taskState()
                )
            }
        }
        // Mirror the live strategy so the chat chip + token note update when it's changed elsewhere.
        viewModelScope.launch {
            settingsRepository.strategy.collect { strategy ->
                _uiState.update { it.copy(strategy = strategy) }
            }
        }
        // Mirror the live model choice so the chat subtitle updates when it's changed in Settings.
        viewModelScope.launch {
            settingsRepository.selectedModel.collect { model ->
                _uiState.update { it.copy(model = model) }
            }
        }
        // Long-term memory wiped from Settings while this session is alive: drop the agent's in-memory
        // copy too, or the next turn would re-persist it. (0L is the initial no-op value.)
        viewModelScope.launch {
            settingsRepository.longTermClearedAt.collect { ts ->
                if (ts == 0L) return@collect
                agent.clearLongTermMemory()
                _uiState.update { it.copy(longTermMemory = emptyMap(), longTermChanged = false) }
            }
        }
        // Day 14: seed the default invariants on first run, then mirror the live list for agent calls.
        viewModelScope.launch {
            invariantRepository.seedDefaults()
            invariantRepository.invariants.collect { invariants = it }
        }
    }

    fun onInputChange(value: TextFieldValue) {
        _uiState.update { it.copy(inputText = value) }
    }

    fun send() {
        val text = _uiState.value.inputText.text.trim()
        if (text.isBlank() || _uiState.value.isStreaming) return
        _uiState.update { it.copy(inputText = TextFieldValue()) }
        dispatch(text)
    }

    /**
     * Append the user/assistant bubbles, stream the reply, and handle completion/errors. On failure
     * the typed [text] is put back in the input box so the user can retry. [overrideSoft] is set when
     * the user confirmed a SOFT-invariant warning and wants to proceed (Day 14).
     */
    private fun dispatch(text: String, overrideSoft: Boolean = false) {
        _uiState.update {
            it.copy(
                messages = it.messages +
                    ChatUiMessage(role = "user", content = text) +
                    ChatUiMessage(role = "assistant", content = ""),
                isStreaming = true,
                errorMessage = null,
                workingChanged = false,
                longTermChanged = false
            )
        }

        viewModelScope.launch {
            val branchId = _uiState.value.activeBranchId
            try {
                val model = settingsRepository.selectedModel.value
                val strategy = settingsRepository.strategy.value
                val profile = settingsRepository.profile.value
                var aux = AuxKind.NONE
                // Day 14: a HARD refusal or a pre-stream SOFT warning ends the turn without committing.
                var refused = false
                var softPre = false
                agent.send(text, model, strategy, profile, invariants, overrideSoft).collect { event ->
                    when (event) {
                        is ChatEvent.Token -> _uiState.update { state ->
                            state.copy(messages = appendToLast(state.messages, event.delta))
                        }
                        // Pre-stream: flip the stage badge the moment the FSM advances.
                        is ChatEvent.TaskAdvanced -> _uiState.update { it.copy(taskState = event.state) }
                        ChatEvent.MemoryUpdating -> _uiState.update { it.copy(memoryUpdating = true) }
                        is ChatEvent.Refused -> {
                            refused = true
                            _uiState.update { state ->
                                val last = state.messages.last()
                                state.copy(
                                    messages = state.messages.dropLast(1) + last.copy(
                                        content = "",
                                        refusal = RefusalInfo(
                                            event.rule, event.explanation, event.alternative
                                        )
                                    )
                                )
                            }
                        }
                        is ChatEvent.SoftViolation -> {
                            if (event.preGate) {
                                // Caught before generating: drop the optimistic bubbles, ask to confirm.
                                softPre = true
                                _uiState.update { state ->
                                    state.copy(
                                        messages = state.messages.dropLast(2),
                                        pendingSoftConfirm = SoftConfirm(
                                            text, event.invariant.rule, event.explanation
                                        )
                                    )
                                }
                            } else {
                                // Caught on the reply: keep it, flag a warning banner.
                                _uiState.update { state ->
                                    val last = state.messages.last()
                                    state.copy(messages = state.messages.dropLast(1) +
                                        last.copy(softNote = event.explanation))
                                }
                            }
                        }
                        is ChatEvent.Complete -> {
                            aux = event.aux
                            _uiState.update { state ->
                                val last = state.messages.last()
                                state.copy(
                                    messages = state.messages.dropLast(1) +
                                        last.copy(tokens = event.tokens, aux = event.aux),
                                    tokenTurns = state.tokenTurns + event.tokens
                                )
                            }
                        }
                    }
                }
                // A refused/soft-blocked turn leaves no committed reply: stop here (no persistence).
                if (refused || softPre) {
                    _uiState.update { it.copy(isStreaming = false, memoryUpdating = false) }
                    recomputeVersionGroups()
                    return@launch
                }
                val rawReply = _uiState.value.messages.last().content
                _uiState.update { it.copy(isStreaming = false, memoryUpdating = false) }
                chatHistoryRepository.append(branchId, "user", text)
                chatHistoryRepository.append(branchId, "assistant", rawReply)
                when (aux) {
                    AuxKind.SUMMARY ->
                        agent.summary?.let { chatHistoryRepository.saveSummary(it, agent.summarizedCount) }
                    AuxKind.FACTS -> chatHistoryRepository.saveFacts(agent.factsToon)
                    AuxKind.NONE -> Unit
                }
                chatHistoryRepository.saveAllLongTermFacts(agent.longTermFacts)
                chatHistoryRepository.saveTaskState(agent.taskState())
                val mem = agent.memorySnapshot()
                _uiState.update { it.copy(
                    workingMemory = mem.working,
                    longTermMemory = mem.longTerm,
                    workingChanged = mem.working != it.workingMemory,
                    longTermChanged = mem.longTerm != it.longTermMemory,
                    taskState = agent.taskState()
                ) }
            } catch (e: ContextWindowExceededException) {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.dropLast(2),
                        inputText = TextFieldValue(text),
                        isStreaming = false,
                        memoryUpdating = false,
                        errorMessage = app.getString(R.string.error_context_window)
                    )
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.dropLast(2),
                        inputText = TextFieldValue(text),
                        isStreaming = false,
                        memoryUpdating = false,
                        errorMessage = app.getString(R.string.error_chat)
                    )
                }
            }
            recomputeVersionGroups()
        }
    }

    /** Change the context-management strategy (same setting the Settings radio drives). */
    fun setStrategy(strategy: ContextStrategy) {
        settingsRepository.setStrategy(strategy)
    }

    /**
     * Branching/regenerate: fork a new branch off the active one at the last assistant index, load
     * the forked transcript (ending at the user turn), then stream a fresh assistant reply. The old
     * reply stays on the parent branch; the ‹ n/m › pager lets the user switch between versions.
     */
    fun regenerate() {
        val state = _uiState.value
        if (state.isStreaming || state.messages.isEmpty() || state.messages.last().role != "assistant") return
        viewModelScope.launch {
            val parentId = state.activeBranchId
            val forkAt = state.messages.size - 1 // keep everything up to (not including) the old assistant reply
            val newId = chatHistoryRepository.createBranch(parentId, forkAt, nextBranchLabel(allBranches.size))
            allBranches = chatHistoryRepository.branches()
            settingsRepository.setActiveBranch(newId)
            // Base transcript ends with the user turn; regenerate will answer it.
            val base = chatHistoryRepository.transcript(newId)
            agent.loadHistory(base)
            _uiState.update {
                it.copy(
                    activeBranchId = newId,
                    messages = base.map(::toUiMessage) + ChatUiMessage(role = "assistant", content = ""),
                    tokenTurns = emptyList(),
                    isStreaming = true,
                    errorMessage = null,
                    versionGroups = computeVersionGroups(newId, allBranches),
                    workingChanged = false,
                    longTermChanged = false
                )
            }

            try {
                val model = settingsRepository.selectedModel.value
                val strategy = settingsRepository.strategy.value
                val profile = settingsRepository.profile.value
                var aux = AuxKind.NONE
                var refused = false
                agent.regenerate(model, strategy, profile, invariants).collect { event ->
                    when (event) {
                        is ChatEvent.Token -> _uiState.update { s ->
                            s.copy(messages = appendToLast(s.messages, event.delta))
                        }
                        // Regenerate never advances the stage, but the event is part of the sealed type.
                        is ChatEvent.TaskAdvanced -> _uiState.update { it.copy(taskState = event.state) }
                        ChatEvent.MemoryUpdating -> _uiState.update { it.copy(memoryUpdating = true) }
                        is ChatEvent.Refused -> {
                            refused = true
                            _uiState.update { s ->
                                val last = s.messages.last()
                                s.copy(messages = s.messages.dropLast(1) + last.copy(
                                    content = "",
                                    refusal = RefusalInfo(event.rule, event.explanation, event.alternative)
                                ))
                            }
                        }
                        is ChatEvent.SoftViolation -> _uiState.update { s ->
                            val last = s.messages.last()
                            s.copy(messages = s.messages.dropLast(1) + last.copy(softNote = event.explanation))
                        }
                        is ChatEvent.Complete -> {
                            aux = event.aux
                            _uiState.update { s ->
                                val last = s.messages.last()
                                s.copy(
                                    messages = s.messages.dropLast(1) +
                                        last.copy(tokens = event.tokens, aux = event.aux),
                                    tokenTurns = s.tokenTurns + event.tokens
                                )
                            }
                        }
                    }
                }
                if (refused) {
                    _uiState.update { it.copy(isStreaming = false, memoryUpdating = false) }
                    recomputeVersionGroups()
                    return@launch
                }
                val rawReply = _uiState.value.messages.last().content
                _uiState.update { it.copy(isStreaming = false, memoryUpdating = false) }
                chatHistoryRepository.append(newId, "assistant", rawReply)
                when (aux) {
                    AuxKind.SUMMARY ->
                        agent.summary?.let { chatHistoryRepository.saveSummary(it, agent.summarizedCount) }
                    AuxKind.FACTS -> chatHistoryRepository.saveFacts(agent.factsToon)
                    AuxKind.NONE -> Unit
                }
                chatHistoryRepository.saveAllLongTermFacts(agent.longTermFacts)
                chatHistoryRepository.saveTaskState(agent.taskState())
                val mem = agent.memorySnapshot()
                _uiState.update { it.copy(
                    workingMemory = mem.working,
                    longTermMemory = mem.longTerm,
                    workingChanged = mem.working != it.workingMemory,
                    longTermChanged = mem.longTerm != it.longTermMemory,
                    taskState = agent.taskState()
                ) }
            } catch (e: ContextWindowExceededException) {
                _uiState.update { s ->
                    s.copy(
                        messages = s.messages.dropLast(1),
                        isStreaming = false,
                        memoryUpdating = false,
                        errorMessage = app.getString(R.string.error_context_window)
                    )
                }
            } catch (e: Exception) {
                _uiState.update { s ->
                    s.copy(
                        messages = s.messages.dropLast(1),
                        isStreaming = false,
                        memoryUpdating = false,
                        errorMessage = app.getString(R.string.error_chat)
                    )
                }
            }
            recomputeVersionGroups()
        }
    }

    /**
     * Branching/edit: fork a new branch off the active one at [index] (the user message being
     * edited), then dispatch [newText] as the first message on that branch. Everything from [index]
     * onward diverges; earlier messages are shared via the branch's parent link.
     */
    fun editMessage(index: Int, newText: String) {
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            val parentId = _uiState.value.activeBranchId
            val newId = chatHistoryRepository.createBranch(parentId, index, nextBranchLabel(allBranches.size))
            allBranches = chatHistoryRepository.branches()
            settingsRepository.setActiveBranch(newId)
            val base = chatHistoryRepository.transcript(newId)
            agent.loadHistory(base)
            _uiState.update {
                it.copy(
                    activeBranchId = newId,
                    messages = base.map(::toUiMessage),
                    tokenTurns = emptyList(),
                    versionGroups = computeVersionGroups(newId, allBranches)
                )
            }
            dispatch(newText)
        }
    }

    /** Switch the active branch (used by the ‹ n/m › version pager arrows). */
    fun switchBranch(id: Long) {
        if (_uiState.value.isStreaming || id == _uiState.value.activeBranchId) return
        viewModelScope.launch {
            settingsRepository.setActiveBranch(id)
            val transcript = chatHistoryRepository.transcript(id)
            agent.loadHistory(transcript)
            _uiState.update {
                it.copy(
                    activeBranchId = id,
                    messages = transcript.map(::toUiMessage),
                    tokenTurns = emptyList(),
                    versionGroups = computeVersionGroups(id, allBranches),
                    workingChanged = false,
                    longTermChanged = false
                )
            }
        }
    }

    /** Wipe the whole session: bubbles, persisted history, branches, agent context + token ledger.
     *  Long-term memory is intentionally preserved — it's user profile data, not session data. */
    fun clearSession() {
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            agent.reset()
            chatHistoryRepository.clear()
            settingsRepository.setActiveBranch(ROOT_BRANCH_ID)
            allBranches = chatHistoryRepository.branches()
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    inputText = TextFieldValue(),
                    isStreaming = false,
                    errorMessage = null,
                    tokenTurns = emptyList(),
                    activeBranchId = ROOT_BRANCH_ID,
                    versionGroups = emptyMap(),
                    workingMemory = emptyMap(),
                    workingChanged = false,
                    longTermChanged = false,
                    taskState = TaskState.EMPTY // session-scoped: a fresh session restarts at PLANNING
                    // longTermMemory stays — it survived the clear
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** Day 14: user confirmed the SOFT-invariant warning — re-send the same message, skipping the gate. */
    fun confirmSoftViolation() {
        val pending = _uiState.value.pendingSoftConfirm ?: return
        _uiState.update { it.copy(pendingSoftConfirm = null) }
        dispatch(pending.text, overrideSoft = true)
    }

    /** Day 14: user dismissed the SOFT-invariant warning — drop it, restore the text for editing. */
    fun dismissSoftViolation() {
        val pending = _uiState.value.pendingSoftConfirm ?: return
        _uiState.update {
            it.copy(pendingSoftConfirm = null, inputText = TextFieldValue(pending.text))
        }
    }

    private fun recomputeVersionGroups() {
        val activeId = _uiState.value.activeBranchId
        _uiState.update { it.copy(versionGroups = computeVersionGroups(activeId, allBranches)) }
    }

    /**
     * Build a [VersionInfo] map keyed by message index. A pager appears at index i when there are
     * alternative versions of that message (either the active branch diverges from its parent there,
     * or the active branch has children forking off at i).
     *
     * Family at index i: trunk branch B0 + all children of B0 with forkAtCount == i, ordered by id.
     * If active branch A diverged from its parent at i → B0 = A.parent, A is a sibling.
     * If active branch A has children forking at i → B0 = A, A is position 0 in the family.
     */
    private fun computeVersionGroups(activeBranchId: Long, branches: List<Branch>): Map<Int, VersionInfo> {
        val active = branches.find { it.id == activeBranchId } ?: return emptyMap()
        val result = mutableMapOf<Int, VersionInfo>()

        // Walk up the ancestry chain: each ancestor fork point gets a pager entry.
        var branch = active
        while (true) {
            val parentId = branch.parentBranchId ?: break
            val parent = branches.find { it.id == parentId } ?: break
            val siblings = branches.filter { it.parentBranchId == parentId && it.forkAtCount == branch.forkAtCount }
            val family = (listOf(parent) + siblings).sortedBy { it.id }
            val idx = family.indexOfFirst { it.id == branch.id }
            result[branch.forkAtCount] = VersionInfo(
                current = idx + 1,
                total = family.size,
                prevId = family.getOrNull(idx - 1)?.id,
                nextId = family.getOrNull(idx + 1)?.id
            )
            // Other children of this ancestor forked at earlier indices are alternative paths
            // within our inherited transcript — expose them as pagers too.
            branches
                .filter { it.parentBranchId == parentId && it.forkAtCount < branch.forkAtCount }
                .groupBy { it.forkAtCount }
                .forEach { (k, children) ->
                    if (result.containsKey(k)) return@forEach
                    val altFamily = (listOf(parent) + children).sortedBy { it.id }
                    result[k] = VersionInfo(
                        current = 1,
                        total = altFamily.size,
                        prevId = null,
                        nextId = altFamily.getOrNull(1)?.id
                    )
                }
            branch = parent
        }

        // Forks off the active branch itself: show pager at each child fork point.
        val childForkPoints = branches.filter { it.parentBranchId == activeBranchId }
            .groupBy { it.forkAtCount }
        for ((k, children) in childForkPoints) {
            if (result.containsKey(k)) continue // ancestry pager already covers this index
            val family = (listOf(active) + children).sortedBy { it.id }
            result[k] = VersionInfo(
                current = 1,
                total = family.size,
                prevId = null,
                nextId = family.getOrNull(1)?.id
            )
        }

        return result
    }

    // Branch labels after the root "main": A, B, C, … (existingCount includes the root).
    private fun nextBranchLabel(existingCount: Int): String =
        ('A' + (existingCount - 1).coerceAtLeast(0)).toString()

    // Rebuild a UI bubble from a stored turn: content passes through unchanged.
    private fun toUiMessage(message: ChatMessage): ChatUiMessage =
        ChatUiMessage(role = message.role, content = message.content)

    private fun appendToLast(messages: List<ChatUiMessage>, delta: String): List<ChatUiMessage> {
        val last = messages.last()
        return messages.dropLast(1) + last.copy(content = last.content + delta)
    }
}
