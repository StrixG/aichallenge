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
import me.obrekht.wishu.WishuApplication
import me.obrekht.wishu.data.Wish
import me.obrekht.wishu.data.WishRepository

data class WishlistUiState(
    val wishes: List<Wish> = emptyList(),
    val inputText: TextFieldValue = TextFieldValue(),
    val isGenerating: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val errorMessage: String? = null
)

class WishlistViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WishuApplication
    private val repository = WishRepository(app.database.wishDao(), app.deepSeekApi)
    private val settingsRepository = app.settingsRepository

    private val _uiState = MutableStateFlow(WishlistUiState())
    val uiState: StateFlow<WishlistUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.wishes.collect { wishes ->
                _uiState.update { it.copy(wishes = wishes) }
            }
        }
    }

    fun onInputChange(value: TextFieldValue) {
        _uiState.update { it.copy(inputText = value) }
    }

    fun addWish() {
        val text = _uiState.value.inputText.text.trim()
        if (text.isBlank()) return
        viewModelScope.launch {
            repository.addWish(text)
            _uiState.update { it.copy(inputText = TextFieldValue()) }
        }
    }

    fun deleteWish(wish: Wish) {
        viewModelScope.launch { repository.deleteWish(wish) }
    }

    fun generateWishIdea() {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, errorMessage = null) }
            try {
                val model = settingsRepository.selectedModel.value
                val ideas = repository.generateWishIdeas(app.getString(me.obrekht.wishu.R.string.prompt_generate_wish), model)
                _uiState.update { it.copy(suggestions = ideas, isGenerating = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isGenerating = false, errorMessage = app.getString(me.obrekht.wishu.R.string.error_generate_idea)) }
            }
        }
    }

    fun addSuggestions(ideas: List<String>) {
        if (ideas.isEmpty()) {
            dismissSuggestions()
            return
        }
        viewModelScope.launch {
            ideas.forEach { repository.addWish(it) }
            _uiState.update { it.copy(suggestions = emptyList()) }
        }
    }

    fun dismissSuggestions() {
        _uiState.update { it.copy(suggestions = emptyList()) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
