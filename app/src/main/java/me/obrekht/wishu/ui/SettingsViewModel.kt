package me.obrekht.wishu.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import me.obrekht.wishu.WishuApplication

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = (application as WishuApplication).settingsRepository

    val selectedModel: StateFlow<String> = settingsRepository.selectedModel

    fun setModel(model: String) = settingsRepository.setModel(model)
}
