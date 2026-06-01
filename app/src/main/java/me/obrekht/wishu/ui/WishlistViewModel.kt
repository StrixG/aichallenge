package me.obrekht.wishu.ui

import android.app.Application
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
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val errorMessage: String? = null
)

class WishlistViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WishuApplication
    private val repository = WishRepository(app.database.wishDao(), app.deepSeekApi)

    private val _uiState = MutableStateFlow(WishlistUiState())
    val uiState: StateFlow<WishlistUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.wishes.collect { wishes ->
                _uiState.update { it.copy(wishes = wishes) }
            }
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun addWish() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return
        viewModelScope.launch {
            repository.addWish(text)
            _uiState.update { it.copy(inputText = "") }
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
                val idea = repository.generateWishIdea()
                _uiState.update { it.copy(inputText = idea, isGenerating = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isGenerating = false, errorMessage = "Failed to generate idea") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
