package me.obrekht.wishu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.obrekht.wishu.agent.ContextStrategy

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("wishu_settings", Context.MODE_PRIVATE)

    private val _selectedModel = MutableStateFlow(
        prefs.getString(KEY_MODEL, MODEL_DEFAULT) ?: MODEL_DEFAULT
    )
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    fun setModel(model: String) {
        _selectedModel.value = model
        prefs.edit().putString(KEY_MODEL, model).apply()
    }

    // Context-management strategy used when building each request (Sliding Window / Summary /
    // Sticky Facts / Branching). Persisted by enum name.
    private val _strategy = MutableStateFlow(
        ContextStrategy.fromName(prefs.getString(KEY_STRATEGY, null))
    )
    val strategy: StateFlow<ContextStrategy> = _strategy.asStateFlow()

    fun setStrategy(strategy: ContextStrategy) {
        _strategy.value = strategy
        prefs.edit().putString(KEY_STRATEGY, strategy.name).apply()
    }

    // The branch the chat is currently on (Branching strategy). Defaults to the root branch.
    private val _activeBranchId = MutableStateFlow(
        prefs.getLong(KEY_ACTIVE_BRANCH, ROOT_BRANCH_ID)
    )
    val activeBranchId: StateFlow<Long> = _activeBranchId.asStateFlow()

    fun setActiveBranch(id: Long) {
        _activeBranchId.value = id
        prefs.edit().putLong(KEY_ACTIVE_BRANCH, id).apply()
    }

    // Bumped when long-term memory is wiped from Settings, so a live chat session can drop its
    // in-memory copy (otherwise the still-loaded agent would re-persist it on the next turn).
    // Not persisted — it's a one-shot in-process signal.
    private val _longTermClearedAt = MutableStateFlow(0L)
    val longTermClearedAt: StateFlow<Long> = _longTermClearedAt.asStateFlow()

    fun signalLongTermCleared() {
        _longTermClearedAt.value = System.currentTimeMillis()
    }

    companion object {
        const val MODEL_DEFAULT = "deepseek-v4-flash"
        private const val KEY_MODEL = "deepseek_model"
        private const val KEY_STRATEGY = "context_strategy"
        private const val KEY_ACTIVE_BRANCH = "active_branch_id"
    }
}
