package me.obrekht.wishu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.obrekht.wishu.data.ModelResult
import me.obrekht.wishu.network.Usage

// What each tier is, shown under the label so the comparison is self-explanatory.
private fun describeTier(result: ModelResult): String =
    "${result.tier.model}, effort = ${result.tier.effort}"

// Token cost reported by the API: total, broken into prompt + completion.
@Composable
private fun TokenLabel(usage: Usage) {
    Text(
        "Токены: ${usage.totalTokens} (промпт ${usage.promptTokens} + ответ ${usage.completionTokens})",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// USD cost of a single run, shown with enough decimals to be non-zero for tiny requests.
@Composable
private fun CostLabel(result: ModelResult) {
    Text(
        "Стоимость: \$${"%.6f".format(result.costUsd)}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ModelsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Total streamed characters: changes on every token (answer + chain-of-thought), drives the
    // autoscroll effect.
    val contentSignal = uiState.results.sumOf { it.answer.length + it.reasoning.length } +
        (uiState.comparison?.length ?: 0) + uiState.comparisonReasoning.length + uiState.results.size

    // "Near bottom" judged purely by how far the last visible item's bottom sits from the viewport
    // bottom (tolerance ~400px). Deliberately NOT requiring that item to be the final one: when a new
    // card is appended below the fold it isn't visible yet, so an index check would wrongly release
    // the pin. The pixel distance stays ~0 while pinned, so a freshly added card still autoscrolls.
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.offset + last.size <= info.viewportEndOffset + 400
        }
    }

    // Pin to bottom while streaming. Snap to the end of the last item; the collapsible reasoning
    // sections change height when toggled, so the smooth overshoot math can't be relied on here.
    LaunchedEffect(contentSignal) {
        if (isNearBottom) {
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Версии моделей") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = uiState.prompt,
                    onValueChange = { viewModel.onPromptChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Один промпт для всех моделей") },
                    enabled = !uiState.running,
                    minLines = 2
                )
            }
            item {
                Button(
                    onClick = { viewModel.runAll() },
                    enabled = !uiState.running && uiState.prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (uiState.running) "Выполняется…" else "Запустить: слабая / средняя / сильная")
                }
            }

            // The last card is the one currently streaming (until the comparison starts).
            val activeIndex = if (uiState.running && uiState.comparison == null) {
                uiState.results.lastIndex
            } else {
                -1
            }
            itemsIndexed(uiState.results, key = { _, r -> r.tier.label }) { index, result ->
                ModelCard(result, streaming = index == activeIndex)
            }

            uiState.comparison?.let { comparison ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Сравнение и выводы",
                                style = MaterialTheme.typography.titleMedium
                            )
                            // Judge's chain-of-thought, collapsible like the model cards.
                            ReasoningSection(
                                reasoning = uiState.comparisonReasoning,
                                thinking = uiState.running && comparison.isBlank()
                            )
                            if (comparison.isNotBlank()) {
                                Spacer(Modifier.size(8.dp))
                                MarkdownText(comparison)
                                if (uiState.running) BlinkingCaret()
                            }
                            uiState.comparisonUsage?.let {
                                Spacer(Modifier.size(8.dp))
                                TokenLabel(it)
                            }
                        }
                    }
                }
            }

            // Grand total once everything finished: sum of all generations + the comparison call.
            if (!uiState.running && uiState.comparison != null) {
                val grandTokens = uiState.results.sumOf { it.usage?.totalTokens ?: 0 } +
                    (uiState.comparisonUsage?.totalTokens ?: 0)
                val grandCost = uiState.results.sumOf { it.costUsd }
                if (grandTokens > 0) {
                    item {
                        Text(
                            "Всего за эксперимент: $grandTokens токенов, " +
                                "ответы моделей \$${"%.6f".format(grandCost)}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    result: ModelResult,
    streaming: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    result.tier.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                ElapsedLabel(
                    streaming = streaming,
                    startedAt = result.startedAt,
                    finalMs = result.elapsedMs
                )
            }
            Text(
                describeTier(result),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Collapsible chain-of-thought, same as the reasoning screen. While streaming with no
            // answer yet, the header pulses ("Размышляю…").
            ReasoningSection(
                reasoning = result.reasoning,
                thinking = streaming && result.answer.isBlank()
            )

            if (result.answer.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                MarkdownText(result.answer)
                if (streaming) BlinkingCaret()
            }

            result.usage?.let {
                Spacer(Modifier.size(8.dp))
                TokenLabel(it)
                CostLabel(result)
            }
        }
    }
}
