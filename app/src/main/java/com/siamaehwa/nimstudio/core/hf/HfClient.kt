package com.siamaehwa.nimstudio.core.hf

import android.util.Base64
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

class HfApiException(
    val status: Int,
    val friendly: String,
) : Exception("HTTP $status")

object HfErrors {

    private val tokenPattern = Regex("hf_[A-Za-z0-9]{8,}")

    fun friendlyFor(status: Int, detail: String?): String {
        val safe = sanitize(detail)
        val suffix = safe.take(200).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        return when {
            status == 401 ->
                "Hugging Face 토큰이 유효하지 않거나 권한이 없습니다(401). " +
                    "설정에서 토큰을 다시 확인하세요.$suffix"

            status == 402 ->
                "결제/크레딧 오류(402): Hugging Face 무료 계정에는 월 크레딧이 없습니다. " +
                    "PRO($2/월)로 업그레이드하거나 크레딧을 구매해야 Inference Providers를 " +
                    "사용할 수 있습니다.$suffix"

            status == 403 ->
                "권한 없음(403): Gated 모델 동의 여부와 토큰의 " +
                    "'Make calls to Inference Providers' 권한을 확인하세요.$suffix"

            status == 404 ->
                "모델 또는 엔드포인트를 찾을 수 없습니다(404). " +
                    "모델 ID·Provider·URL을 확인하세요.$suffix"

            status == 422 ->
                "요청 형식 오류(422): 모델이 파라미터를 거부했습니다.$suffix"

            status == 429 ->
                "요청 한도 초과(429): 잠시 후 다시 시도하세요. " +
                    "무료 크레딧 소진 시에도 이 오류가 납니다.$suffix"

            status in 500..599 ->
                "Hugging Face 서버 오류($status): 잠시 후 다시 시도하세요.$suffix"

            else -> "Hugging Face 요청 실패(HTTP $status)$suffix"
        }
    }

    fun friendlyFor(t: Throwable): String = when (t) {
        is HfApiException -> t.friendly
        is SocketTimeoutException ->
            "HF 응답이 오랫동안 없어 연결을 끊었습니다. 잠시 후 다시 시도하세요."

        is UnknownHostException -> "네트워크를 찾을 수 없습니다. 인터넷 연결을 확인하세요."
        is IOException -> "네트워크 오류: ${t.message ?: "연결에 실패했습니다"}"
        else -> t.message ?: t.javaClass.simpleName
    }

    fun sanitize(detail: String?): String = detail
        .orEmpty()
        .replace(tokenPattern, "hf_***")
        .trim()
}

@Serializable
private data class HfChunk(
    val choices: List<HfChoice> = emptyList(),
    val usage: HfUsage? = null,
)

@Serializable
private data class HfChoice(
    val delta: HfDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class HfDelta(val content: String? = null)

@Serializable
private data class HfUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)

@Serializable
private data class HfNativeChunk(
    val token: HfNativeToken? = null,
    @SerialName("generated_text") val generatedText: String? = null,
    val error: String? = null,
)

@Serializable
private data class HfNativeToken(val text: String? = null, val special: Boolean = false)

@Serializable
private data class HfFalQueue(
    @SerialName("request_id") val requestId: String? = null,
    val status: String? = null,
    @SerialName("response_url") val responseUrl: String? = null,
)

@Serializable
private data class HfFalStatus(val status: String? = null)

@Serializable
private data class HfFalResult(val images: List<HfFalImage> = emptyList())

@Serializable
private data class HfFalImage(val url: String? = null)

@Serializable
private data class HfWhoAmI(
    val name: String? = null,
    val user: String? = null,
)

class HfClient {

    companion object {
        const val HUB_BASE = "https://huggingface.co"
        const val ROUTER_BASE = "https://router.huggingface.co"
        private const val MAX_ERROR_BYTES = 64_000L
        private const val MAX_IMAGE_BYTES = 25_000_000L
        private const val MAX_RESPONSE_BYTES = 2_000_000L
        private const val FAL_POLL_TIMEOUT_MS = 240_000L
        private const val FAL_POLL_INTERVAL_MS = 800L
        private val FAL_FAILED_STATES = setOf("FAILED", "ERROR", "CANCELLED")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val hubHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val streamHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private val imageHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun whoAmI(token: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$HUB_BASE/api/whoami-v2")
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/json")
            .get()
            .build()
        hubHttp.newCall(request).execute().use { response ->
            val bodyText = readLimited(response, MAX_RESPONSE_BYTES)
            if (!response.isSuccessful) {
                throw HfApiException(response.code, HfErrors.friendlyFor(response.code, bodyText))
            }
            runCatching { json.decodeFromString(HfWhoAmI.serializer(), bodyText) }
                .getOrNull()
                ?.let { it.name ?: it.user }
                ?: "HF 사용자"
        }
    }

    suspend fun searchModels(
        token: String?,
        query: String,
        limit: Int = 20,
        availableOnly: Boolean = false,
    ): List<HfModelSummary> = withContext(Dispatchers.IO) {
        val builder = "$HUB_BASE/api/models".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
            .addQueryParameter("full", "true")
            .addQueryParameter("sort", "downloads")
            .addQueryParameter("direction", "-1")
            .addQueryParameter("expand[]", "inferenceProviderMapping")
            .addQueryParameter("expand[]", "inference")
        if (availableOnly) builder.addQueryParameter("inference_provider", "all")
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) builder.addQueryParameter("search", trimmed)
        val request = Request.Builder()
            .url(builder.build())
            .header("Accept", "application/json")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .get()
            .build()
        hubHttp.newCall(request).execute().use { response ->
            val bodyText = readLimited(response, MAX_RESPONSE_BYTES)
            if (!response.isSuccessful) {
                throw HfApiException(response.code, HfErrors.friendlyFor(response.code, bodyText))
            }
            HfHub.parseModelList(bodyText)
        }
    }

    suspend fun modelDetails(token: String?, modelId: String): HfModelSummary =
        withContext(Dispatchers.IO) {
            val url = "$HUB_BASE/api/models/${modelId.trim().trimStart('/')}"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("full", "true")
                .addQueryParameter("expand[]", "inferenceProviderMapping")
                .addQueryParameter("expand[]", "inference")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
                .get()
                .build()
            hubHttp.newCall(request).execute().use { response ->
                val bodyText = readLimited(response, MAX_RESPONSE_BYTES)
                if (!response.isSuccessful) {
                    throw HfApiException(
                        response.code,
                        HfErrors.friendlyFor(response.code, bodyText),
                    )
                }
                HfHub.parseModelDetails(bodyText)
                    ?: throw IOException("모델 정보를 해석할 수 없습니다")
            }
        }

    fun chatStream(
        token: String,
        model: String,
        provider: String?,
        messages: List<HfChatMessage>,
        maxTokens: Int = 4096,
    ): Flow<HfChatEvent> = flow {
        val routed = if (provider.isNullOrBlank()) model else "$model:$provider"
        val payload = HfPayloads.chatRequest(routed, messages, maxTokens, stream = true)
        val request = Request.Builder()
            .url("$ROUTER_BASE/v1/chat/completions")
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        val call = streamHttp.newCall(request)
        coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val bodyText = readLimited(response, MAX_ERROR_BYTES)
                throw HfApiException(
                    response.code,
                    HfErrors.friendlyFor(response.code, bodyText),
                )
            }
            val source = response.body?.source() ?: throw IOException("응답 본문이 비어 있습니다")
            var reason: String? = null
            var promptTokens = 0
            var completionTokens = 0
            while (true) {
                coroutineContext.ensureActive()
                val line = source.readUtf8Line() ?: break
                val data = sseDataPayload(line) ?: continue
                if (data == "[DONE]") break
                val chunk = runCatching {
                    json.decodeFromString(HfChunk.serializer(), data)
                }.getOrNull() ?: continue
                chunk.usage?.let {
                    promptTokens = it.promptTokens
                    completionTokens = it.completionTokens
                }
                val choice = chunk.choices.firstOrNull() ?: continue
                choice.delta?.content?.takeIf { it.isNotEmpty() }?.let {
                    emit(HfChatEvent.Content(it))
                }
                choice.finishReason?.let { reason = it }
            }
            emit(HfChatEvent.Done(reason, promptTokens, completionTokens))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateImage(
        token: String,
        model: String,
        provider: String?,
        providerId: String?,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Long?,
        guidance: Double?,
    ): ByteArray = withContext(Dispatchers.IO) {
        val chosen = provider?.takeIf { it.isNotBlank() }
            ?: throw HfApiException(
                400,
                "이미지 생성 Provider를 선택하세요(hf-inference 또는 fal-ai).",
            )
        val fullHf = HfPayloads.imageRequest(
            prompt = prompt,
            negativePrompt = negativePrompt,
            width = width,
            height = height,
            steps = steps,
            seed = seed,
            guidance = guidance,
        )
        val fullFal = HfPayloads.falImageRequest(
            prompt = prompt,
            width = width,
            height = height,
            steps = steps,
            seed = seed,
            guidance = guidance,
        )
        val attempts = when (chosen) {
            "hf-inference" -> listOf(
                "$ROUTER_BASE/hf-inference/models/$model" to
                    listOf(fullHf, HfPayloads.minimalImageRequest(prompt)),
            )

            "fal-ai" -> {
                val alias = providerId?.takeIf { it.isNotBlank() }
                    ?: throw HfApiException(400, "fal-ai 모델 별칭(providerId)이 없습니다.")
                listOf(
                    "$ROUTER_BASE/fal-ai/$alias?_subdomain=queue" to
                        listOf(fullFal, HfPayloads.minimalFalRequest(prompt)),
                )
            }

            else -> throw HfApiException(
                400,
                "이 Provider($chosen)의 이미지 생성은 아직 지원하지 않습니다. " +
                    "hf-inference 또는 fal-ai를 선택하세요.",
            )
        }
        var lastError: HfApiException? = null
        for ((url, payloads) in attempts) {
            for (payload in payloads) {
                try {
                    return@withContext if (chosen == "fal-ai") {
                        postFalImage(url, payload, token)
                    } else {
                        postImage(url, payload, token)
                    }
                } catch (error: HfApiException) {
                    lastError = error
                    if (error.status == 400 || error.status == 422) continue
                    throw error
                }
            }
        }
        throw lastError ?: IOException("이미지 생성 요청에 실패했습니다")
    }

    fun endpointChatStream(
        config: HfEndpointConfig,
        token: String?,
        messages: List<HfChatMessage>,
        maxTokens: Int = 4096,
    ): Flow<HfChatEvent> = flow {
        val url = if (config.apiStyle == HfApiStyle.HfTask) {
            hfNativeUrl(config)
        } else {
            endpointChatUrl(config)
        }
        val payload = if (config.apiStyle == HfApiStyle.HfTask) {
            HfPayloads.nativeChatRequest(messages, maxTokens)
        } else {
            HfPayloads.chatRequest(
                model = config.modelId.ifBlank { "default" },
                messages = messages,
                maxTokens = maxTokens,
                stream = true,
            )
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .post(payload.toRequestBody(jsonMedia))
            .build()
        val call = streamHttp.newCall(request)
        coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val bodyText = readLimited(response, MAX_ERROR_BYTES)
                throw HfApiException(
                    response.code,
                    HfErrors.friendlyFor(response.code, bodyText),
                )
            }
            val source = response.body?.source() ?: throw IOException("응답 본문이 비어 있습니다")
            var reason: String? = null
            var nativeEmitted = false
            while (true) {
                coroutineContext.ensureActive()
                val line = source.readUtf8Line() ?: break
                val data = sseDataPayload(line) ?: continue
                if (data == "[DONE]") break
                if (config.apiStyle == HfApiStyle.HfTask) {
                    val chunk = runCatching {
                        json.decodeFromString(HfNativeChunk.serializer(), data)
                    }.getOrNull() ?: continue
                    chunk.error?.let { throw HfApiException(500, HfErrors.sanitize(it)) }
                    chunk.token?.text?.takeIf { it.isNotEmpty() }?.let {
                        nativeEmitted = true
                        emit(HfChatEvent.Content(it))
                    }
                    chunk.generatedText
                        ?.takeIf { it.isNotEmpty() && !nativeEmitted }
                        ?.let { emit(HfChatEvent.Content(it)) }
                } else {
                    val chunk = runCatching {
                        json.decodeFromString(HfChunk.serializer(), data)
                    }.getOrNull() ?: continue
                    val choice = chunk.choices.firstOrNull() ?: continue
                    choice.delta?.content?.takeIf { it.isNotEmpty() }?.let {
                        emit(HfChatEvent.Content(it))
                    }
                    choice.finishReason?.let { reason = it }
                }
            }
            emit(HfChatEvent.Done(reason))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun endpointGenerateImage(
        config: HfEndpointConfig,
        token: String?,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Long?,
    ): ByteArray = withContext(Dispatchers.IO) {
        val url = config.url.trim().trimEnd('/')
        if (url.isEmpty()) throw HfApiException(400, "Endpoint URL을 입력하세요.")
        val payloads = listOf(
            HfPayloads.imageRequest(
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = width,
                height = height,
                steps = steps,
                seed = seed,
                guidance = null,
            ),
            HfPayloads.minimalImageRequest(prompt),
        )
        var lastError: HfApiException? = null
        for (payload in payloads) {
            try {
                return@withContext postImage(url, payload, token)
            } catch (error: HfApiException) {
                lastError = error
                if (error.status == 400 || error.status == 422) continue
                throw error
            }
        }
        throw lastError ?: IOException("이미지 생성 요청에 실패했습니다")
    }

    private fun postImage(url: String, payload: String, token: String?): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/png")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .post(payload.toRequestBody(jsonMedia))
            .build()
        imageHttp.newCall(request).execute().use { response ->
            val bytes = readBytesLimited(response, MAX_IMAGE_BYTES)
            if (!response.isSuccessful) {
                val text = bytes.toString(Charsets.UTF_8)
                throw HfApiException(response.code, HfErrors.friendlyFor(response.code, text))
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.startsWith("image/") || looksLikeImage(bytes)) return bytes
            val text = bytes.toString(Charsets.UTF_8)
            HfHub.extractBase64Image(text)?.let {
                return Base64.decode(it, Base64.DEFAULT)
            }
            throw IOException("이미지 응답을 해석할 수 없습니다: ${text.take(300)}")
        }
    }

    private suspend fun postFalImage(url: String, payload: String, token: String?): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .post(payload.toRequestBody(jsonMedia))
            .build()
        val queueText = imageHttp.newCall(request).execute().use { response ->
            val bytes = readBytesLimited(response, MAX_ERROR_BYTES)
            if (!response.isSuccessful) {
                val text = bytes.toString(Charsets.UTF_8)
                throw HfApiException(response.code, HfErrors.friendlyFor(response.code, text))
            }
            bytes.toString(Charsets.UTF_8)
        }

        runCatching { json.decodeFromString(HfFalResult.serializer(), queueText) }
            .getOrNull()
            ?.images
            ?.firstOrNull()
            ?.url
            ?.let { return downloadImage(it) }

        val queue = runCatching { json.decodeFromString(HfFalQueue.serializer(), queueText) }
            .getOrNull()
            ?: throw IOException("fal-ai 큐 응답을 해석할 수 없습니다: ${queueText.take(300)}")
        queue.requestId ?: throw IOException(
            "fal-ai 요청 ID가 없습니다: ${queueText.take(300)}",
        )

        val responseUrl = queue.responseUrl?.takeIf { it.isNotBlank() }
            ?: url.substringBefore('?')
        val (statusUrl, resultUrl) = falQueueUrls(responseUrl, url)
        var status = queue.status.orEmpty().uppercase()
        val deadline = System.currentTimeMillis() + FAL_POLL_TIMEOUT_MS
        while (status != "COMPLETED") {
            if (status in FAL_FAILED_STATES) {
                throw HfApiException(500, "fal-ai 이미지 생성 실패($status)")
            }
            if (System.currentTimeMillis() >= deadline) {
                throw IOException("fal-ai 이미지 생성 시간 초과(4분)")
            }
            delay(FAL_POLL_INTERVAL_MS)
            coroutineContext.ensureActive()
            val statusText = falGet(statusUrl, token)
            status = runCatching {
                json.decodeFromString(HfFalStatus.serializer(), statusText).status
            }.getOrNull().orEmpty().uppercase()
        }

        val resultText = falGet(resultUrl, token)
        val result = runCatching {
            json.decodeFromString(HfFalResult.serializer(), resultText)
        }.getOrNull() ?: throw IOException("fal-ai 결과를 해석할 수 없습니다: ${resultText.take(300)}")
        val imageUrl = result.images.firstOrNull()?.url?.takeIf { it.isNotBlank() }
            ?: throw IOException("fal-ai 결과에 이미지가 없습니다: ${resultText.take(300)}")
        return downloadImage(imageUrl)
    }

    private fun falGet(url: String, token: String?): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .get()
            .build()
        imageHttp.newCall(request).execute().use { response ->
            val bytes = readBytesLimited(response, MAX_ERROR_BYTES)
            if (!response.isSuccessful) {
                val text = bytes.toString(Charsets.UTF_8)
                throw HfApiException(response.code, HfErrors.friendlyFor(response.code, text))
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private fun downloadImage(imageUrl: String): ByteArray {
        val request = Request.Builder()
            .url(imageUrl)
            .header("Accept", "image/*")
            .get()
            .build()
        imageHttp.newCall(request).execute().use { response ->
            val bytes = readBytesLimited(response, MAX_IMAGE_BYTES)
            if (!response.isSuccessful) {
                val text = bytes.toString(Charsets.UTF_8)
                throw HfApiException(response.code, HfErrors.friendlyFor(response.code, text))
            }
            if (bytes.isEmpty()) throw IOException("빈 이미지 응답을 받았습니다")
            return bytes
        }
    }

    suspend fun testEndpointChat(config: HfEndpointConfig, token: String?): String =
        withContext(Dispatchers.IO) {
            val result = StringBuilder()
            withTimeout(60_000) {
                endpointChatStream(
                    config = config,
                    token = token,
                    messages = listOf(HfChatMessage("user", "Reply with exactly: OK")),
                    maxTokens = 16,
                ).collect { event ->
                    when (event) {
                        is HfChatEvent.Content -> result.append(event.text)
                        is HfChatEvent.Done, is HfChatEvent.Notice -> Unit
                    }
                }
            }
            val text = result.toString().trim()
            if (text.isEmpty()) {
                "Endpoint에 연결되었습니다. (빈 응답)"
            } else {
                "응답 확인: ${text.take(160)}"
            }
        }

    private fun hfNativeUrl(config: HfEndpointConfig): String {
        val base = config.url.trim().trimEnd('/')
        if (base.isEmpty()) throw HfApiException(400, "Endpoint URL을 입력하세요.")
        return when {
            base.endsWith("/generate_stream") || base.endsWith("/generate") -> base
            else -> "$base/generate_stream"
        }
    }

    private fun endpointChatUrl(config: HfEndpointConfig): String {
        val base = config.url.trim().trimEnd('/')
        if (base.isEmpty()) throw HfApiException(400, "Endpoint URL을 입력하세요.")
        return when {
            base.endsWith("/v1/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private fun readLimited(
        response: okhttp3.Response,
        limit: Long,
    ): String {
        val body = response.body
        val length = body?.contentLength() ?: -1L
        if (length > limit) throw IOException("응답이 너무 큽니다")
        return body?.source()?.let { source ->
            val buffer = okio.Buffer()
            val read = source.read(buffer, limit)
            if (read < 0) "" else buffer.readUtf8()
        }.orEmpty()
    }

    private fun readBytesLimited(
        response: okhttp3.Response,
        limit: Long,
    ): ByteArray {
        val body = response.body ?: return ByteArray(0)
        val length = body.contentLength()
        if (length > limit) throw IOException("응답이 너무 큽니다")
        val buffer = Buffer()
        val read = body.source().read(buffer, limit + 1)
        if (read > limit) throw IOException("응답이 너무 큽니다")
        return if (read < 0) ByteArray(0) else buffer.readByteArray()
    }

    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        val gif = bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        val webp = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        return png || jpeg || gif || webp
    }
}

internal fun falQueueUrls(responseUrl: String, requestUrl: String): Pair<String, String> {
    val request = URI(requestUrl)
    val response = URI(responseUrl)
    val base = buildString {
        append(request.scheme ?: "https")
        append("://")
        append(request.rawAuthority ?: "router.huggingface.co")
        if (request.host == "router.huggingface.co") append("/fal-ai")
    }
    val query = request.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
    val path = response.rawPath.orEmpty()
    return "$base$path/status$query" to "$base$path$query"
}
