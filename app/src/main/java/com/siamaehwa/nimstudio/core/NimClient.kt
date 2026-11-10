package com.siamaehwa.nimstudio.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface NimStreamEvent {
    data class Content(val text: String) : NimStreamEvent
    data class Reasoning(val text: String) : NimStreamEvent
    data class Finished(val reason: String?) : NimStreamEvent
}

data class ChatTurn(
    val role: String,
    val content: String,
)

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
                messages = listOf(ChatMessage("user", "Reply with exactly: OK")),
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
                val content = parsed.choices.firstOrNull()?.message?.content.orEmpty()
                content to elapsedMs
            }
        }

    fun streamChat(
        apiKey: String,
        baseUrl: String,
        model: String,
        turns: List<ChatTurn>,
        temperature: Double = 0.7,
        maxTokens: Int = 1024,
    ): Flow<NimStreamEvent> = flow {
        val payload = ChatRequest(
            model = model,
            messages = turns.map { ChatMessage(it.role, it.content) },
            temperature = temperature,
            topP = 0.95,
            maxTokens = maxTokens,
            stream = true,
        )
        val request = Request.Builder()
            .url(normalizeBase(baseUrl) + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(json.encodeToString(ChatRequest.serializer(), payload).toRequestBody(jsonMedia))
            .build()

        val call = http.newCall(request)
        coroutineContext[Job]?.invokeOnCompletion { call.cancel() }

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
                if (payloadLine == "[DONE]") break
                val chunk = runCatching {
                    json.decodeFromString(ChatChunk.serializer(), payloadLine)
                }.getOrNull() ?: continue
                val choice = chunk.choices.firstOrNull() ?: continue
                val delta = choice.delta
                if (delta != null) {
                    val content = delta.content
                    if (!content.isNullOrEmpty()) emit(NimStreamEvent.Content(content))
                    val reasoning = delta.reasoningContent
                    if (!reasoning.isNullOrEmpty()) emit(NimStreamEvent.Reasoning(reasoning))
                }
                choice.finishReason?.let { emit(NimStreamEvent.Finished(it)) }
            }
        }
    }.flowOn(Dispatchers.IO)

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
