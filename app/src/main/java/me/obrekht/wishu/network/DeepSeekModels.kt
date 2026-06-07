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
    val stream: Boolean? = null,
    // For stream=true: ask the gateway to emit a final usage-only chunk so we can report token cost.
    @SerialName("stream_options") val streamOptions: StreamOptions? = null
)

@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true
)

// Token accounting returned by the API (final chunk when streaming, top-level when not).
@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
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

// Server-sent-events shape for stream=true: each chunk carries an incremental delta. The final
// usage-only chunk (when stream_options.include_usage=true) has empty choices and a populated usage.
@Serializable
data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val usage: Usage? = null
)

@Serializable
data class StreamChoice(
    val delta: Delta
)

@Serializable
data class Delta(
    val content: String? = null,
    // Reasoning models stream chain-of-thought here, separate from the final `content`.
    @SerialName("reasoning_content") val reasoningContent: String? = null
)
