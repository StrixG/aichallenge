package me.obrekht.wishu.data

import androidx.compose.runtime.Immutable
import java.io.IOException
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.obrekht.wishu.network.ChatMessage
import me.obrekht.wishu.network.ChatRequest
import me.obrekht.wishu.network.StreamChunk
import me.obrekht.wishu.network.StreamOptions
import me.obrekht.wishu.network.Usage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// One rung of the weak→strong ladder. DeepSeek only exposes two real model IDs and two distinct
// effort levels ("high" / "max" — lower values are remapped to "high"), so the three tiers are built
// by combining BOTH knobs: a cheaper model at high effort vs the pro model at high vs max effort.
@Immutable
data class ModelTier(
    val label: String,
    val model: String,
    val effort: String,
    // USD per 1M tokens (cache-miss input / output) — from DeepSeek pricing, used to price each run.
    val inputPricePerM: Double,
    val outputPricePerM: Double
)

@Immutable
data class ModelResult(
    val tier: ModelTier,
    val answer: String = "",
    val reasoning: String = "",
    val startedAt: Long = 0L,
    val elapsedMs: Long = 0L,
    val usage: Usage? = null
) {
    // Cost of this single run in USD: prompt tokens at input price + completion tokens at output price.
    val costUsd: Double
        get() = usage?.let {
            it.promptTokens / 1_000_000.0 * tier.inputPricePerM +
                it.completionTokens / 1_000_000.0 * tier.outputPricePerM
        } ?: 0.0
}

// Send the SAME prompt up a weak→medium→strong ladder and compare the replies by quality, speed and
// resource use (tokens + cost). Model + reasoning_effort are the ONLY things that change between
// calls — prompt and token budget are held fixed so the tier is the isolated variable. Reuses the
// project's shared streaming OkHttp client (auth header injected by its interceptor).
class ModelsRepository(
    private val httpClient: OkHttpClient
) {
    companion object {
        const val DEFAULT_PROMPT = "Объясни, почему небо голубое, и приведи один бытовой пример того же физического эффекта."

        // flash@high = weak, pro@high = medium, pro@max = strong. Prices: flash 0.14/0.28, pro 0.435/0.87.
        val TIERS = listOf(
            ModelTier("Слабая", "deepseek-v4-flash", "high", 0.14, 0.28),
            ModelTier("Средняя", "deepseek-v4-pro", "high", 0.435, 0.87),
            ModelTier("Сильная", "deepseek-v4-pro", "max", 0.435, 0.87)
        )

        // The verdict call runs on the strong tier so the comparison itself is the best available.
        private val JUDGE = TIERS.last()

        private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val jsonMedia = "application/json".toMediaType()

    // Streamed text plus the token usage reported in the final chunk.
    private data class AskResult(val content: String, val reasoning: String, val usage: Usage?)

    // Streamed completion at an explicit model + effort. content + reasoning stream separately
    // (thinking models put chain-of-thought in `reasoning_content`); onProgress(content, reasoning)
    // fires after every delta. Returns the final (content, reasoning, usage), text trimmed.
    private suspend fun ask(
        model: String,
        effort: String,
        messages: List<ChatMessage>,
        maxTokens: Int = 2000,
        onProgress: (content: String, reasoning: String) -> Unit
    ): AskResult = withContext(Dispatchers.IO) {
        val payload = ChatRequest(
            model = model,
            messages = messages,
            maxTokens = maxTokens,
            reasoningEffort = effort,
            stream = true,
            streamOptions = StreamOptions(includeUsage = true)
        )
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(json.encodeToString(payload).toRequestBody(jsonMedia))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body?.source() ?: throw IOException("empty body")
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var usage: Usage? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val chunk = json.decodeFromString<StreamChunk>(data)
                chunk.usage?.let { usage = it } // final usage-only chunk has empty choices
                val delta = chunk.choices.firstOrNull()?.delta
                delta?.content?.let { content.append(it) }
                delta?.reasoningContent?.let { reasoning.append(it) }
                onProgress(content.toString(), reasoning.toString())
            }
            AskResult(content.toString().trim(), reasoning.toString().trim(), usage)
        }
    }

    // One generation on the given tier. Same prompt every time — only model + effort differ.
    suspend fun generate(
        prompt: String,
        tier: ModelTier,
        onProgress: (String, String) -> Unit
    ): ModelResult {
        var result = AskResult("", "", null)
        val ms = measureTimeMillis {
            result = ask(
                tier.model,
                tier.effort,
                listOf(ChatMessage(role = "user", content = prompt)),
                onProgress = onProgress
            )
        }
        return ModelResult(
            tier = tier,
            answer = result.content,
            reasoning = result.reasoning,
            elapsedMs = ms,
            usage = result.usage
        )
    }

    // Streamed verdict: final text + the judge's chain-of-thought + token usage.
    data class CompareResult(val content: String, val reasoning: String, val usage: Usage?)

    // Final call: feed the three answers back and ask the model to compare them by the exercise's
    // criteria (quality / speed / resource use) and conclude which tier suits which kind of task.
    suspend fun compare(
        prompt: String,
        results: List<ModelResult>,
        onProgress: (content: String, reasoning: String) -> Unit
    ): CompareResult {
        val block = results.joinToString("\n\n") { r ->
            val time = "%.1f с".format(r.elapsedMs / 1000.0)
            val tokens = r.usage?.totalTokens ?: 0
            "### ${r.tier.label} (${r.tier.model}, effort=${r.tier.effort}) — ${time}, ${tokens} токенов\n" +
                r.answer.ifBlank { r.reasoning }
        }
        val result = ask(
            JUDGE.model,
            JUDGE.effort,
            listOf(
                ChatMessage(
                    role = "user",
                    content = "Промпт: $prompt\n\n" +
                        "Ниже три ответа на один и тот же промпт от моделей разной мощности " +
                        "(с указанием времени и числа токенов):\n\n$block\n\n" +
                        "Сравни их по трём критериям: качество ответа, скорость, ресурсоёмкость " +
                        "(токены и стоимость). Затем сделай краткий вывод: для каких задач оправдана " +
                        "слабая, средняя и сильная модель. Ответь кратко и структурированно, обычным " +
                        "текстом со списками. НЕ используй таблицы и Markdown-таблицы."
                )
            ),
            maxTokens = 4000
        ) { c, r -> onProgress(c, r) }
        return CompareResult(result.content, result.reasoning, result.usage)
    }
}
