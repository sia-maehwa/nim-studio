package com.siamaehwa.nimstudio.core

import android.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface NimStreamEvent {
    data class Content(val text: String) : NimStreamEvent
    data class Reasoning(val text: String) : NimStreamEvent
    data class Notice(val text: String) : NimStreamEvent
    data class Finished(val reason: String?, val usage: TokenUsage? = null) : NimStreamEvent
}

data class ChatTurn(
    val role: String,
    val content: String,
    val parts: JsonElement? = null,
)

data class ImageGenParams(
    val model: String,
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 1024,
    val height: Int = 1024,
    val steps: Int = 30,
    val seed: Long? = null,
)

data class GeneratedImage(
    val bytes: ByteArray,
)

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

sealed interface AgentStreamEvent {
    data class Content(val text: String) : AgentStreamEvent
    data class Reasoning(val text: String) : AgentStreamEvent
    data class Notice(val text: String) : AgentStreamEvent
    data class Done(
        val toolCalls: List<AgentToolCall>,
        val finishReason: String?,
        val usage: TokenUsage? = null,
    ) : AgentStreamEvent
}

class NimClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    private val streamHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchModels(apiKey: String, baseUrl: String): List<NimModel> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(normalizeBase(baseUrl) + "/models")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw apiException(response.code, bodyText)
                json.decodeFromString(ModelsResponse.serializer(), bodyText).data
                    .filter { it.id.isNotBlank() }
                    .sortedBy { it.id }
            }
        }

    suspend fun testChat(apiKey: String, baseUrl: String, model: String): Pair<String, Long> =
        withContext(Dispatchers.IO) {
            val payload = ChatRequest(
                model = model,
                messages = listOf(ChatMessage("user", textContent("Reply with exactly: OK"))),
                temperature = 0.0,
                topP = 1.0,
                maxTokens = 16,
                stream = false,
            )
            val request = Request.Builder()
                .url(normalizeBase(baseUrl) + "/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .post(json.encodeToString(ChatRequest.serializer(), payload).toRequestBody(jsonMedia))
                .build()
            val started = System.nanoTime()
            http.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                if (!response.isSuccessful) throw apiException(response.code, bodyText)
                val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), bodyText)
                val content = parsed.choices.firstOrNull()?.message?.content.asMessageText()
                content to elapsedMs
            }
        }

    fun streamChat(
        apiKey: String,
        baseUrl: String,
        model: String,
        turns: List<ChatTurn>,
        temperature: Double = 0.7,
        maxTokens: Int = 4096,
        reasoningEffort: String? = null,
    ): Flow<NimStreamEvent> = flow {
        var emitted = false
        var lastError: Exception? = null
        var finished = false
        var fellBack = false
        for (effort in effortAttempts(reasoningEffort)) {
            if (finished) break
            for (tokens in tokenLadder(maxTokens)) {
                try {
                    streamChatOnce(
                        apiKey, baseUrl, model, turns, temperature, tokens, effort,
                    ) { event ->
                        emitted = true
                        emit(event)
                    }
                    finished = true
                    lastError = null
                    break
                } catch (c: CancellationException) {
                    throw c
                } catch (e: NimApiException) {
                    if (e.status !in RETRYABLE_STATUSES || emitted) throw e
                    lastError = e
                    if (effort != null && !fellBack) {
                        fellBack = true
                        emit(
                            NimStreamEvent.Notice(
                                "이 모델이 추론 강도를 지원하지 않아 기본값으로 다시 시도합니다",
                            ),
                        )
                        break
                    }
                }
            }
        }
        if (!finished) lastError?.let { throw it }
    }.flowOn(Dispatchers.IO)

    private fun effortAttempts(effort: String?): List<String?> =
        if (effort == null) listOf(null) else listOf(effort, null)

    private suspend fun streamChatOnce(
        apiKey: String,
        baseUrl: String,
        model: String,
        turns: List<ChatTurn>,
        temperature: Double,
        maxTokens: Int,
        reasoningEffort: String?,
        onEvent: suspend (NimStreamEvent) -> Unit,
    ) {
        val payload = ChatRequest(
            model = model,
            messages = turns.map {
                ChatMessage(it.role, it.parts ?: textContent(it.content))
            },
            temperature = temperature,
            topP = 0.95,
            maxTokens = maxTokens,
            stream = true,
            reasoningEffort = reasoningEffort,
            streamOptions = StreamOptions(includeUsage = true),
        )
        val request = Request.Builder()
            .url(normalizeBase(baseUrl) + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")
            .post(json.encodeToString(ChatRequest.serializer(), payload).toRequestBody(jsonMedia))
            .build()

        val call = streamHttp.newCall(request)
        coroutineContext[Job]?.invokeOnCompletion { call.cancel() }

        call.execute().use { response ->
            if (!response.isSuccessful) {
                val bodyText = response.body?.string().orEmpty()
                throw apiException(response.code, bodyText)
            }
            val source = response.body?.source() ?: throw IOException("응답 본문이 비어 있습니다")
            var finishReason: String? = null
            var usage: TokenUsage? = null
            var sawDone = false
            while (true) {
                coroutineContext.ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payloadLine = line.removePrefix("data:").trim()
                if (payloadLine.isEmpty()) continue
                if (payloadLine == "[DONE]") {
                    sawDone = true
                    break
                }
                val chunk = runCatching {
                    json.decodeFromString(ChatChunk.serializer(), payloadLine)
                }.getOrNull() ?: continue
                chunk.usage?.let { usage = it }
                val choice = chunk.choices.firstOrNull() ?: continue
                val delta = choice.delta
                if (delta != null) {
                    val content = delta.content
                    if (!content.isNullOrEmpty()) onEvent(NimStreamEvent.Content(content))
                    val reasoning = delta.reasoningContent ?: delta.reasoning
                    if (!reasoning.isNullOrEmpty()) onEvent(NimStreamEvent.Reasoning(reasoning))
                }
                choice.finishReason?.let { finishReason = normalizeFinishReason(it) }
            }
            onEvent(NimStreamEvent.Finished(finishReason ?: if (sawDone) "stop" else null, usage))
        }
    }

    suspend fun generateImages(
        apiKey: String,
        baseUrl: String,
        params: ImageGenParams,
    ): List<GeneratedImage> = withContext(Dispatchers.IO) {
        val spec = ImageCatalog.spec(params.model)
        var lastError: Exception? = null
        for (attempt in imageAttempts(baseUrl, params, spec)) {
            val request = Request.Builder()
                .url(attempt.url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .post(attempt.payload.toRequestBody(jsonMedia))
                .build()
            val call = http.newCall(request)
            coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw apiException(response.code, bodyText)
                    return@withContext parseImageResponse(bodyText)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Exception) {
                lastError = t
                val retryable = t is NimApiException &&
                    t.status in setOf(400, 404, 405, 422)
                if (!retryable) throw t
            }
        }
        throw lastError ?: IOException("이미지 생성 요청에 실패했습니다")
    }

    private data class ImageAttempt(val url: String, val payload: String)

    private fun imageAttempts(
        baseUrl: String,
        params: ImageGenParams,
        spec: ImageModelSpec?,
    ): List<ImageAttempt> {
        val base = normalizeBase(baseUrl)
        val ladder = genaiPayloadLadder(params, spec)
        if (base.contains("api.nvidia.com")) {
            val url = "https://ai.api.nvidia.com/v1/genai/${params.model}"
            return ladder.map { ImageAttempt(url, it) }
        }
        return listOf(
            ImageAttempt("$base/images/generations", openAiImagesPayload(params)),
        ) + ladder.flatMap { payload ->
            listOf(
                ImageAttempt("$base/genai/${params.model}", payload),
                ImageAttempt("$base/infer", payload),
            )
        }
    }

    private fun genaiPayloadLadder(
        params: ImageGenParams,
        spec: ImageModelSpec?,
    ): List<String> {
        val family = spec?.family ?: ImageFamily.Flux
        return when (family) {
            ImageFamily.Sdxl -> listOf(
                genaiSdxl(params, spec, minimal = false),
                genaiSdxl(params, spec, minimal = true),
            )

            ImageFamily.Sd3 -> listOf(
                genaiSd3(params, spec, includeNegative = true, includeCfg = true),
                genaiSd3(params, spec, includeNegative = false, includeCfg = true),
                genaiSd3(params, spec, includeNegative = false, includeCfg = false),
            )

            ImageFamily.Klein -> listOf(
                genaiKlein(params, spec),
                genaiMinimal(params),
            )

            ImageFamily.Flux -> listOf(
                genaiFlux(params, spec, includeCfg = true, includeMode = true),
                genaiFlux(params, spec, includeCfg = false, includeMode = true),
                genaiFlux(params, spec, includeCfg = false, includeMode = false),
            )
        }
    }

    private fun genaiSdxl(params: ImageGenParams, spec: ImageModelSpec?, minimal: Boolean): String =
        buildJsonObject {
            putJsonArray("text_prompts") {
                add(
                    buildJsonObject {
                        put("text", params.prompt)
                        put("weight", 1)
                    },
                )
                if (!minimal && params.negativePrompt.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("text", params.negativePrompt)
                            put("weight", -1)
                        },
                    )
                }
            }
            if (!minimal) {
                put("cfg_scale", spec?.cfgScale ?: 7.5)
                put("seed", params.seed ?: 0)
                put("steps", params.steps)
            }
            put("width", params.width)
            put("height", params.height)
        }.toString()

    private fun genaiSd3(
        params: ImageGenParams,
        spec: ImageModelSpec?,
        includeNegative: Boolean,
        includeCfg: Boolean,
    ): String = buildJsonObject {
        put("prompt", params.prompt)
        if (includeNegative && params.negativePrompt.isNotBlank()) {
            put("negative_prompt", params.negativePrompt)
        }
        if (includeCfg) {
            put("cfg_scale", spec?.cfgScale ?: 3.5)
            put("seed", params.seed ?: 0)
            put("steps", params.steps)
        }
        put("aspect_ratio", aspectRatio(params.width, params.height))
    }.toString()

    private fun genaiKlein(params: ImageGenParams, spec: ImageModelSpec?): String =
        buildJsonObject {
            put("prompt", params.prompt)
            put("width", params.width)
            put("height", params.height)
            put("seed", params.seed ?: 0)
            put("steps", spec?.fixedSteps ?: params.steps)
        }.toString()

    private fun genaiFlux(
        params: ImageGenParams,
        spec: ImageModelSpec?,
        includeCfg: Boolean,
        includeMode: Boolean,
    ): String = buildJsonObject {
        put("prompt", params.prompt)
        if (includeMode) put("mode", "base")
        put("width", params.width)
        put("height", params.height)
        put("seed", params.seed ?: 0)
        put("steps", spec?.fixedSteps ?: params.steps.coerceIn(5, 50))
        if (includeCfg) spec?.cfgScale?.let { put("cfg_scale", it) }
    }.toString()

    private fun genaiMinimal(params: ImageGenParams): String = buildJsonObject {
        put("prompt", params.prompt)
        put("width", params.width)
        put("height", params.height)
    }.toString()

    private fun openAiImagesPayload(params: ImageGenParams): String = buildJsonObject {
        put("model", params.model)
        put("prompt", params.prompt)
        put("n", 1)
        put("response_format", "b64_json")
        put("size", "${params.width}x${params.height}")
        put("steps", params.steps)
        if (params.seed != null) put("seed", params.seed)
    }.toString()

    private fun genaiPayload(params: ImageGenParams, spec: ImageModelSpec?): String {
        val family = spec?.family ?: ImageFamily.Flux
        return buildJsonObject {
            when (family) {
                ImageFamily.Sdxl -> {
                    putJsonArray("text_prompts") {
                        add(
                            buildJsonObject {
                                put("text", params.prompt)
                                put("weight", 1)
                            },
                        )
                        if (params.negativePrompt.isNotBlank()) {
                            add(
                                buildJsonObject {
                                    put("text", params.negativePrompt)
                                    put("weight", -1)
                                },
                            )
                        }
                    }
                    put("cfg_scale", spec?.cfgScale ?: 7.5)
                    put("width", params.width)
                    put("height", params.height)
                    put("seed", params.seed ?: 0)
                    put("steps", params.steps)
                }

                ImageFamily.Sd3 -> {
                    put("prompt", params.prompt)
                    if (params.negativePrompt.isNotBlank()) {
                        put("negative_prompt", params.negativePrompt)
                    }
                    put("cfg_scale", spec?.cfgScale ?: 3.5)
                    put("aspect_ratio", aspectRatio(params.width, params.height))
                    put("seed", params.seed ?: 0)
                    put("steps", params.steps)
                }

                ImageFamily.Klein -> {
                    put("prompt", params.prompt)
                    put("width", params.width)
                    put("height", params.height)
                    put("seed", params.seed ?: 0)
                    put("steps", spec?.fixedSteps ?: params.steps)
                }

                ImageFamily.Flux -> {
                    put("prompt", params.prompt)
                    put("mode", "base")
                    put("width", params.width)
                    put("height", params.height)
                    put("seed", params.seed ?: 0)
                    put("steps", spec?.fixedSteps ?: params.steps.coerceIn(5, 50))
                    spec?.cfgScale?.let { put("cfg_scale", it) }
                }
            }
        }.toString()
    }

    private fun aspectRatio(width: Int, height: Int): String {
        val ratio = width.toDouble() / height.toDouble()
        return when {
            ratio >= 1.6 -> "16:9"
            ratio >= 1.2 -> "3:2"
            ratio >= 1.05 -> "5:4"
            ratio >= 0.95 -> "1:1"
            ratio >= 0.83 -> "4:5"
            ratio >= 0.62 -> "2:3"
            else -> "9:16"
        }
    }

    private fun parseImageResponse(bodyText: String): List<GeneratedImage> {
        val root = runCatching { json.parseToJsonElement(bodyText) as JsonObject }
            .getOrElse { throw IOException("이미지 응답을 해석할 수 없습니다") }
        val items = (root["data"] as? JsonArray)
            ?: (root["artifacts"] as? JsonArray)
            ?: (root["images"] as? JsonArray)
            ?: JsonArray(emptyList())

        val images = mutableListOf<GeneratedImage>()
        val failures = mutableListOf<String>()
        for (element in items) {
            val obj = element as? JsonObject ?: continue
            val encoded = obj.stringOrNull("b64_json") ?: obj.stringOrNull("base64")
            val raw = obj.stringOrNull("image")
            val url = obj.stringOrNull("url") ?: obj.stringOrNull("image_url")
            val bytes = when {
                encoded != null -> decodeBase64(encoded)
                raw != null && raw.startsWith("http") -> downloadImage(raw)
                raw != null && raw.startsWith("data:") ->
                    decodeBase64(raw.substringAfter(',', ""))
                raw != null -> decodeBase64(raw)
                url != null -> downloadImage(url)
                else -> null
            }
            if (bytes != null && bytes.isNotEmpty()) {
                images.add(GeneratedImage(bytes))
            } else {
                obj.stringOrNull("finishReason")?.let { reason ->
                    if (reason != "SUCCESS") failures.add(reason)
                }
            }
        }
        if (images.isEmpty()) {
            val single = root.stringOrNull("image") ?: root.stringOrNull("b64_json")
            if (single != null) {
                val bytes = if (single.startsWith("http")) {
                    downloadImage(single)
                } else {
                    decodeBase64(single.substringAfter(',', single))
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    return listOf(GeneratedImage(bytes))
                }
            }
            val reason = failures.firstOrNull()
            if (reason != null) {
                throw IOException(
                    if (reason == "CONTENT_FILTERED") {
                        "이미지가 안전 필터에 의해 차단되었습니다. 프롬프트를 수정해 보세요."
                    } else {
                        "이미지 생성 실패: $reason"
                    },
                )
            }
            throw IOException("응답에 이미지 데이터가 없습니다: " + bodyText.take(200))
        }
        return images
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun decodeBase64(value: String): ByteArray? =
        runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull()

    private fun downloadImage(url: String): ByteArray? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/*")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
    }.getOrNull()

    fun streamAgent(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        temperature: Double = 0.2,
        maxTokens: Int = 4096,
        reasoningEffort: String? = null,
    ): Flow<AgentStreamEvent> = flow {
        var emitted = false
        var lastError: Exception? = null
        var finished = false
        var fellBack = false
        for (effort in effortAttempts(reasoningEffort)) {
            if (finished) break
            for (tokens in tokenLadder(maxTokens)) {
                try {
                    streamAgentOnce(
                        apiKey, baseUrl, model, messages, tools, temperature, tokens, effort,
                    ) { event ->
                        emitted = true
                        emit(event)
                    }
                    finished = true
                    lastError = null
                    break
                } catch (c: CancellationException) {
                    throw c
                } catch (e: NimApiException) {
                    if (e.status !in RETRYABLE_STATUSES || emitted) throw e
                    lastError = e
                    if (effort != null && !fellBack) {
                        fellBack = true
                        emit(
                            AgentStreamEvent.Notice(
                                "이 모델이 추론 강도를 지원하지 않아 기본값으로 다시 시도합니다",
                            ),
                        )
                        break
                    }
                }
            }
        }
        if (!finished) lastError?.let { throw it }
    }.flowOn(Dispatchers.IO)

    private suspend fun streamAgentOnce(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        temperature: Double,
        maxTokens: Int,
        reasoningEffort: String?,
        onEvent: suspend (AgentStreamEvent) -> Unit,
    ) {
        val payload = ChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            topP = 0.95,
            maxTokens = maxTokens,
            stream = true,
            tools = tools,
            toolChoice = "auto",
            reasoningEffort = reasoningEffort,
            streamOptions = StreamOptions(includeUsage = true),
        )
        val request = Request.Builder()
            .url(normalizeBase(baseUrl) + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")
            .post(json.encodeToString(ChatRequest.serializer(), payload).toRequestBody(jsonMedia))
            .build()

        val call = streamHttp.newCall(request)
        coroutineContext[Job]?.invokeOnCompletion { call.cancel() }

        val accumulated = LinkedHashMap<Int, MutableAgentCall>()
        var finishReason: String? = null
        var usage: TokenUsage? = null
        var sawDone = false

        call.execute().use { response ->
            if (!response.isSuccessful) {
                val bodyText = response.body?.string().orEmpty()
                throw apiException(response.code, bodyText)
            }
            val source = response.body?.source() ?: throw IOException("응답 본문이 비어 있습니다")
            while (true) {
                coroutineContext.ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payloadLine = line.removePrefix("data:").trim()
                if (payloadLine.isEmpty()) continue
                if (payloadLine == "[DONE]") {
                    sawDone = true
                    break
                }
                val chunk = runCatching {
                    json.decodeFromString(ChatChunk.serializer(), payloadLine)
                }.getOrNull() ?: continue
                chunk.usage?.let { usage = it }
                val choice = chunk.choices.firstOrNull() ?: continue
                val delta = choice.delta
                if (delta != null) {
                    delta.content?.takeIf { it.isNotEmpty() }?.let {
                        onEvent(AgentStreamEvent.Content(it))
                    }
                    val reasoning = delta.reasoningContent ?: delta.reasoning
                    if (!reasoning.isNullOrEmpty()) {
                        onEvent(AgentStreamEvent.Reasoning(reasoning))
                    }
                    delta.toolCalls?.forEach { tc ->
                        val acc = accumulated.getOrPut(tc.index) { MutableAgentCall() }
                        tc.id?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                        tc.function?.name?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                        tc.function?.arguments?.let { acc.arguments.append(it) }
                    }
                }
                choice.finishReason?.let { finishReason = normalizeFinishReason(it) }
            }
        }

        val calls = accumulated.values
            .filter { it.name.isNotBlank() }
            .mapIndexed { index, acc ->
                AgentToolCall(
                    id = acc.id.ifBlank { "call_$index" },
                    name = acc.name,
                    arguments = acc.arguments.toString().ifBlank { "{}" },
                )
            }
        onEvent(AgentStreamEvent.Done(calls, finishReason ?: if (sawDone) "stop" else null, usage))
    }

    private fun tokenLadder(requested: Int): List<Int> =
        listOf(requested, 8192, 4096).filter { it <= requested }.distinct()

    private class MutableAgentCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    private companion object {
        val RETRYABLE_STATUSES = setOf(400, 422)
    }

    private fun normalizeFinishReason(reason: String): String = when (reason) {
        "end_turn", "eos", "completed" -> "stop"
        else -> reason
    }

    private fun normalizeBase(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    private fun apiException(status: Int, bodyText: String): NimApiException {
        val body = runCatching {
            json.decodeFromString(ApiErrorBody.serializer(), bodyText)
        }.getOrNull()
        return NimApiException(
            status = status,
            title = body?.title,
            detail = body?.detail,
            friendly = NimErrors.friendlyFor(status, body?.title, body?.detail),
        )
    }
}
