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
import me.obrekht.wishu.data.ModelResult
import me.obrekht.wishu.data.ModelsRepository
import me.obrekht.wishu.network.Usage

data class ModelsUiState(
    val prompt: String = ModelsRepository.DEFAULT_PROMPT,
    val running: Boolean = false,
    val results: List<ModelResult> = emptyList(),
    val comparison: String? = null,
    val comparisonReasoning: String = "",
    val comparisonUsage: Usage? = null,
    val errorMessage: String? = null
)

class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WishuApplication
    private val repository = ModelsRepository(app.streamingHttpClient)

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    fun onPromptChange(text: String) {
        _uiState.update { it.copy(prompt = text) }
    }

    // Run the same prompt up the weak→strong ladder sequentially. Each run streams token-by-token: a
    // placeholder card is added when the run starts and its answer grows live as deltas arrive.
    // After all tiers, the comparison call streams the same way.
    fun runAll() {
        if (_uiState.value.running) return
        val prompt = _uiState.value.prompt.trim()
        if (prompt.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    running = true,
                    results = emptyList(),
                    comparison = null,
                    comparisonReasoning = "",
                    comparisonUsage = null,
                    errorMessage = null
                )
            }
            try {
                for (tier in ModelsRepository.TIERS) {
                    val started = System.currentTimeMillis()
                    _uiState.update {
                        it.copy(results = it.results + ModelResult(tier, startedAt = started))
                    }
                    val index = _uiState.value.results.lastIndex
                    val result = repository.generate(prompt, tier) { c, r ->
                        updateProgress(index, c, r)
                    }
                    updateResult(index, force = true) { result }
                }

                val verdict = repository.compare(prompt, _uiState.value.results) { content, reasoning ->
                    if (shouldPublish(force = false)) {
                        _uiState.update { it.copy(comparison = content, comparisonReasoning = reasoning) }
                    }
                }
                _uiState.update {
                    it.copy(
                        comparison = verdict.content,
                        comparisonReasoning = verdict.reasoning,
                        comparisonUsage = verdict.usage,
                        running = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(running = false, errorMessage = e.message ?: "Request failed")
                }
            }
        }
    }

    // Coalesce streamed tokens to ~25 fps so the UI recomposes a few dozen times per second instead
    // of once per token. The stage start/end force a publish so nothing is lost.
    private var lastPublishMs = 0L

    private fun shouldPublish(force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (force || now - lastPublishMs >= 40) {
            lastPublishMs = now
            return true
        }
        return false
    }

    // Atomic, throttled update of one result. Streamed text/elapsed are cumulative snapshots, so a
    // skipped (throttled) update is harmless — the next one carries the full latest value.
    private fun updateResult(index: Int, force: Boolean = false, transform: (ModelResult) -> ModelResult) {
        if (!shouldPublish(force)) return
        _uiState.update { state ->
            if (index !in state.results.indices) return@update state
            val list = state.results.toMutableList()
            list[index] = transform(list[index])
            state.copy(results = list)
        }
    }

    private fun updateProgress(index: Int, content: String, reasoning: String) {
        updateResult(index) { it.copy(answer = content, reasoning = reasoning) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
