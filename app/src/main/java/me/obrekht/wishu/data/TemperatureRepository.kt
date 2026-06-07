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

@Immutable
data class TempResult(
    val temperature: Double,
    val answer: String = "",
    val reasoning: String = "",
    val startedAt: Long = 0L,
    val elapsedMs: Long = 0L,
    val usage: Usage? = null
)

// Send the SAME prompt at three temperatures and compare the replies by
// accuracy, creativity and diversity. `temperature` is the ONLY thing that changes between calls —
// everything else (model, prompt, token budget) is held fixed so the effect is isolated. Lower temp
// = more focused/deterministic; higher = more random/creative. Reuses the project's shared streaming
// OkHttp client (auth header injected by its interceptor).
class TemperatureRepository(
    private val httpClient: OkHttpClient
) {
    companion object {
        const val DEFAULT_PROMPT = "Придумай 5 необычных, неожиданных подарков на день рождения для человека, у которого уже всё есть."
        // The three settings the exercise asks us to compare.
        val TEMPERATURES = listOf(0.0, 0.7, 1.2)
        private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val jsonMedia = "application/json".toMediaType()

    // Streamed completion at an explicit temperature. content + reasoning stream separately (reasoning
    // models put chain-of-thought in `reasoning_content`); onProgress(content, reasoning) fires after
    // every delta. Returns the final (content, reasoning) pair, both trimmed.
    // Streamed text plus the token usage reported in the final chunk.
    private data class AskResult(val content: String, val reasoning: String, val usage: Usage?)

    private suspend fun ask(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int = 2000,
        onProgress: (content: String, reasoning: String) -> Unit
    ): AskResult = withContext(Dispatchers.IO) {
        val payload = ChatRequest(
            model = model,
            messages = messages,
            maxTokens = maxTokens,
            temperature = temperature,
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

    // One generation at the given temperature. Same prompt every time — only `temperature` differs.
    suspend fun generate(
        prompt: String,
        model: String,
        temperature: Double,
        onProgress: (String, String) -> Unit
    ): TempResult {
        var result = AskResult("", "", null)
        val ms = measureTimeMillis {
            result = ask(model, listOf(ChatMessage(role = "user", content = prompt)), temperature, onProgress = onProgress)
        }
        return TempResult(
            temperature,
            answer = result.content,
            reasoning = result.reasoning,
            elapsedMs = ms,
            usage = result.usage
        )
    }

    // Final call: feed the three answers back and ask the model to compare them by the exercise's
    // three criteria (accuracy / creativity / diversity) and conclude which temperature suits which
    // kind of task. Low temp here so the verdict itself is stable.
    suspend fun compare(
        prompt: String,
        results: List<TempResult>,
        model: String,
        onText: (String) -> Unit
    ): Pair<String, Usage?> {
        val block = results.joinToString("\n\n") { r ->
            "### temperature = ${r.temperature}\n${r.answer.ifBlank { r.reasoning }}"
        }
        val result = ask(
            model,
            listOf(
                ChatMessage(
                    role = "user",
                    content = "Промпт: $prompt\n\n" +
                        "Ниже три ответа на один и тот же промпт при разной температуре:\n\n$block\n\n" +
                        "Сравни их по трём критериям: точность, креативность, разнообразие. " +
                        "Затем сделай вывод: для каких задач лучше подходит каждое значение температуры. " +
                        "Ответь кратко и структурированно, обычным текстом со списками. " +
                        "НЕ используй таблицы и Markdown-таблицы."
                )
            ),
            temperature = 0.3,
            maxTokens = 4000
        ) { c, r -> onText(c.ifBlank { r }) }
        return result.content.ifBlank { result.reasoning } to result.usage
    }
}
