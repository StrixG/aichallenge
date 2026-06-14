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

    // History compression: fold old turns into a summary instead of re-sending the whole dialog.
    private val _compressionEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_COMPRESSION, COMPRESSION_DEFAULT)
    )
    val compressionEnabled: StateFlow<Boolean> = _compressionEnabled.asStateFlow()

    fun setCompression(enabled: Boolean) {
        _compressionEnabled.value = enabled
        prefs.edit().putBoolean(KEY_COMPRESSION, enabled).apply()
    }

    companion object {
        const val MODEL_DEFAULT = "deepseek-v4-flash"
        private const val KEY_MODEL = "deepseek_model"
        private const val COMPRESSION_DEFAULT = true
        private const val KEY_COMPRESSION = "compress_history"
    }
}
