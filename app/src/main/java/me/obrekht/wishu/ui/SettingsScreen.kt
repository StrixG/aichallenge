package me.obrekht.wishu.ui

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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import me.obrekht.wishu.agent.ContextStrategy
import me.obrekht.wishu.agent.ReplyFormat
import me.obrekht.wishu.agent.ReplyStyle

private data class LanguageOption(val tag: String, val label: String)
private data class ModelOption(val id: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenInvariants: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
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
    val strategy by viewModel.strategy.collectAsState()
    val profile by viewModel.profile.collectAsState()

    var showClearMemoryDialog by remember { mutableStateOf(false) }

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
                icon = Icons.Rounded.Person,
                title = stringResource(R.string.profile_label)
            ) {
                OutlinedTextField(
                    value = profile.name,
                    onValueChange = { name -> viewModel.updateProfile { it.copy(name = name) } },
                    label = { Text(stringResource(R.string.profile_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.profile_style_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
                val styles = ReplyStyle.entries
                styles.forEachIndexed { index, option ->
                    SelectableOption(
                        label = stringResource(replyStyleLabel(option)),
                        selected = profile.style == option,
                        shape = groupedItemShape(index, styles.size),
                        onSelect = { viewModel.updateProfile { it.copy(style = option) } }
                    )
                }
                Text(
                    text = stringResource(R.string.profile_format_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
                val formats = ReplyFormat.entries
                formats.forEachIndexed { index, option ->
                    SelectableOption(
                        label = stringResource(replyFormatLabel(option)),
                        selected = profile.format == option,
                        shape = groupedItemShape(index, formats.size),
                        onSelect = { viewModel.updateProfile { it.copy(format = option) } }
                    )
                }
                OutlinedTextField(
                    value = profile.constraints,
                    onValueChange = { c -> viewModel.updateProfile { it.copy(constraints = c) } },
                    label = { Text(stringResource(R.string.profile_constraints_label)) },
                    placeholder = { Text(stringResource(R.string.profile_constraints_hint)) },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }

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

            SettingsSection(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.strategy_label)
            ) {
                val strategies = ContextStrategy.entries
                strategies.forEachIndexed { index, option ->
                    SelectableOption(
                        label = stringResource(strategyLabel(option)),
                        selected = strategy == option,
                        shape = groupedItemShape(index, strategies.size),
                        onSelect = { viewModel.setStrategy(option) }
                    )
                }
            }

            SettingsSection(
                icon = Icons.Rounded.Shield,
                title = stringResource(R.string.invariants_title)
            ) {
                NavigationOption(
                    label = stringResource(R.string.invariants_settings_label),
                    description = stringResource(R.string.invariants_settings_caption),
                    shape = groupedItemShape(0, 1),
                    onClick = onOpenInvariants
                )
            }

            SettingsSection(
                icon = Icons.Rounded.Psychology,
                title = stringResource(R.string.memory_section_title)
            ) {
                DestructiveOption(
                    label = stringResource(R.string.clear_long_term),
                    description = stringResource(R.string.clear_long_term_caption),
                    shape = groupedItemShape(0, 1),
                    onClick = { showClearMemoryDialog = true }
                )
            }
        }
    }

    if (showClearMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearMemoryDialog = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.clear_long_term)) },
            text = { Text(stringResource(R.string.clear_long_term_dialog)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearMemoryDialog = false
                    viewModel.clearLongTermMemory()
                }) {
                    Text(stringResource(R.string.clear_long_term_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearMemoryDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

private fun strategyLabel(strategy: ContextStrategy): Int = when (strategy) {
    ContextStrategy.SUMMARY -> R.string.strategy_summary
    ContextStrategy.SLIDING_WINDOW -> R.string.strategy_sliding_window
    ContextStrategy.STICKY_FACTS -> R.string.strategy_sticky_facts
    ContextStrategy.BRANCHING -> R.string.strategy_branching
}

private fun replyStyleLabel(style: ReplyStyle): Int = when (style) {
    ReplyStyle.DEFAULT -> R.string.profile_style_default
    ReplyStyle.CONCISE -> R.string.profile_style_concise
    ReplyStyle.DETAILED -> R.string.profile_style_detailed
    ReplyStyle.FORMAL -> R.string.profile_style_formal
    ReplyStyle.PLAYFUL -> R.string.profile_style_playful
}

private fun replyFormatLabel(format: ReplyFormat): Int = when (format) {
    ReplyFormat.DEFAULT -> R.string.profile_format_default
    ReplyFormat.BULLETS -> R.string.profile_format_bullets
    ReplyFormat.PROSE -> R.string.profile_format_prose
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
private fun NavigationOption(
    label: String,
    description: String,
    shape: RoundedCornerShape,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DestructiveOption(
    label: String,
    description: String,
    shape: RoundedCornerShape,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
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
