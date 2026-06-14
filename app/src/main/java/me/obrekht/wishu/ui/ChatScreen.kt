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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.DeleteSweep
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.obrekht.wishu.R
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

    // Keep the newest message in view as it streams in.
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.content) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
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
                                CompressionBadge(
                                    enabled = uiState.compressionEnabled,
                                    onToggle = { viewModel.toggleCompression() }
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(uiState.messages) { _, message ->
                    if (message.role == "user") {
                        UserBubble(message.content)
                    } else {
                        AssistantBubble(
                            message = message,
                            tokens = message.tokens
                        )
                    }
                }
            }
        }
    }
}

// Live mirror of the Settings compression toggle, and a tap-target to flip it without leaving the
// chat — so a screen recording shows the mode and switches it in one place.
@Composable
private fun CompressionBadge(enabled: Boolean, onToggle: () -> Unit) {
    val container = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (enabled) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onToggle,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = stringResource(
                if (enabled) R.string.chat_compression_on else R.string.chat_compression_off
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge
            )
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
    tokens: TurnTokens?
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (message.content.isBlank()) {
                    LoadingIndicator(color = MaterialTheme.colorScheme.primary)
                } else {
                    MarkdownText(message.content)
                }
                // Per-turn accounting: DeepSeek's exact token counts for this turn, as a labeled
                // stat grid (label left, value right) so the numbers are easy to read off in a demo.
                tokens?.let {
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
                        // Cache hit/miss split: the hit share is what makes repeated prefixes cheap.
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
                        // This turn folded older messages: show the compression note + its token cost.
                        if (it.summaryPromptTokens > 0) {
                            Text(
                                text = stringResource(R.string.chat_summarized),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            StatRow(
                                stringResource(R.string.token_label_summary),
                                stringResource(
                                    R.string.token_value_summary,
                                    tokens(it.summaryPromptTokens),
                                    tokens(it.summaryCompletionTokens)
                                ),
                                indent = true
                            )
                        }
                    }
                }
            }
        }
    }
}
