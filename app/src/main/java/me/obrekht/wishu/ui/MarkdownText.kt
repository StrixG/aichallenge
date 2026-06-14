package me.obrekht.wishu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Minimal, dependency-free Markdown renderer for assistant replies. Handles the subset the chat
 * actually produces — block level: `#`/`##`/`###` headers and `-`/`*`/`•` bullet lines; inline:
 * `**bold**`, `*italic*`/`_italic_`, `` `code` ``. Not a full CommonMark parser (no tables, links,
 * code fences, or nested lists); unknown syntax falls through as plain text.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    // SpanStyle.background isn't @Composable, so resolve the theme color once up here.
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier) {
        text.split("\n").forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(4.dp))

                line.startsWith("### ") -> Text(
                    parseInline(line.removePrefix("### "), codeBackground),
                    style = MaterialTheme.typography.titleSmall
                )
                line.startsWith("## ") -> Text(
                    parseInline(line.removePrefix("## "), codeBackground),
                    style = MaterialTheme.typography.titleMedium
                )
                line.startsWith("# ") -> Text(
                    parseInline(line.removePrefix("# "), codeBackground),
                    style = MaterialTheme.typography.titleLarge
                )

                isBullet(line) -> Row {
                    Text("•  ", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        parseInline(line.drop(2).trimStart(), codeBackground),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                else -> Text(parseInline(line, codeBackground), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun isBullet(line: String): Boolean =
    line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")

// Single-pass inline scanner. Markers don't nest (bold/italic/code content is plain); an unclosed
// marker is emitted verbatim so stray `*` or `` ` `` never eats the rest of the line.
private fun parseInline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '*' && text.startsWith("**", i) -> {
                val close = text.indexOf("**", i + 2)
                if (close >= 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, close))
                    }
                    i = close + 2
                } else {
                    append(c); i++
                }
            }

            c == '*' || c == '_' -> {
                val close = text.indexOf(c, i + 1)
                if (close > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, close))
                    }
                    i = close + 1
                } else {
                    append(c); i++
                }
            }

            c == '`' -> {
                val close = text.indexOf('`', i + 1)
                if (close >= 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                        append(text.substring(i + 1, close))
                    }
                    i = close + 1
                } else {
                    append(c); i++
                }
            }

            else -> {
                append(c); i++
            }
        }
    }
}
