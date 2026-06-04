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
    val stream: Boolean? = null
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

// Server-sent-events shape for stream=true: each chunk carries an incremental delta.
@Serializable
data class StreamChunk(
    val choices: List<StreamChoice>
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
