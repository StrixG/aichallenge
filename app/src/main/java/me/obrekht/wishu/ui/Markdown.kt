package me.obrekht.wishu.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

// Shared streaming-text bits used by the experiment screens (reasoning, temperature): a tiny
// Markdown renderer, a typewriter caret, and an elapsed-time label that ticks per frame.

// Minimal Markdown renderer: the model replies with **bold**, *italic*, `code`, # headings and
// -/numbered lists. Plain Text() would show the raw symbols, so render the common bits.
@Composable
internal fun MarkdownText(
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

// Typewriter caret: pulses while text is still streaming in.
@Composable
internal fun BlinkingCaret() {
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

// Elapsed time. While streaming it ticks every frame (smooth) and is the only thing recomposing
// at frame rate — the state read is confined to this composable. When done it shows the final ms.
@Composable
internal fun ElapsedLabel(streaming: Boolean, startedAt: Long, finalMs: Long) {
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
