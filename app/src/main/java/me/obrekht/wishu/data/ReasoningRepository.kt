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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class ReasoningMethod { DIRECT, STEP_BY_STEP, SELF_PROMPT, EXPERTS }

@Immutable
data class MethodResult(
    val method: ReasoningMethod,
    val intermediate: String? = null, // e.g. the prompt the model generated for itself
    val answer: String,
    val reasoning: String = "",       // chain-of-thought, hidden behind a thinking indicator
    val startedAt: Long = 0L,         // wall-clock start; lets the UI tick elapsed time per frame
    val elapsedMs: Long
)

// Day 3 learning exercise: solve ONE Fermi problem four different ways through the same API,
// then compare which method gave the most accurate result. Reuses the project's shared OkHttp
// client (auth header is injected by its interceptor).
class ReasoningRepository(
    private val httpClient: OkHttpClient
) {
    companion object {
        const val DEFAULT_PROBLEM = "Сколько подарков в среднем человек дарит другим людям за всю свою жизнь?"
        private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val jsonMedia = "application/json".toMediaType()

    // Streamed completion. stream=true keeps tokens flowing (so the gateway never times the
    // connection out) AND surfaces text token-by-token. The model's chain-of-thought arrives in
    // `reasoning_content` and the final answer in `content`; we stream them separately so the UI
    // can hide the reasoning behind a thinking indicator. onProgress(content, reasoning) fires
    // after every delta. Returns the final (content, reasoning) pair, both trimmed.
    // Lower temperature than the wishlist flow: reasoning wants determinism, not creativity.
    private suspend fun ask(
        model: String,
        messages: List<ChatMessage>,
        onProgress: (content: String, reasoning: String) -> Unit
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val payload = ChatRequest(
            model = model,
            messages = messages,
            maxTokens = 10000,
            temperature = 0.3,
            stream = true
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
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val delta = json.decodeFromString<StreamChunk>(data).choices.firstOrNull()?.delta
                delta?.content?.let { content.append(it) }
                delta?.reasoningContent?.let { reasoning.append(it) }
                onProgress(content.toString(), reasoning.toString())
            }
            content.toString().trim() to reasoning.toString().trim()
        }
    }

    // Method 1: bare problem, no extra instruction.
    suspend fun solveDirect(
        problem: String,
        model: String,
        onProgress: (String, String) -> Unit
    ): MethodResult {
        var pair = "" to ""
        val ms = measureTimeMillis {
            pair = ask(model, listOf(ChatMessage(role = "user", content = problem)), onProgress)
        }
        return MethodResult(ReasoningMethod.DIRECT, answer = pair.first, reasoning = pair.second, elapsedMs = ms)
    }

    // Method 2: same problem, but instruct the model to reason step by step.
    suspend fun solveStepByStep(
        problem: String,
        model: String,
        onProgress: (String, String) -> Unit
    ): MethodResult {
        var pair = "" to ""
        val ms = measureTimeMillis {
            pair = ask(
                model,
                listOf(
                    ChatMessage(
                        role = "system",
                        content = "Решай задачу пошагово. Показывай каждый шаг рассуждения и " +
                            "промежуточные оценки, а в конце дай итоговый числовой ответ."
                    ),
                    ChatMessage(role = "user", content = problem)
                ),
                onProgress
            )
        }
        return MethodResult(ReasoningMethod.STEP_BY_STEP, answer = pair.first, reasoning = pair.second, elapsedMs = ms)
    }

    // Method 3: first ask the model to WRITE a good prompt for the task, then send that prompt back.
    suspend fun solveSelfPrompt(
        problem: String,
        model: String,
        onPrompt: (String) -> Unit,
        onProgress: (String, String) -> Unit
    ): MethodResult {
        var generatedPrompt = ""
        var pair = "" to ""
        val ms = measureTimeMillis {
            generatedPrompt = ask(
                model,
                listOf(
                    ChatMessage(
                        role = "user",
                        content = "Составь оптимальный промпт для решения этой задачи. " +
                            "Верни ТОЛЬКО текст промпта, без решения:\n\n$problem"
                    )
                )
            ) { content, _ -> onPrompt(content) }.first
            pair = ask(model, listOf(ChatMessage(role = "user", content = generatedPrompt)), onProgress)
        }
        return MethodResult(
            ReasoningMethod.SELF_PROMPT,
            intermediate = generatedPrompt,
            answer = pair.first,
            reasoning = pair.second,
            elapsedMs = ms
        )
    }

    // Method 4: panel of three experts (analyst / engineer / critic), each gives their own estimate.
    suspend fun solveExperts(
        problem: String,
        model: String,
        onProgress: (String, String) -> Unit
    ): MethodResult {
        var pair = "" to ""
        val ms = measureTimeMillis {
            pair = ask(
                model,
                listOf(
                    ChatMessage(
                        role = "system",
                        content = "Реши задачу силами группы из трёх экспертов. " +
                            "Каждый даёт собственную оценку под своим заголовком:\n" +
                            "АНАЛИТИК — разбивает задачу на факторы и оценивает их;\n" +
                            "ИНЖЕНЕР — считает по формуле с конкретными числами;\n" +
                            "КРИТИК — ищет слабые места в оценках первых двух.\n" +
                            "В конце под заголовком ИТОГ дай согласованный числовой ответ."
                    ),
                    ChatMessage(role = "user", content = problem)
                ),
                onProgress
            )
        }
        return MethodResult(ReasoningMethod.EXPERTS, answer = pair.first, reasoning = pair.second, elapsedMs = ms)
    }

    // 5th call: feed the four answers back and ask the model to compare them.
    suspend fun compare(
        problem: String,
        results: List<MethodResult>,
        model: String,
        onText: (String) -> Unit
    ): String {
        val block = results.joinToString("\n\n") { r ->
            "### ${r.method.name}\n${r.answer.ifBlank { r.reasoning }}"
        }
        val (content, reasoning) = ask(
            model,
            listOf(
                ChatMessage(
                    role = "user",
                    content = "Задача: $problem\n\n" +
                        "Ниже четыре ответа, полученных разными методами:\n\n$block\n\n" +
                        "Сравни их: сильно ли отличаются итоговые оценки и какой метод дал, " +
                        "по-твоему, наиболее точный и обоснованный результат? Ответь кратко."
                )
            )
        ) { c, r -> onText(c.ifBlank { r }) }
        return content.ifBlank { reasoning }
    }
}
