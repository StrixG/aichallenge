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
import me.obrekht.wishu.invariant.Invariant

data class InvariantsUiState(
    val invariants: List<Invariant> = emptyList(),
    // The invariant currently open in the editor (null = editor closed); a blank-id one means "add new".
    val editing: Invariant? = null
)

/**
 * Day 14: backs the invariants management screen. All business logic (mapping, persistence) stays here;
 * the screen only renders state + forwards intents. Mirrors the live repository [Flow].
 */
class InvariantsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as WishuApplication).invariantRepository

    private val _uiState = MutableStateFlow(InvariantsUiState())
    val uiState: StateFlow<InvariantsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.seedDefaults()
            repository.invariants.collect { list ->
                _uiState.update { it.copy(invariants = list) }
            }
        }
    }

    fun openEditor(invariant: Invariant) = _uiState.update { it.copy(editing = invariant) }

    fun dismissEditor() = _uiState.update { it.copy(editing = null) }

    fun save(invariant: Invariant) {
        viewModelScope.launch { repository.upsert(invariant) }
        dismissEditor()
    }

    fun delete(invariant: Invariant) {
        viewModelScope.launch { repository.delete(invariant) }
    }
}
