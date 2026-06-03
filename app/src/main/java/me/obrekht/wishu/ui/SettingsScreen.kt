package me.obrekht.wishu.ui

import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import me.obrekht.wishu.R

private data class LanguageOption(val tag: String, val label: String)
private data class ModelOption(val id: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    BackHandler(onBack = onNavigateBack)

    val languages = listOf(
        LanguageOption("", stringResource(R.string.language_system_default)),
        LanguageOption("en", "English"),
        LanguageOption("ru", "Русский"),
    )
    val currentTag = AppCompatDelegate.getApplicationLocales()
        .let { if (it.isEmpty) "" else it[0]?.language ?: "" }
    var selectedTag by remember { mutableStateOf(currentTag) }

    val models = listOf(
        ModelOption("deepseek-v4-flash", "DeepSeek V4 Flash"),
        ModelOption("deepseek-v4-pro", "DeepSeek V4 Pro")
    )
    val selectedModel by viewModel.selectedModel.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_navigate_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Text(
                text = stringResource(R.string.language_label),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            languages.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedTag = option.tag
                            val locales = if (option.tag.isEmpty()) {
                                LocaleListCompat.getEmptyLocaleList()
                            } else {
                                LocaleListCompat.forLanguageTags(option.tag)
                            }
                            AppCompatDelegate.setApplicationLocales(locales)
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedTag == option.tag, onClick = null)
                    Text(text = option.label, modifier = Modifier.padding(start = 8.dp))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.model_label),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            models.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setModel(option.id) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedModel == option.id, onClick = null)
                    Text(text = option.label, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
