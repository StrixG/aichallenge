package me.obrekht.wishu.ui

import androidx.compose.foundation.gestures.scrollBy
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
import me.obrekht.wishu.data.TempResult
import me.obrekht.wishu.network.Usage

// What each temperature does, shown under the number so the comparison is self-explanatory.
private fun describeTemperature(temperature: Double): String = when {
    temperature <= 0.0 -> "детерминированно — самый предсказуемый, повторяемый ответ"
    temperature < 1.0 -> "сбалансированно — немного вариативности при связном тексте"
    else -> "креативно — больше фантазии и разнообразия, но риск бессвязности"
}

// Token cost reported by the API: total, broken into prompt + completion.
@Composable
private fun TokenLabel(usage: Usage) {
    Text(
        "Токены: ${usage.totalTokens} (промпт ${usage.promptTokens} + ответ ${usage.completionTokens})",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemperatureScreen(
    onNavigateBack: () -> Unit,
    viewModel: TemperatureViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Total streamed characters: changes on every token, drives the autoscroll effect.
    val contentSignal = uiState.results.sumOf { it.answer.length } +
        (uiState.comparison?.length ?: 0) + uiState.results.size

    // "Near bottom" with a tolerance so a single token's growth still counts as pinned; once the
    // user scrolls further up than the tolerance, autoscroll lets go.
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 400
        }
    }

    // Pin to bottom by scrolling exactly the overshoot below the viewport, not by jumping to the
    // last item with a MAX offset (which clamps differently every frame and fights animateItem,
    // causing the bounce). Per token, scrollBy moves only the few px the new text added — smooth.
    // Only when a brand-new card appears entirely below the fold do we snap to it once.
    LaunchedEffect(contentSignal) {
        if (isNearBottom) {
            val info = listState.layoutInfo
            val lastIndex = info.totalItemsCount - 1
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
            if (lastVisible.index < lastIndex) {
                listState.scrollToItem(lastIndex)
            } else {
                val delta = (lastVisible.offset + lastVisible.size) - info.viewportEndOffset
                if (delta > 0) listState.scrollBy(delta.toFloat())
            }
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
                title = { Text("Температура") },
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
                    label = { Text("Один промпт для всех температур") },
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
                    Text(if (uiState.running) "Выполняется…" else "Запустить при 0 / 0.7 / 1.2")
                }
            }

            // The last card is the one currently streaming (until the comparison starts).
            val activeIndex = if (uiState.running && uiState.comparison == null) {
                uiState.results.lastIndex
            } else {
                -1
            }
            itemsIndexed(uiState.results, key = { _, r -> r.temperature }) { index, result ->
                // No animateItem: its placement animation fights the bottom-pin autoscroll while a
                // card is growing token-by-token, producing the jitter at the bottom edge.
                TemperatureCard(result, streaming = index == activeIndex)
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
                            Spacer(Modifier.size(8.dp))
                            MarkdownText(comparison)
                            if (uiState.running) BlinkingCaret()
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
                val grandTotal = uiState.results.sumOf { it.usage?.totalTokens ?: 0 } +
                    (uiState.comparisonUsage?.totalTokens ?: 0)
                if (grandTotal > 0) {
                    item {
                        Text(
                            "Всего за эксперимент: $grandTotal токенов",
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
private fun TemperatureCard(
    result: TempResult,
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
                    "temperature = ${result.temperature}",
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
                describeTemperature(result.temperature),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (result.answer.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                MarkdownText(result.answer)
                if (streaming) BlinkingCaret()
            } else if (streaming) {
                Spacer(Modifier.size(8.dp))
                Text(
                    "Генерирую…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            result.usage?.let {
                Spacer(Modifier.size(8.dp))
                TokenLabel(it)
            }
        }
    }
}
