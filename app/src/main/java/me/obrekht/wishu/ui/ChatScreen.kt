package me.obrekht.wishu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.obrekht.wishu.R
import me.obrekht.wishu.agent.AuxKind
import me.obrekht.wishu.agent.ContextStrategy
import me.obrekht.wishu.agent.TaskStage
import me.obrekht.wishu.agent.TaskState
import me.obrekht.wishu.agent.TurnTokens
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: ChatViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Follow the newest tokens only while the user is at the bottom. If they scroll up to read
    // earlier messages, stop auto-scrolling so the stream doesn't yank them back down.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            // Bottom of the last item within the viewport (+ slack to absorb per-token growth).
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 200
        }
    }
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.content) {
        if (uiState.messages.isNotEmpty() && isAtBottom) {
            // Pin to the very bottom of the last item, so following keeps the newest text visible.
            listState.scrollToItem(uiState.messages.lastIndex, Int.MAX_VALUE)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.chat_title))
                                Spacer(Modifier.width(8.dp))
                                StrategyChip(
                                    strategy = uiState.strategy,
                                    onSelect = { viewModel.setStrategy(it) }
                                )
                            }
                            if (uiState.model.isNotBlank()) {
                                Text(
                                    text = modelLabel(uiState.model),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.cd_navigate_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.clearSession() },
                            enabled = uiState.messages.isNotEmpty() && !uiState.isStreaming
                        ) {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                contentDescription = stringResource(R.string.cd_clear_session)
                            )
                        }
                    }
                )
                // Cumulative token + cost meter for the session (driven by the latest turn).
                uiState.tokenTurns.lastOrNull()?.let { total ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val turnCount = uiState.tokenTurns.size
                        val avgPerTurn = if (turnCount > 0) total.cumulativeTotal / turnCount else 0
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.DataUsage,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = stringResource(
                                        R.string.token_cumulative,
                                        tokens(total.cumulativeTotal),
                                        usd(total.cumulativeCostUsd)
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(
                                        R.string.token_cumulative_detail,
                                        turnCount,
                                        tokens(avgPerTurn)
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.imePadding(),
                tonalElevation = 2.dp
            ) {
                Column {
                    TaskStatePanel(taskState = uiState.taskState)
                    MemoryLayersPanel(
                        shortTermCount = uiState.messages.size,
                        workingMemory = uiState.workingMemory,
                        longTermMemory = uiState.longTermMemory,
                        workingChanged = uiState.workingChanged,
                        longTermChanged = uiState.longTermChanged,
                        memoryUpdating = uiState.memoryUpdating
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    TextField(
                        value = uiState.inputText,
                        onValueChange = { viewModel.onInputChange(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.hint_chat)) },
                        maxLines = 8,
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = { viewModel.send() },
                        enabled = uiState.inputText.text.isNotBlank() && !uiState.isStreaming
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(R.string.cd_send)
                        )
                    }
                }
                }
            }
        }
    ) { padding ->
        if (uiState.messages.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.chat_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val isBranching = uiState.strategy == ContextStrategy.BRANCHING
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(uiState.messages) { index, message ->
                    val versionInfo = uiState.versionGroups[index]
                    if (message.role == "user") {
                        UserBubble(
                            text = message.content,
                            versionInfo = versionInfo,
                            showEditControls = isBranching && !uiState.isStreaming,
                            onEdit = { newText -> viewModel.editMessage(index, newText) },
                            onSwitchBranch = viewModel::switchBranch
                        )
                    } else {
                        AssistantBubble(
                            message = message,
                            tokens = message.tokens,
                            versionInfo = versionInfo,
                            showRegenerate = isBranching && !uiState.isStreaming &&
                                index == uiState.messages.lastIndex,
                            onRegenerate = viewModel::regenerate,
                            onSwitchBranch = viewModel::switchBranch
                        )
                    }
                }
            }
        }
    }

    // Day 14 — SOFT invariant caught on the request: warn and let the user proceed or back out.
    uiState.pendingSoftConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissSoftViolation() },
            icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null) },
            title = { Text(stringResource(R.string.soft_violation_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(confirm.rule, fontWeight = FontWeight.SemiBold)
                    if (confirm.explanation.isNotBlank()) Text(confirm.explanation)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmSoftViolation() }) {
                    Text(stringResource(R.string.soft_violation_proceed))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSoftViolation() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

// Day 14: the body of a refusal bubble — a HARD invariant blocked the reply. Names the rule, explains
// why, and offers an in-constraint alternative when one was generated.
@Composable
private fun RefusalContent(refusal: RefusalInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Block, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.refusal_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Text(refusal.rule, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (refusal.explanation.isNotBlank()) {
            Text(refusal.explanation, style = MaterialTheme.typography.bodyMedium)
        }
        refusal.alternative?.takeIf { it.isNotBlank() }?.let { alt ->
            Text(
                text = stringResource(R.string.refusal_alternative, alt),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// Day 14: a non-blocking SOFT-invariant warning shown under a reply that still stands.
@Composable
private fun SoftWarning(note: String) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Rounded.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )
        Text(
            text = note,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}

// Collapsible task-state panel (Day 13): shows the FSM's stage trail (planning ▸ execution ▸
// validation ▸ done) with the active stage highlighted, and — when expanded — the current step +
// expected action. The stage advances in code (never skips); this just renders it.
@Composable
private fun TaskStatePanel(taskState: TaskState) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = !expanded },
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.task_panel_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    StageTrail(taskState.stage)
                }
                Icon(
                    Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        if (expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TaskInfoRow(
                        label = stringResource(R.string.task_current_step),
                        value = taskState.currentStep.ifBlank { stringResource(R.string.memory_empty) }
                    )
                    TaskInfoRow(
                        label = stringResource(R.string.task_expected_action),
                        value = taskState.expectedAction.ifBlank { stringResource(R.string.memory_empty) }
                    )
                }
            }
        }
    }
}

// The four fixed stages, in order, active one highlighted. Stage names are latin in both locales
// (like the SHORT/WORK/LONG memory chips) — they label the formal FSM states.
@Composable
private fun StageTrail(stage: TaskStage) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TaskStage.all.forEachIndexed { i, s ->
            val active = s == stage
            Text(
                text = stringResource(stageLabel(s)),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            if (i < TaskStage.all.lastIndex) {
                Text(
                    text = " ▸ ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun TaskInfoRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun stageLabel(stage: TaskStage): Int = when (stage) {
    TaskStage.PLANNING -> R.string.task_stage_planning
    TaskStage.EXECUTION -> R.string.task_stage_execution
    TaskStage.VALIDATION -> R.string.task_stage_validation
    TaskStage.DONE -> R.string.task_stage_done
}

// Collapsible memory layers debug panel: shows what's in each of the three memory layers, and
// flags which layer changed on the last turn (an "updated" badge + a dot on the collapsed header).
@Composable
private fun MemoryLayersPanel(
    shortTermCount: Int,
    workingMemory: Map<String, String>,
    longTermMemory: Map<String, String>,
    workingChanged: Boolean,
    longTermChanged: Boolean,
    memoryUpdating: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    val anyChanged = workingChanged || longTermChanged
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = !expanded },
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.memory_panel_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    // Visible even when collapsed: spinner while the memory helpers run, then the
                    // "updated" badge for whichever layer changed.
                    when {
                        memoryUpdating -> {
                            Spacer(Modifier.width(6.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.memory_updating),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        anyChanged -> {
                            Spacer(Modifier.width(6.dp))
                            UpdatedBadge()
                        }
                    }
                }
                Icon(
                    Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        if (expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MemoryLayerRow(
                        label = stringResource(R.string.memory_layer_short),
                        content = stringResource(R.string.memory_messages, shortTermCount),
                        labelColor = MaterialTheme.colorScheme.secondary,
                        updated = false
                    )
                    MemoryLayerRow(
                        label = stringResource(R.string.memory_layer_working),
                        content = factsText(workingMemory),
                        labelColor = MaterialTheme.colorScheme.tertiary,
                        updated = workingChanged
                    )
                    MemoryLayerRow(
                        label = stringResource(R.string.memory_layer_long),
                        content = factsText(longTermMemory),
                        labelColor = MaterialTheme.colorScheme.primary,
                        updated = longTermChanged
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryLayerRow(label: String, content: String, labelColor: Color, updated: Boolean) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = labelColor,
            contentColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        Text(
            text = content,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        if (updated) UpdatedBadge()
    }
}

// Small accent pill that calls out a just-updated layer.
@Composable
private fun UpdatedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = stringResource(R.string.memory_updated_badge),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

private fun factsText(facts: Map<String, String>): String =
    if (facts.isEmpty()) "—"
    else facts.entries.joinToString(" · ") { "${it.key}: ${it.value}" }

// The active context strategy as a tap-to-switch chip with a dropdown — so a screen recording
// shows the mode and switches between all four in one place.
@Composable
private fun StrategyChip(strategy: ContextStrategy, onSelect: (ContextStrategy) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        onClick = { expanded = true },
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.chat_strategy_chip, stringResource(strategyLabel(strategy))),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                Icons.Rounded.ArrowDropDown,
                contentDescription = stringResource(R.string.cd_select_strategy),
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ContextStrategy.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(strategyLabel(option))) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

// Version pager: compact ‹ n/m › row shown under bubbles that have alternative versions.
@Composable
private fun VersionPager(info: VersionInfo, onSwitchBranch: (Long) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(
            onClick = { info.prevId?.let(onSwitchBranch) },
            enabled = info.prevId != null,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.cd_prev_version),
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = stringResource(R.string.version_indicator, info.current, info.total),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        IconButton(
            onClick = { info.nextId?.let(onSwitchBranch) },
            enabled = info.nextId != null,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(R.string.cd_next_version),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun strategyLabel(strategy: ContextStrategy): Int = when (strategy) {
    ContextStrategy.SUMMARY -> R.string.strategy_summary
    ContextStrategy.SLIDING_WINDOW -> R.string.strategy_sliding_window
    ContextStrategy.STICKY_FACTS -> R.string.strategy_sticky_facts
    ContextStrategy.BRANCHING -> R.string.strategy_branching
}

@Composable
private fun UserBubble(
    text: String,
    versionInfo: VersionInfo?,
    showEditControls: Boolean,
    onEdit: (String) -> Unit,
    onSwitchBranch: (Long) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var editText by remember(text) { mutableStateOf(TextFieldValue(text)) }

    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (showEditControls && !editing) {
                IconButton(
                    onClick = { editing = true },
                    modifier = Modifier.size(36.dp).align(Alignment.CenterVertically)
                ) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.cd_edit_message),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            if (editing) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = editText,
                            onValueChange = { editText = it },
                            modifier = Modifier.weight(1f),
                            maxLines = 8,
                            shape = RoundedCornerShape(16.dp),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                        IconButton(
                            onClick = {
                                val newText = editText.text.trim()
                                if (newText.isNotBlank()) {
                                    editing = false
                                    onEdit(newText)
                                }
                            },
                            enabled = editText.text.isNotBlank()
                        ) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.cd_edit_confirm)
                            )
                        }
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    SelectionContainer {
                        Text(
                            text = text,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
        if (versionInfo != null && versionInfo.total > 1) {
            VersionPager(versionInfo, onSwitchBranch)
        }
    }
}

// Cost is a fraction of a cent per turn; show enough decimals to see it move.
private fun usd(value: Double): String = String.format(Locale.US, "%.5f", value)

// Friendly name for a model id (brand names, same as SettingsScreen); raw id as a fallback.
private fun modelLabel(id: String): String = when (id) {
    "deepseek-v4-flash" -> "DeepSeek V4 Flash"
    "deepseek-v4-pro" -> "DeepSeek V4 Pro"
    else -> id
}

// Token counts get big fast; group thousands so they stay scannable (1234 -> 1,234).
private fun tokens(value: Int): String = String.format(Locale.US, "%,d", value)

// Cache-hit share of the prompt, rounded to a whole percent (0 when the prompt is empty).
private fun percent(part: Int, whole: Int): Int =
    if (whole <= 0) 0 else Math.round(100.0 * part / whole).toInt()

// One label/value line in the token stat grid: label left, value right-aligned.
@Composable
private fun StatRow(label: String, value: String, emphasis: Boolean = false, indent: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (indent) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Medium,
            color = if (indent) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AssistantBubble(
    message: ChatUiMessage,
    tokens: TurnTokens?,
    versionInfo: VersionInfo?,
    showRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onSwitchBranch: (Long) -> Unit
) {
    val tokens2 = message.tokens
    val isRefusal = message.refusal != null
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                color = if (isRefusal) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isRefusal) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    when {
                        message.refusal != null -> RefusalContent(message.refusal)
                        message.content.isBlank() -> LoadingIndicator(color = MaterialTheme.colorScheme.primary)
                        else -> SelectionContainer { MarkdownText(message.content) }
                    }
                    message.softNote?.let { SoftWarning(it) }
                    // Per-turn accounting.
                    tokens2?.let {
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Column(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.token_section_title),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            StatRow(stringResource(R.string.token_label_prompt), tokens(it.promptActual), emphasis = true)
                            StatRow(
                                stringResource(R.string.token_label_cache_hit),
                                stringResource(
                                    R.string.token_value_with_pct,
                                    tokens(it.cacheHitActual),
                                    percent(it.cacheHitActual, it.promptActual)
                                ),
                                indent = true
                            )
                            StatRow(stringResource(R.string.token_label_cache_miss), tokens(it.cacheMissActual), indent = true)
                            StatRow(stringResource(R.string.token_label_reply), tokens(it.completionActual))
                            StatRow(stringResource(R.string.token_label_total), tokens(it.totalActual), emphasis = true)
                            StatRow(stringResource(R.string.token_label_cost), "$" + usd(it.turnCostUsd))
                            if (it.auxPromptTokens > 0 && message.aux != AuxKind.NONE) {
                                val noteRes = if (message.aux == AuxKind.FACTS) {
                                    R.string.chat_facts_updated
                                } else {
                                    R.string.chat_summarized
                                }
                                Text(
                                    text = stringResource(noteRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                StatRow(
                                    stringResource(R.string.token_label_aux),
                                    stringResource(
                                        R.string.token_value_aux,
                                        tokens(it.auxPromptTokens),
                                        tokens(it.auxCompletionTokens)
                                    ),
                                    indent = true
                                )
                            }
                        }
                    }
                }
            }
            if (showRegenerate) {
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onRegenerate,
                    modifier = Modifier.size(36.dp).align(Alignment.Bottom)
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.cd_regenerate),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (versionInfo != null && versionInfo.total > 1) {
            VersionPager(versionInfo, onSwitchBranch)
        }
    }
}
