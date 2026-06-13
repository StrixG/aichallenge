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
import me.obrekht.wishu.agent.parseWishItems
import me.obrekht.wishu.agent.stripWishItems
import me.obrekht.wishu.data.ChatHistoryRepository
import me.obrekht.wishu.data.WishRepository
import me.obrekht.wishu.network.ChatMessage

data class ChatUiMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    val items: List<String> = emptyList(),
    // Token accounting for this assistant turn (null for user turns / restored bubbles).
    val tokens: TurnTokens? = null
)

data class ChatUiState(
    val messages: List<ChatUiMessage> = emptyList(),
    val inputText: TextFieldValue = TextFieldValue(),
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
    // Per-turn token accounting; the last entry carries the cumulative totals/cost.
    val tokenTurns: List<TurnTokens> = emptyList()
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WishuApplication
    private val agent = WishChatAgent(app.streamingHttpClient)
    private val wishRepository = WishRepository(app.database.wishDao(), app.deepSeekApi)
    private val chatHistoryRepository = ChatHistoryRepository(app.database.chatMessageDao())
    private val settingsRepository = app.settingsRepository

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        // Restore the saved transcript: re-seed the agent (so DeepSeek gets full context again)
        // and rebuild the on-screen bubbles as if the agent never stopped.
        viewModelScope.launch {
            val saved = chatHistoryRepository.load()
            if (saved.isEmpty()) return@launch
            agent.restore(saved)
            _uiState.update { it.copy(messages = saved.map(::toUiMessage)) }
        }
    }

    fun onInputChange(value: TextFieldValue) {
        _uiState.update { it.copy(inputText = value) }
    }

    fun send() {
        val text = _uiState.value.inputText.text.trim()
        if (text.isBlank() || _uiState.value.isStreaming) return
        _uiState.update { it.copy(inputText = TextFieldValue()) }
        // On failure restore the typed text so the user can retry.
        dispatch(displayText = text, payloadText = text, restoreOnError = text)
    }

    /**
     * Demo: fire a turn whose payload is a multi-megabyte dummy string — far past the model's real
     * ~1M-token context window — so DeepSeek rejects it and the distinct overflow error shows
     * without anyone pasting a novel. The bubble shows a short label, not the giant payload.
     */
    fun simulateOverflow() {
        if (_uiState.value.isStreaming) return
        val dummy = "lorem ipsum dolor sit amet ".repeat(200_000) // ~5.4M chars ≈ 1.35M tokens
        dispatch(displayText = OVERFLOW_LABEL, payloadText = dummy, restoreOnError = null)
    }

    /**
     * Append the user/assistant bubbles, stream the reply, and handle completion/errors.
     * [displayText] is what the user bubble shows; [payloadText] is what's actually sent (they
     * differ only for [simulateOverflow]). On failure [restoreOnError], if non-null, is put back
     * in the input box.
     */
    private fun dispatch(displayText: String, payloadText: String, restoreOnError: String?) {
        _uiState.update {
            it.copy(
                messages = it.messages +
                    ChatUiMessage(role = "user", content = displayText) +
                    ChatUiMessage(role = "assistant", content = ""),
                isStreaming = true,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                val model = settingsRepository.selectedModel.value
                agent.send(payloadText, model).collect { event ->
                    when (event) {
                        is ChatEvent.Token -> _uiState.update { state ->
                            state.copy(messages = appendToLast(state.messages, event.delta))
                        }
                        is ChatEvent.Complete -> _uiState.update { state ->
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
                val rawReply = _uiState.value.messages.last().content
                _uiState.update { state ->
                    val last = state.messages.last()
                    val items = parseWishItems(last.content)
                    // Drop the bullet lines from the bubble text; they render as add buttons.
                    val display = if (items.isEmpty()) last.content else stripWishItems(last.content)
                    state.copy(
                        messages = state.messages.dropLast(1) +
                            last.copy(content = display, items = items),
                        isStreaming = false
                    )
                }
                // Persist the completed turn. Store the raw assistant content (with bullets) so
                // parseWishItems works again on restore, exactly like a fresh stream.
                chatHistoryRepository.append("user", displayText)
                chatHistoryRepository.append("assistant", rawReply)
            } catch (e: ContextWindowExceededException) {
                // The model's real context window overflowed. The agent didn't commit the turn,
                // so drop both bubbles, optionally restore the input, and show the distinct error.
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.dropLast(2),
                        inputText = restoreOnError?.let { TextFieldValue(it) } ?: state.inputText,
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
                        inputText = restoreOnError?.let { TextFieldValue(it) } ?: state.inputText,
                        isStreaming = false,
                        errorMessage = app.getString(R.string.error_chat)
                    )
                }
            }
        }
    }

    fun addItem(text: String) {
        viewModelScope.launch { wishRepository.addWish(text) }
    }

    /** Wipe the whole session: on-screen bubbles, persisted history, agent context + token ledger. */
    fun clearSession() {
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            agent.reset()
            chatHistoryRepository.clear()
            _uiState.value = ChatUiState()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // Rebuild a UI bubble from a stored turn. Assistant bullet lines become add buttons again
    // (same parse/strip as the live post-stream path); user turns pass through unchanged.
    private fun toUiMessage(message: ChatMessage): ChatUiMessage {
        if (message.role != "assistant") {
            return ChatUiMessage(role = message.role, content = message.content)
        }
        val items = parseWishItems(message.content)
        val display = if (items.isEmpty()) message.content else stripWishItems(message.content)
        return ChatUiMessage(role = "assistant", content = display, items = items)
    }

    private fun appendToLast(messages: List<ChatUiMessage>, delta: String): List<ChatUiMessage> {
        val last = messages.last()
        return messages.dropLast(1) + last.copy(content = last.content + delta)
    }

    private companion object {
        const val OVERFLOW_LABEL = "⚠️ Simulating context overflow…"
    }
}
