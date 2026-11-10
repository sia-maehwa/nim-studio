package com.siamaehwa.nimstudio.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NimModel(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val created: Long? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
)

@Serializable
data class ModelsResponse(
    val data: List<NimModel> = emptyList(),
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerialName("top_p") val topP: Double = 0.95,
    @SerialName("max_tokens") val maxTokens: Int? = 1024,
    val stream: Boolean = true,
)

@Serializable
data class ChatChunk(
    val choices: List<ChatChunkChoice> = emptyList(),
)

@Serializable
data class ChatChunkChoice(
    val delta: ChatDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatDelta(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatCompletionChoice> = emptyList(),
)

@Serializable
data class ChatCompletionChoice(
    val message: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ApiErrorBody(
    val status: Int? = null,
    val title: String? = null,
    val detail: String? = null,
)
