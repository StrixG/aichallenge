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
import me.obrekht.wishu.data.MethodResult
import me.obrekht.wishu.data.ReasoningMethod
import me.obrekht.wishu.data.ReasoningRepository

data class ReasoningUiState(
    val problem: String = ReasoningRepository.DEFAULT_PROBLEM,
    val running: Boolean = false,
    val results: List<MethodResult> = emptyList(),
    val comparison: String? = null,
    val errorMessage: String? = null
)

class ReasoningViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WishuApplication
    private val repository = ReasoningRepository(app.streamingHttpClient)
    private val settingsRepository = app.settingsRepository

    private val _uiState = MutableStateFlow(ReasoningUiState())
    val uiState: StateFlow<ReasoningUiState> = _uiState.asStateFlow()

    fun onProblemChange(text: String) {
        _uiState.update { it.copy(problem = text) }
    }

    // Run all four methods sequentially. Each method streams its text token-by-token: a placeholder
    // card is added when the method starts and its answer/prompt grow live as deltas arrive. After
    // all four, the comparison call streams the same way.
    fun runAll() {
        if (_uiState.value.running) return
        val problem = _uiState.value.problem.trim()
        if (problem.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(running = true, results = emptyList(), comparison = null, errorMessage = null)
            }
            try {
                val model = settingsRepository.selectedModel.value

                // Add a streaming placeholder (stamped with a start time so the UI can tick elapsed
                // per frame), then commit the final MethodResult (forced so the last throttled
                // update is kept).
                suspend fun stage(
                    method: ReasoningMethod,
                    block: suspend (Int) -> MethodResult
                ) {
                    val started = System.currentTimeMillis()
                    _uiState.update {
                        it.copy(results = it.results + MethodResult(method, answer = "", startedAt = started, elapsedMs = 0))
                    }
                    val index = _uiState.value.results.lastIndex
                    val result = block(index)
                    updateResult(index, force = true) { result }
                }

                stage(ReasoningMethod.DIRECT) { i ->
                    repository.solveDirect(problem, model) { c, r -> updateProgress(i, c, r) }
                }
                stage(ReasoningMethod.STEP_BY_STEP) { i ->
                    repository.solveStepByStep(problem, model) { c, r -> updateProgress(i, c, r) }
                }
                stage(ReasoningMethod.SELF_PROMPT) { i ->
                    repository.solveSelfPrompt(
                        problem,
                        model,
                        onPrompt = { updateIntermediate(i, it) },
                        onProgress = { c, r -> updateProgress(i, c, r) }
                    )
                }
                stage(ReasoningMethod.EXPERTS) { i ->
                    repository.solveExperts(problem, model) { c, r -> updateProgress(i, c, r) }
                }

                val comparison = repository.compare(problem, _uiState.value.results, model) { partial ->
                    if (shouldPublish(force = false)) _uiState.update { it.copy(comparison = partial) }
                }
                _uiState.update { it.copy(comparison = comparison, running = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(running = false, errorMessage = e.message ?: "Request failed")
                }
            }
        }
    }

    // Coalesce streamed tokens to ~25 fps so the UI recomposes a few dozen times per second
    // instead of once per token. Stages force a publish at start/end so nothing is lost.
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
    // skipped (throttled) update is harmless — the next one carries the full latest value. Mutating
    // via _uiState.update keeps it safe across the IO (stream) and main (ticker) threads.
    private fun updateResult(index: Int, force: Boolean = false, transform: (MethodResult) -> MethodResult) {
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

    private fun updateIntermediate(index: Int, text: String) {
        updateResult(index) { it.copy(intermediate = text) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
