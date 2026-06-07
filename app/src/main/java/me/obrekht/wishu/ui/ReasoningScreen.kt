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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.obrekht.wishu.data.MethodResult
import me.obrekht.wishu.data.ReasoningMethod

private fun ReasoningMethod.label(): String = when (this) {
    ReasoningMethod.DIRECT -> "1. Прямой ответ"
    ReasoningMethod.STEP_BY_STEP -> "2. Пошагово"
    ReasoningMethod.SELF_PROMPT -> "3. Самопромпт"
    ReasoningMethod.EXPERTS -> "4. Группа экспертов"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningScreen(
    onNavigateBack: () -> Unit,
    viewModel: ReasoningViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Total streamed characters: changes on every token, drives the autoscroll effect.
    val contentSignal = uiState.results.sumOf { it.answer.length + (it.intermediate?.length ?: 0) } +
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
                title = { Text("Способы рассуждения") },
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
                    value = uiState.problem,
                    onValueChange = { viewModel.onProblemChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Задача (Fermi)") },
                    enabled = !uiState.running,
                    minLines = 2
                )
            }
            item {
                Button(
                    onClick = { viewModel.runAll() },
                    enabled = !uiState.running && uiState.problem.isNotBlank(),
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
                    Text(if (uiState.running) "Выполняется…" else "Запустить все 4 способа")
                }
            }

            // The last card is the one currently streaming (until the comparison starts).
            val activeIndex = if (uiState.running && uiState.comparison == null) {
                uiState.results.lastIndex
            } else {
                -1
            }
            itemsIndexed(uiState.results, key = { _, r -> r.method.name }) { index, result ->
                MethodCard(result, streaming = index == activeIndex, modifier = Modifier.animateItem())
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
                                "Сравнение",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.size(8.dp))
                            MarkdownText(comparison)
                            if (uiState.running) BlinkingCaret()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MethodCard(
    result: MethodResult,
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
                    result.method.label(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                ElapsedLabel(
                    streaming = streaming,
                    startedAt = result.startedAt,
                    finalMs = result.elapsedMs
                )
            }
            result.intermediate?.let { prompt ->
                Spacer(Modifier.size(8.dp))
                Text(
                    "Сгенерированный промпт:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MarkdownText(prompt, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Chain-of-thought stays collapsed behind a thinking indicator; the answer is the
            // payload. While streaming with no answer yet, the indicator animates ("Размышляю…").
            ReasoningSection(
                reasoning = result.reasoning,
                thinking = streaming && result.answer.isBlank()
            )

            if (result.answer.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                MarkdownText(result.answer)
                if (streaming) BlinkingCaret()
            }
        }
    }
}

// Collapsible chain-of-thought. Closed by default; while the model is still thinking (no answer
// yet) the header pulses with animated dots. Tap to reveal the reasoning text.
@Composable
private fun ReasoningSection(reasoning: String, thinking: Boolean) {
    if (reasoning.isBlank() && !thinking) return
    var expanded by remember { mutableStateOf(false) }
    Spacer(Modifier.size(8.dp))
    Surface(
        onClick = { if (reasoning.isNotBlank()) expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (thinking) {
                    ThinkingLabel()
                } else {
                    Icon(
                        Icons.Rounded.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Рассуждение",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                if (reasoning.isNotBlank()) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AnimatedVisibility(visible = expanded && reasoning.isNotBlank()) {
                Column {
                    Spacer(Modifier.size(6.dp))
                    MarkdownText(reasoning, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// Pulsing brain + cycling dots shown while the model is still reasoning.
@Composable
private fun ThinkingLabel() {
    val transition = rememberInfiniteTransition(label = "think")
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "thinkPulse"
    )
    val step by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "thinkDots"
    )
    Icon(
        Icons.Rounded.Psychology,
        contentDescription = null,
        modifier = Modifier
            .size(18.dp)
            .alpha(pulse),
        tint = MaterialTheme.colorScheme.primary
    )
    Text(
        "Размышляю" + ".".repeat(step.toInt().coerceIn(0, 3)),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

// Elapsed time. While streaming it ticks every frame (smooth) and is the only thing recomposing
// at frame rate — the state read is confined to this composable. When done it shows the final ms.
@Composable
private fun ElapsedLabel(streaming: Boolean, startedAt: Long, finalMs: Long) {
    val ms by produceState(initialValue = finalMs, streaming, startedAt, finalMs) {
        if (streaming && startedAt > 0L) {
            while (true) {
                withFrameMillis { }
                value = System.currentTimeMillis() - startedAt
            }
        } else {
            value = finalMs
        }
    }
    Text(
        "$ms ms",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// Typewriter caret: pulses while text is still streaming in.
@Composable
private fun BlinkingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "caretAlpha"
    )
    Text(
        "▍",
        modifier = Modifier.alpha(alpha),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

// Minimal Markdown renderer: the model replies with **bold**, *italic*, `code`, # headings and
// -/numbered lists. Plain Text() would show the raw symbols, so render the common bits.
@Composable
private fun MarkdownText(
    text: String,
    color: androidx.compose.ui.graphics.Color = LocalContentColor.current
) {
    // Re-split only when the text actually changes (each streamed token currently grows `text`).
    val lines = remember(text) { text.trim().split("\n") }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        lines.forEach { raw ->
            val line = raw.trim()
            when {
                line.isBlank() -> Spacer(Modifier.size(2.dp))

                line.startsWith("#") -> Text(
                    parseInlineCached(line.trimStart('#', ' ')),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = color
                )

                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = color)
                        Text(
                            parseInlineCached(line.drop(2).trim()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = color
                        )
                    }

                else -> Text(
                    parseInlineCached(line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color
                )
            }
        }
    }
}

// During streaming the same finished lines get re-rendered on every token; only the last line
// actually changes. Cache parsed lines so completed ones are O(1) instead of re-parsed each frame.
private val inlineCache = object : LinkedHashMap<String, AnnotatedString>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, AnnotatedString>) = size > 512
}

private fun parseInlineCached(s: String): AnnotatedString =
    inlineCache.getOrPut(s) { parseInline(s) }

// Inline spans: **bold**, *italic* / _italic_, `code`. Unmatched markers fall through as text.
private fun parseInline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                    i = end + 2
                } else { append(s[i]); i++ }
            }
            s[i] == '*' || s[i] == '_' -> {
                val marker = s[i]
                val end = s.indexOf(marker, i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}
