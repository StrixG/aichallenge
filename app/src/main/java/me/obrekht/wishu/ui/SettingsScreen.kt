package me.obrekht.wishu.ui

import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import me.obrekht.wishu.R

private data class LanguageOption(val tag: String, val label: String)
private data class ModelOption(val id: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(
                icon = Icons.Rounded.Language,
                title = stringResource(R.string.language_label)
            ) {
                languages.forEachIndexed { index, option ->
                    SelectableOption(
                        label = option.label,
                        selected = selectedTag == option.tag,
                        shape = groupedItemShape(index, languages.size),
                        onSelect = {
                            selectedTag = option.tag
                            val locales = if (option.tag.isEmpty()) {
                                LocaleListCompat.getEmptyLocaleList()
                            } else {
                                LocaleListCompat.forLanguageTags(option.tag)
                            }
                            AppCompatDelegate.setApplicationLocales(locales)
                        }
                    )
                }
            }

            SettingsSection(
                icon = Icons.Rounded.Memory,
                title = stringResource(R.string.model_label)
            ) {
                models.forEachIndexed { index, option ->
                    SelectableOption(
                        label = option.label,
                        selected = selectedModel == option.id,
                        shape = groupedItemShape(index, models.size),
                        onSelect = { viewModel.setModel(option.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 4.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content()
        }
    }
}

// Android 16 grouped-list shape: big outer corners on first/last item,
// small inner corners between connected items.
private fun groupedItemShape(index: Int, count: Int): RoundedCornerShape {
    val large = 20.dp
    val small = 4.dp
    val first = index == 0
    val last = index == count - 1
    return RoundedCornerShape(
        topStart = if (first) large else small,
        topEnd = if (first) large else small,
        bottomStart = if (last) large else small,
        bottomEnd = if (last) large else small
    )
}

@Composable
private fun SelectableOption(
    label: String,
    selected: Boolean,
    shape: RoundedCornerShape,
    onSelect: () -> Unit
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onSelect,
        selected = selected,
        shape = shape,
        color = container,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                RadioButton(selected = selected, onClick = null)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
