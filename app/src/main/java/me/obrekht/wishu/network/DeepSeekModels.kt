package me.obrekht.wishu.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double = 0.9,
    val stop: List<String>? = null,
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null
)

@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = false
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatResponse(
    val choices: List<Choice>
)

@Serializable
data class Choice(
    val message: ChatMessage
)

// Streaming (SSE) response chunks. The final usage-only chunk can have empty choices
// and carries the exact token counts (requires stream_options.include_usage=true).
@Serializable
data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val usage: Usage? = null
)

// Exact token accounting returned by DeepSeek (OpenAI-compatible).
// promptTokens == cache-hit + cache-miss; hits are billed at a much lower input rate.
@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int = 0,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int = 0
)

// OpenAI-compatible error envelope DeepSeek returns on non-2xx (e.g. 400 context overflow).
@Serializable
data class ApiErrorEnvelope(
    val error: ApiError
)

@Serializable
data class ApiError(
    val message: String = "",
    val type: String = "",
    val code: String = ""
)

@Serializable
data class StreamChoice(
    val delta: Delta
)

@Serializable
data class Delta(
    val content: String? = null
)
