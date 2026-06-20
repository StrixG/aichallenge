package me.obrekht.wishu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.obrekht.wishu.agent.ContextStrategy
import me.obrekht.wishu.agent.ReplyFormat
import me.obrekht.wishu.agent.ReplyStyle
import me.obrekht.wishu.agent.UserProfile

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

    // The user's declared preferences (display name, reply style/format, free-text constraints).
    // Injected into every request alongside the agent's auto-learned long-term memory. Stored as
    // four flat prefs keys; enums by name.
    private val _profile = MutableStateFlow(
        UserProfile(
            name = prefs.getString(KEY_PROFILE_NAME, "").orEmpty(),
            style = ReplyStyle.fromName(prefs.getString(KEY_PROFILE_STYLE, null)),
            format = ReplyFormat.fromName(prefs.getString(KEY_PROFILE_FORMAT, null)),
            constraints = prefs.getString(KEY_PROFILE_CONSTRAINTS, "").orEmpty()
        )
    )
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    fun setProfile(profile: UserProfile) {
        _profile.value = profile
        prefs.edit()
            .putString(KEY_PROFILE_NAME, profile.name)
            .putString(KEY_PROFILE_STYLE, profile.style.name)
            .putString(KEY_PROFILE_FORMAT, profile.format.name)
            .putString(KEY_PROFILE_CONSTRAINTS, profile.constraints)
            .apply()
    }

    // Bumped when long-term memory is wiped from Settings, so a live chat session can drop its
    // in-memory copy (otherwise the still-loaded agent would re-persist it on the next turn).
    // Not persisted — it's a one-shot in-process signal.
    private val _longTermClearedAt = MutableStateFlow(0L)
    val longTermClearedAt: StateFlow<Long> = _longTermClearedAt.asStateFlow()

    fun signalLongTermCleared() {
        _longTermClearedAt.value = System.currentTimeMillis()
    }

    // Last seed version applied to the invariants table. Lets [InvariantRepository] re-apply the
    // built-in defaults when their definition changes (e.g. new keywords), instead of the old
    // seed-once-when-empty rule that left stale rows on existing installs.
    fun invariantSeedVersion(): Int = prefs.getInt(KEY_INVARIANT_SEED_VERSION, 0)

    fun setInvariantSeedVersion(version: Int) {
        prefs.edit().putInt(KEY_INVARIANT_SEED_VERSION, version).apply()
    }

    companion object {
        const val MODEL_DEFAULT = "deepseek-v4-flash"
        private const val KEY_MODEL = "deepseek_model"
        private const val KEY_STRATEGY = "context_strategy"
        private const val KEY_ACTIVE_BRANCH = "active_branch_id"
        private const val KEY_PROFILE_NAME = "profile_name"
        private const val KEY_PROFILE_STYLE = "profile_style"
        private const val KEY_PROFILE_FORMAT = "profile_format"
        private const val KEY_PROFILE_CONSTRAINTS = "profile_constraints"
        private const val KEY_INVARIANT_SEED_VERSION = "invariant_seed_version"
    }
}
