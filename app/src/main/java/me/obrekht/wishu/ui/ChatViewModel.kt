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
import me.obrekht.wishu.agent.ChatEvent
import me.obrekht.wishu.agent.ContextWindowExceededException
import me.obrekht.wishu.agent.TurnTokens
import me.obrekht.wishu.agent.WishChatAgent
import me.obrekht.wishu.data.ChatHistoryRepository
import me.obrekht.wishu.network.ChatMessage

data class ChatUiMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    // Token accounting for this assistant turn (null for user turns / restored bubbles).
    val tokens: TurnTokens? = null
)

data class ChatUiState(
    val messages: List<ChatUiMessage> = emptyList(),
    val inputText: TextFieldValue = TextFieldValue(),
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
    // Per-turn token accounting; the last entry carries the cumulative totals/cost.
    val tokenTurns: List<TurnTokens> = emptyList(),
    // Mirrors the Settings compression toggle so the chat shows the active mode.
    val compressionEnabled: Boolean = false,
    // Mirrors the Settings model choice; drives pricing, so it's shown next to the token panel.
    val model: String = ""
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WishuApplication
    private val agent = WishChatAgent(app.streamingHttpClient)
    private val chatHistoryRepository =
        ChatHistoryRepository(app.database.chatMessageDao(), app.database.chatSummaryDao())
    private val settingsRepository = app.settingsRepository

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        // Restore the saved transcript: re-seed the agent (summary + recent tail, so the next
        // request rebuilds the same compressed context) and rebuild every on-screen bubble.
        viewModelScope.launch {
            val saved = chatHistoryRepository.load()
            val savedSummary = chatHistoryRepository.loadSummary()
            if (saved.isEmpty() && savedSummary == null) return@launch
            val summarizedCount = savedSummary?.summarizedCount ?: 0
            // The agent only needs the un-folded tail; the bubbles still show the full log.
            agent.restore(savedSummary?.summary, summarizedCount, saved.drop(summarizedCount))
            _uiState.update { it.copy(messages = saved.map(::toUiMessage)) }
        }
        // Mirror the live compression toggle so the chat badge updates when it's flipped in Settings.
        viewModelScope.launch {
            settingsRepository.compressionEnabled.collect { enabled ->
                _uiState.update { it.copy(compressionEnabled = enabled) }
            }
        }
        // Mirror the live model choice so the chat subtitle updates when it's changed in Settings.
        viewModelScope.launch {
            settingsRepository.selectedModel.collect { model ->
                _uiState.update { it.copy(model = model) }
            }
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
     * the typed [text] is put back in the input box so the user can retry.
     */
    private fun dispatch(text: String) {
        _uiState.update {
            it.copy(
                messages = it.messages +
                    ChatUiMessage(role = "user", content = text) +
                    ChatUiMessage(role = "assistant", content = ""),
                isStreaming = true,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                val model = settingsRepository.selectedModel.value
                val compress = settingsRepository.compressionEnabled.value
                var didSummarize = false
                agent.send(text, model, compress).collect { event ->
                    when (event) {
                        is ChatEvent.Token -> _uiState.update { state ->
                            state.copy(messages = appendToLast(state.messages, event.delta))
                        }
                        is ChatEvent.Complete -> {
                            didSummarize = event.summarized
                            _uiState.update { state ->
                                // Attach to the streaming assistant bubble; keep the list for the
                                // session-cumulative meter (its last entry holds the running totals).
                                val last = state.messages.last()
                                state.copy(
                                    messages = state.messages.dropLast(1) +
                                        last.copy(tokens = event.tokens),
                                    tokenTurns = state.tokenTurns + event.tokens
                                )
                            }
                        }
                    }
                }
                val rawReply = _uiState.value.messages.last().content
                _uiState.update { it.copy(isStreaming = false) }
                // Persist the completed turn.
                chatHistoryRepository.append("user", text)
                chatHistoryRepository.append("assistant", rawReply)
                // If this turn folded older messages, persist the new summary + its coverage so a
                // restart rebuilds the same compressed context.
                if (didSummarize) {
                    agent.summary?.let { chatHistoryRepository.saveSummary(it, agent.summarizedCount) }
                }
            } catch (e: ContextWindowExceededException) {
                // The model's real context window overflowed. The agent didn't commit the turn,
                // so drop both bubbles, optionally restore the input, and show the distinct error.
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.dropLast(2),
                        inputText = TextFieldValue(text),
                        isStreaming = false,
                        errorMessage = app.getString(R.string.error_context_window)
                    )
                }
            } catch (e: Exception) {
                // Send failed (network, etc.); the agent didn't commit the turn. Drop both
                // bubbles, optionally restore the input, and surface the error.
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.dropLast(2),
                        inputText = TextFieldValue(text),
                        isStreaming = false,
                        errorMessage = app.getString(R.string.error_chat)
                    )
                }
            }
        }
    }

    /** Flip history compression from the chat screen (same setting the Settings toggle drives). */
    fun toggleCompression() {
        settingsRepository.setCompression(!_uiState.value.compressionEnabled)
    }

    /** Wipe the whole session: on-screen bubbles, persisted history, agent context + token ledger. */
    fun clearSession() {
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            agent.reset()
            chatHistoryRepository.clear()
            // Reset only the conversation fields with copy(); the mirrored settings (compression,
            // model) stay as-is. Their StateFlow collectors won't re-emit an unchanged value, so
            // rebuilding the whole state would leave them stuck at defaults.
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    inputText = TextFieldValue(),
                    isStreaming = false,
                    errorMessage = null,
                    tokenTurns = emptyList()
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // Rebuild a UI bubble from a stored turn: content passes through unchanged.
    private fun toUiMessage(message: ChatMessage): ChatUiMessage =
        ChatUiMessage(role = message.role, content = message.content)

    private fun appendToLast(messages: List<ChatUiMessage>, delta: String): List<ChatUiMessage> {
        val last = messages.last()
        return messages.dropLast(1) + last.copy(content = last.content + delta)
    }
}
