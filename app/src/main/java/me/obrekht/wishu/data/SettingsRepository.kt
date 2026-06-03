package me.obrekht.wishu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    companion object {
        const val MODEL_DEFAULT = "deepseek-v4-flash"
        private const val KEY_MODEL = "deepseek_model"
    }
}
