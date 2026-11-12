package com.siamaehwa.nimstudio.core.hf

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class HfTask(val id: String, val label: String) {
    Chat("conversational", "채팅"),
    TextToImage("text-to-image", "텍스트→이미지"),
    ImageToImage("image-to-image", "이미지 편집"),
    Unknown("", "알 수 없음"),
    ;

    companion object {
        fun fromId(id: String?): HfTask = when (id) {
            "conversational", "text-generation", "image-text-to-text" -> Chat
            "text-to-image" -> TextToImage
            "image-to-image", "image-text-to-image" -> ImageToImage
            else -> Unknown
        }
    }
}

enum class HfAvailability(val label: String) {
    Ready("실행 가능"),
    NoProvider("Provider 미지원"),
    AuthRequired("인증 필요"),
    Gated("Gated 모델"),
    NeedsDeployment("배포 필요"),
    Unknown("확인되지 않음"),
}

enum class HfApiStyle(val id: String, val label: String) {
    OpenAi("openai", "OpenAI 호환"),
    HfTask("hf", "HF 추론(TGI/TEI)"),
    ;

    companion object {
        fun fromId(id: String?): HfApiStyle =
            entries.firstOrNull { it.id == id } ?: OpenAi
    }
}

data class HfProviderEntry(
    val provider: String,
    val providerId: String?,
    val status: String,
    val task: String?,
    val supportsTools: Boolean = false,
)

data class HfModelSummary(
    val id: String,
    val pipelineTag: String? = null,
    val gated: String? = null,
    val private: Boolean = false,
    val downloads: Long = 0,
    val likes: Int = 0,
    val tags: List<String> = emptyList(),
    val providers: List<HfProviderEntry> = emptyList(),
    val inference: String? = null,
    val lookupFailed: Boolean = false,
) {
    val task: HfTask
        get() {
            val providerTask = providers.firstOrNull { HfTask.fromId(it.task) != HfTask.Unknown }
                ?.task
            if (providerTask != null) return HfTask.fromId(providerTask)
            return HfTask.fromId(pipelineTag)
        }

    val author: String get() = id.substringBefore('/', "")

    val license: String?
        get() = tags.firstOrNull { it.startsWith("license:") }
            ?.removePrefix("license:")
            ?.takeIf { it.isNotBlank() }

    fun availabilityFor(task: HfTask, tokenPresent: Boolean): HfAvailability {
        if (lookupFailed) return HfAvailability.Unknown
        if (!tokenPresent && gated != null) return HfAvailability.Gated
        if (!tokenPresent && private) return HfAvailability.AuthRequired
        val live = providers.filter {
            it.status == "live" && HfTask.fromId(it.task) == task
        }
        if (live.isNotEmpty()) return HfAvailability.Ready
        if (providers.any { HfTask.fromId(it.task) == task }) {
            return HfAvailability.NoProvider
        }
        if (providers.isNotEmpty()) return HfAvailability.NoProvider
        return HfAvailability.NeedsDeployment
    }

    fun liveProviders(task: HfTask): List<HfProviderEntry> = providers.filter {
        it.status == "live" && HfTask.fromId(it.task) == task
    }
}

@Serializable
data class HfSavedModel(
    val id: String,
    val taskId: String = HfTask.Chat.id,
    val provider: String? = null,
    val providerId: String? = null,
    val addedAt: Long = 0,
    val lastTest: String? = null,
    val lastError: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class HfEndpointConfig(
    val url: String = "",
    val modelId: String = "",
    val taskId: String = HfTask.Chat.id,
    val apiStyleId: String = HfApiStyle.OpenAi.id,
) {
    val task: HfTask get() = HfTask.fromId(taskId)
    val apiStyle: HfApiStyle get() = HfApiStyle.fromId(apiStyleId)
    val configured: Boolean get() = url.isNotBlank()
}

@Serializable
data class HfChatMessage(val role: String, val content: String)

sealed interface HfChatEvent {
    data class Content(val text: String) : HfChatEvent
    data class Notice(val text: String) : HfChatEvent
    data class Done(
        val reason: String?,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
    ) : HfChatEvent
}

object HfHub {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun parseModelList(body: String): List<HfModelSummary> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val array = root as? JsonArray ?: return emptyList()
        return array.mapNotNull { parseSummary(it as? JsonObject ?: return@mapNotNull null) }
    }

    fun parseModelDetails(body: String): HfModelSummary? {
        val obj = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        return parseSummary(obj)
    }

    private fun parseSummary(obj: JsonObject): HfModelSummary? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        return HfModelSummary(
            id = id,
            pipelineTag = obj["pipeline_tag"]?.jsonPrimitive?.contentOrNull,
            gated = parseGated(obj["gated"]),
            private = obj["private"]?.jsonPrimitive?.booleanOrNull == true,
            downloads = obj["downloads"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
            likes = obj["likes"]?.jsonPrimitive?.intOrNull ?: 0,
            tags = (obj["tags"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty(),
            providers = parseMapping(obj["inferenceProviderMapping"]),
            inference = obj["inference"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun parseGated(element: JsonElement?): String? = when (element) {
        null, is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.contentOrNull?.takeIf { it != "false" }
            element.booleanOrNull == true -> "true"
            else -> null
        }

        else -> null
    }

    private fun parseMapping(element: JsonElement?): List<HfProviderEntry> = when (element) {
        null, is JsonNull -> emptyList()
        is JsonArray -> element.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            parseProvider(obj["provider"]?.jsonPrimitive?.contentOrNull, obj)
        }

        is JsonObject -> element.entries.mapNotNull { (name, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            parseProvider(name, obj)
        }

        else -> emptyList()
    }

    private fun parseProvider(name: String?, obj: JsonObject): HfProviderEntry? {
        if (name.isNullOrBlank()) return null
        val features = obj["features"] as? JsonObject
        return HfProviderEntry(
            provider = name,
            providerId = obj["providerId"]?.jsonPrimitive?.contentOrNull,
            status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            task = obj["task"]?.jsonPrimitive?.contentOrNull,
            supportsTools = features?.get("toolCalling")
                ?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    fun extractBase64Image(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return null
        val candidates = mutableListOf<JsonElement>()
        when (root) {
            is JsonObject -> {
                listOf("image", "images", "output", "outputs", "data", "artifacts").forEach {
                    root[it]?.let(candidates::add)
                }
                root["b64_json"]?.let(candidates::add)
                root["base64"]?.let(candidates::add)
            }

            is JsonArray -> candidates.add(root)
            else -> Unit
        }
        candidates.forEach { candidate ->
            val value = firstString(candidate) ?: return@forEach
            val cleaned = cleanBase64(value)
            if (cleaned != null) return cleaned
        }
        return null
    }

    private fun firstString(element: JsonElement?): String? = when (element) {
        null, is JsonNull -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.firstNotNullOfOrNull { firstString(it) }
        is JsonObject -> {
            listOf("base64", "b64_json", "image", "url").firstNotNullOfOrNull { key ->
                firstString(element[key])
            }
        }

        else -> null
    }

    private fun cleanBase64(value: String): String? {
        var text = value.trim()
        val comma = text.indexOf("base64,")
        if (text.startsWith("data:") && comma >= 0) {
            text = text.substring(comma + "base64,".length)
        }
        if (text.startsWith("http://") || text.startsWith("https://")) return null
        if (text.length < 64) return null
        val allowed = text.all {
            it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '\n' ||
                it == '\r'
        }
        if (!allowed) return null
        return text.filterNot { it == '\n' || it == '\r' }
    }
}

object HfPayloads {

    fun chatRequest(
        model: String,
        messages: List<HfChatMessage>,
        maxTokens: Int,
        stream: Boolean,
    ): String = buildJsonObject {
        put("model", model)
        put("stream", stream)
        put("max_tokens", maxTokens)
        put("temperature", 0.7)
        put("messages", buildJsonArray {
            messages.forEach { message ->
                add(
                    buildJsonObject {
                        put("role", message.role)
                        put("content", message.content)
                    },
                )
            }
        })
    }.toString()

    fun imageRequest(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Long?,
        guidance: Double?,
    ): String = buildJsonObject {
        put("inputs", prompt)
        put("parameters", buildJsonObject {
            if (negativePrompt.isNotBlank()) put("negative_prompt", negativePrompt)
            put("width", width)
            put("height", height)
            put("num_inference_steps", steps)
            seed?.let { put("seed", it) }
            guidance?.let { put("guidance_scale", it) }
        })
    }.toString()

    fun minimalImageRequest(prompt: String): String = buildJsonObject {
        put("inputs", prompt)
    }.toString()

    fun minimalFalRequest(prompt: String): String = buildJsonObject {
        put("prompt", prompt)
    }.toString()

    fun falImageRequest(
        providerId: String,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Long?,
        guidance: Double?,
    ): String = buildJsonObject {
        put("prompt", prompt)
        put("image_size", buildJsonObject {
            put("width", width)
            put("height", height)
        })
        put("num_inference_steps", falTextSteps(providerId, steps))
        if (negativePrompt.isNotBlank() && falSupportsNegative(providerId)) {
            put("negative_prompt", negativePrompt)
        }
        seed?.let { put("seed", it) }
        guidance?.let { put("guidance_scale", it) }
        put("enable_safety_checker", false)
    }.toString()

    fun falEditRequest(
        providerId: String,
        prompt: String,
        negativePrompt: String,
        imageDataUrl: String,
        steps: Int,
        seed: Long?,
        guidance: Double?,
    ): String = buildJsonObject {
        put("prompt", prompt)
        putFalImageInput(providerId, imageDataUrl)
        put("num_inference_steps", falEditSteps(providerId, steps))
        if (negativePrompt.isNotBlank() && providerId.contains("qwen-image-edit")) {
            put("negative_prompt", negativePrompt)
        }
        seed?.let { put("seed", it) }
        guidance?.let { put("guidance_scale", it) }
        put("enable_safety_checker", false)
    }.toString()

    fun minimalFalEditRequest(
        providerId: String,
        prompt: String,
        imageDataUrl: String,
    ): String = buildJsonObject {
        put("prompt", prompt)
        putFalImageInput(providerId, imageDataUrl)
    }.toString()

    fun falTextSteps(providerId: String, steps: Int): Int = when {
        providerId.contains("flux/schnell") -> steps.coerceIn(1, 12)
        providerId.contains("z-image/turbo") -> steps.coerceIn(1, 8)
        else -> steps.coerceIn(1, 50)
    }

    fun falEditSteps(providerId: String, steps: Int): Int = when {
        providerId.contains("flux-2/klein/4b") -> steps.coerceIn(4, 8)
        providerId.contains("flux-kontext") -> steps.coerceIn(10, 50)
        providerId.contains("qwen-image-edit-plus") -> steps.coerceIn(2, 100)
        else -> steps.coerceIn(2, 50)
    }

    fun falSupportsNegative(providerId: String): Boolean =
        providerId.contains("fast-sdxl") ||
            (providerId.contains("qwen-image") && !providerId.contains("edit"))

    fun falEditUsesImageUrls(providerId: String): Boolean =
        providerId.startsWith("fal-ai/flux-2/") ||
            providerId.contains("qwen-image-edit-plus") ||
            providerId.contains("qwen-image-edit-2511")

    private fun JsonObjectBuilder.putFalImageInput(
        providerId: String,
        imageDataUrl: String,
    ) {
        if (falEditUsesImageUrls(providerId)) {
            put("image_urls", buildJsonArray { add(JsonPrimitive(imageDataUrl)) })
        } else {
            put("image_url", imageDataUrl)
        }
    }

    fun nativeChatRequest(
        messages: List<HfChatMessage>,
        maxTokens: Int,
    ): String = buildJsonObject {
        put("inputs", buildNativePrompt(messages))
        put("parameters", buildJsonObject {
            put("max_new_tokens", maxTokens)
            put("return_full_text", false)
        })
        put("stream", true)
    }.toString()

    fun buildNativePrompt(messages: List<HfChatMessage>): String =
        messages.joinToString("\n\n") { "${it.role}: ${it.content}" } + "\nassistant:"
}

fun sseDataPayload(line: String): String? {
    if (!line.startsWith("data:")) return null
    val payload = line.removePrefix("data:").trim()
    return payload.ifEmpty { null }
}
