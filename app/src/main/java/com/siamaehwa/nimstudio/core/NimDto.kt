package com.siamaehwa.nimstudio.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

fun textContent(text: String?): JsonElement? = text?.let(::JsonPrimitive)

fun JsonElement?.asMessageText(): String = when (this) {
    null -> ""
    is JsonPrimitive -> content
    is JsonArray -> joinToString("") { element ->
        val obj = element as? JsonObject ?: return@joinToString ""
        when (obj["type"].primitiveText()) {
            "text" -> obj["text"].primitiveText()
            else -> ""
        }
    }
    else -> ""
}

private fun JsonElement?.primitiveText(): String =
    (this as? JsonPrimitive)?.content.orEmpty()

@Serializable
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: ToolCallFunction = ToolCallFunction(),
)

@Serializable
data class ToolCallFunction(
    val name: String = "",
    val arguments: String = "{}",
)

@Serializable
data class ToolSpec(
    val type: String = "function",
    val function: ToolFunctionSpec,
)

@Serializable
data class ToolFunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerialName("top_p") val topP: Double = 0.95,
    @SerialName("max_tokens") val maxTokens: Int? = 4096,
    val stream: Boolean = true,
    val tools: List<ToolSpec>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)

@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

@Serializable
data class TokenUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class ChatChunk(
    val choices: List<ChatChunkChoice> = emptyList(),
    val usage: TokenUsage? = null,
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
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

@Serializable
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionDelta? = null,
)

@Serializable
data class FunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
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
