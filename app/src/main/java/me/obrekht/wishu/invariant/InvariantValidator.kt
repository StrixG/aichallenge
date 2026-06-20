package me.obrekht.wishu.invariant

import kotlinx.serialization.json.Json
import me.obrekht.wishu.agent.SUMMARY_MODEL
import me.obrekht.wishu.agent.Toon
import me.obrekht.wishu.network.ChatMessage
import me.obrekht.wishu.network.ChatRequest
import me.obrekht.wishu.network.ChatResponse
import me.obrekht.wishu.network.Thinking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The LLM "проверяла" — a separate checker agent for the SEMANTIC invariant layer. It judges meaning,
 * not keywords, so it catches indirect evasions the deterministic [InvariantChecker] can't express
 * ("something to drink at a party" → alcohol). One cheap non-streaming flash call, modeled on the
 * agent's own `helperCall`: TOON in, TOON out, thinking disabled.
 *
 * Fail-open: any network/parse error yields an empty list, so the deterministic layer remains the hard
 * guarantee and a checker outage never blocks a turn.
 */
class InvariantValidator(private val client: OkHttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Check [text] against the LLM-checked part of [invariants]. Empty == no violation found. */
    fun validate(text: String, invariants: List<Invariant>): List<InvariantResult.Violated> {
        val checked = invariants.filter { it.usesLlm }
        if (checked.isEmpty()) return emptyList()

        val instruction = buildString {
            append("INVARIANTS:\n")
            checked.forEachIndexed { i, inv -> append(i + 1).append(". ").append(inv.rule).append('\n') }
            append("\nTEXT to check:\n").append(text)
        }

        val parsed = runCatching {
            val (raw, _) = call(VALIDATOR_PROMPT, instruction)
            raw?.let { Toon.decode(it) }
        }.getOrNull() ?: return emptyList()

        return checked.mapIndexedNotNull { i, inv ->
            val n = i + 1
            val violated = parsed["v$n"].equals("yes", ignoreCase = true)
            if (!violated) null
            else InvariantResult.Violated(
                inv,
                parsed["r$n"]?.takeIf { it.isNotBlank() && it != "-" } ?: inv.refusalReason
            )
        }
    }

    private fun call(system: String, userInstruction: String): Pair<String?, Unit> {
        val payload = ChatRequest(
            model = SUMMARY_MODEL,
            messages = listOf(
                ChatMessage(role = "system", content = system),
                ChatMessage(role = "user", content = userInstruction)
            ),
            maxTokens = 512,
            stream = false,
            thinking = Thinking(type = "disabled")
        )
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null to Unit
            val body = json.decodeFromString<ChatResponse>(response.body.string())
            return body.choices.firstOrNull()?.message?.content?.trim() to Unit
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.deepseek.com/chat/completions"
        val JSON_MEDIA = "application/json".toMediaType()

        const val VALIDATOR_PROMPT =
            "You are an invariant-compliance checker for a gift-planning assistant. You are given " +
                "numbered INVARIANTS the assistant must never break, and a TEXT to check. For EACH " +
                "invariant, decide whether the TEXT violates it. Judge MEANING, not literal keywords — " +
                "catch indirect or disguised violations (e.g. 'something to sip at a party' implies " +
                "alcohol). Do NOT flag an invariant the text simply doesn't touch. " +
                "Output in TOON (Token-Oriented Object Notation): for each invariant n, two lines — " +
                "'v<n>: yes|no' (yes = violated) and 'r<n>: <short reason, or - if no>'. " +
                "Output ONLY those lines, no prose, no code fences. Example:\n" +
                "v1: yes\nr1: suggests wine, which is alcohol\nv2: no\nr2: -"
    }
}
