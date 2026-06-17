package me.obrekht.wishu.agent

/**
 * Minimal TOON (Token-Oriented Object Notation) codec for the flat key-value memory layers.
 *
 * TOON drops JSON's braces, quotes, and commas — for a flat string map it's just `key: value`
 * lines, which costs fewer tokens in the helper prompts than the equivalent JSON object. We only
 * ever serialize flat `Map<String, String>` here (working memory, long-term profile), so this
 * intentionally does NOT implement nesting or the tabular array form of full TOON.
 *
 * The DeepSeek wire format (ChatRequest/ChatResponse/StreamChunk) stays JSON — that's the API
 * contract; TOON is only for the memory facts we hand to / read back from the model.
 */
object Toon {

    /** Encode a flat map as TOON: one `key: value` line each. Empty map -> empty string. */
    fun encode(map: Map<String, String>): String =
        map.entries.joinToString("\n") { (k, v) -> "$k: ${v.replace('\n', ' ')}" }

    /**
     * Decode TOON (or a close-enough `key: value` block) back to a map. Tolerates code fences and
     * surrounding prose: blank lines, fence markers, and lines without a colon are skipped. Splits
     * on the FIRST colon, so values may themselves contain colons (e.g. "3:00"). Returns empty on
     * no parseable lines (caller keeps prior facts).
     */
    fun decode(raw: String): Map<String, String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") }
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .filter { it.first.isNotEmpty() }
            .toMap()
}
