package me.obrekht.wishu.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.obrekht.wishu.R
import me.obrekht.wishu.invariant.CheckType
import me.obrekht.wishu.invariant.DetKind
import me.obrekht.wishu.invariant.Invariant
import me.obrekht.wishu.invariant.Severity
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InvariantsScreen(
    onNavigateBack: () -> Unit,
    viewModel: InvariantsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.invariants_title)) },
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
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.openEditor(blankInvariant()) }) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.cd_add_invariant))
            }
        }
    ) { padding ->
        if (uiState.invariants.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.invariant_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.invariants, key = { it.id }) { invariant ->
                    InvariantCard(
                        invariant = invariant,
                        onClick = { viewModel.openEditor(invariant) },
                        onDelete = { viewModel.delete(invariant) }
                    )
                }
            }
        }
    }

    uiState.editing?.let { editing ->
        InvariantEditorDialog(
            initial = editing,
            onSave = viewModel::save,
            onDismiss = viewModel::dismissEditor
        )
    }
}

@Composable
private fun InvariantCard(invariant: Invariant, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(invariant.rule, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(
                        stringResource(severityLabel(invariant.severity)),
                        if (invariant.severity == Severity.HARD) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.tertiary
                    )
                    Badge(stringResource(checkLabel(invariant.check)), MaterialTheme.colorScheme.primary)
                }
                if (invariant.refusalReason.isNotBlank()) {
                    Text(
                        invariant.refusalReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (invariant.keywords.isNotEmpty()) {
                    Text(
                        invariant.keywords.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.cd_delete_invariant),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, contentColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InvariantEditorDialog(
    initial: Invariant,
    onSave: (Invariant) -> Unit,
    onDismiss: () -> Unit
) {
    var rule by remember { mutableStateOf(initial.rule) }
    var refusal by remember { mutableStateOf(initial.refusalReason) }
    var severity by remember { mutableStateOf(initial.severity) }
    var check by remember { mutableStateOf(initial.check) }
    var detKind by remember { mutableStateOf(initial.detKind) }
    var keywords by remember { mutableStateOf(initial.keywords.joinToString(", ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.invariants_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = rule,
                    onValueChange = { rule = it },
                    label = { Text(stringResource(R.string.invariant_field_rule)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = refusal,
                    onValueChange = { refusal = it },
                    label = { Text(stringResource(R.string.invariant_field_refusal)) },
                    modifier = Modifier.fillMaxWidth()
                )
                EnumSelector(
                    label = stringResource(R.string.invariant_field_severity),
                    options = Severity.entries,
                    selected = severity,
                    optionLabel = { stringResource(severityLabel(it)) },
                    onSelect = { severity = it }
                )
                EnumSelector(
                    label = stringResource(R.string.invariant_field_check),
                    options = CheckType.entries,
                    selected = check,
                    optionLabel = { stringResource(checkLabel(it)) },
                    onSelect = { check = it }
                )
                // LLM-only check never runs the deterministic layer, so its config is dead — hide it
                // and reset detKind to NONE so a stale selection isn't persisted on save.
                LaunchedEffect(check) {
                    if (check == CheckType.LLM) detKind = DetKind.NONE
                }
                if (check != CheckType.LLM) {
                    EnumSelector(
                        label = stringResource(R.string.invariant_field_detkind),
                        options = DetKind.entries,
                        selected = detKind,
                        optionLabel = { stringResource(detKindLabel(it)) },
                        onSelect = { detKind = it }
                    )
                    if (detKind == DetKind.BANNED_KEYWORDS) {
                        OutlinedTextField(
                            value = keywords,
                            onValueChange = { keywords = it },
                            label = { Text(stringResource(R.string.invariant_field_keywords)) },
                            placeholder = { Text(stringResource(R.string.invariant_keywords_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = rule.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            rule = rule.trim(),
                            refusalReason = refusal.trim(),
                            severity = severity,
                            check = check,
                            detKind = detKind,
                            keywords = keywords.split(',', '|')
                                .map { it.trim() }.filter { it.isNotEmpty() }
                        )
                    )
                }
            ) { Text(stringResource(R.string.invariant_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun <T> EnumSelector(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    selected = isSelected,
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface
                ) {
                    Text(
                        text = optionLabel(option),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

private fun blankInvariant() = Invariant(
    id = UUID.randomUUID().toString(),
    rule = "",
    check = CheckType.LLM,
    severity = Severity.HARD,
    refusalReason = ""
)

private fun severityLabel(severity: Severity): Int = when (severity) {
    Severity.HARD -> R.string.invariant_severity_hard
    Severity.SOFT -> R.string.invariant_severity_soft
}

private fun checkLabel(check: CheckType): Int = when (check) {
    CheckType.DETERMINISTIC -> R.string.invariant_check_deterministic
    CheckType.LLM -> R.string.invariant_check_llm
    CheckType.BOTH -> R.string.invariant_check_both
}

private fun detKindLabel(detKind: DetKind): Int = when (detKind) {
    DetKind.NONE -> R.string.invariant_detkind_none
    DetKind.BUDGET_CEILING -> R.string.invariant_detkind_budget
    DetKind.BANNED_KEYWORDS -> R.string.invariant_detkind_keywords
}
