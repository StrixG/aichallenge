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
import me.obrekht.wishu.agent.WishChatAgent
import me.obrekht.wishu.agent.parseWishItems
import me.obrekht.wishu.agent.stripWishItems
import me.obrekht.wishu.data.ChatHistoryRepository
import me.obrekht.wishu.data.WishRepository
import me.obrekht.wishu.network.ChatMessage

data class ChatUiMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    val items: List<String> = emptyList()
)

data class ChatUiState(
    val messages: List<ChatUiMessage> = emptyList(),
    val inputText: TextFieldValue = TextFieldValue(),
    val isStreaming: Boolean = false,
    val errorMessage: String? = null
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

        _uiState.update {
            it.copy(
                messages = it.messages +
                    ChatUiMessage(role = "user", content = text) +
                    ChatUiMessage(role = "assistant", content = ""),
                inputText = TextFieldValue(),
                isStreaming = true,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                val model = settingsRepository.selectedModel.value
                agent.send(text, model).collect { delta ->
                    _uiState.update { state -> state.copy(messages = appendToLast(state.messages, delta)) }
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
                chatHistoryRepository.append("user", text)
                chatHistoryRepository.append("assistant", rawReply)
            } catch (e: Exception) {
                // Drop the empty assistant placeholder and surface the error.
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.dropLast(1),
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
}
