package me.obrekht.wishu.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.obrekht.wishu.WishuApplication
import me.obrekht.wishu.agent.ContextStrategy
import me.obrekht.wishu.data.ChatHistoryRepository

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WishuApplication
    private val settingsRepository = app.settingsRepository
    private val chatHistoryRepository = ChatHistoryRepository(
        app.database.chatMessageDao(),
        app.database.chatSummaryDao(),
        app.database.chatFactsDao(),
        app.database.chatBranchDao(),
        app.database.longTermMemoryDao()
    )

    val selectedModel: StateFlow<String> = settingsRepository.selectedModel

    fun setModel(model: String) = settingsRepository.setModel(model)

    val strategy: StateFlow<ContextStrategy> = settingsRepository.strategy

    fun setStrategy(strategy: ContextStrategy) = settingsRepository.setStrategy(strategy)

    /** Wipe the persistent long-term profile and signal any live chat session to drop its copy. */
    fun clearLongTermMemory() {
        viewModelScope.launch {
            chatHistoryRepository.clearLongTermMemory()
            settingsRepository.signalLongTermCleared()
        }
    }
}
