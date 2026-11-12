package com.siamaehwa.nimstudio.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class MissingApiKeyException : Exception("API 키가 설정되지 않았습니다")

sealed interface ModelTestOutcome {
    data class Success(val latencyMs: Long, val sample: String) : ModelTestOutcome
    data class Failure(val message: String) : ModelTestOutcome
}

sealed interface ConnectionCheck {
    data class Success(val modelCount: Int, val latencyMs: Long) : ConnectionCheck
    data class Failure(val message: String) : ConnectionCheck
}

class NimRepository(
    private val store: NimStore,
    private val client: NimClient,
) {

    suspend fun models(): List<NimModel> {
        val key = store.apiKey.first() ?: throw MissingApiKeyException()
        val base = store.baseUrl.first()
        return client.fetchModels(key, base)
    }

    suspend fun testModel(model: String): ModelTestOutcome {
        val key = store.apiKey.first() ?: return ModelTestOutcome.Failure("API 키가 설정되지 않았습니다")
        val base = store.baseUrl.first()
        return try {
            if (ModelKinds.isLikelyImage(model)) {
                val started = System.nanoTime()
                client.generateImages(key, base, testImageParams(model))
                ModelTestOutcome.Success(
                    latencyMs = (System.nanoTime() - started) / 1_000_000,
                    sample = "이미지 생성 확인",
                )
            } else {
                val (sample, elapsed) = client.testChat(key, base, model)
                ModelTestOutcome.Success(elapsed, sample.trim())
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            ModelTestOutcome.Failure(NimErrors.friendlyFor(t))
        }
    }

    private fun testImageParams(model: String): ImageGenParams {
        val spec = ImageCatalog.spec(model)
        val side = if (spec?.squareOnly == true) 1024 else 768
        return ImageGenParams(
            model = model,
            prompt = "a small blue circle centered on a white background",
            width = side,
            height = side,
            steps = spec?.fixedSteps ?: (spec?.defaultSteps ?: 8).coerceAtMost(8),
        )
    }

    suspend fun verifyConnection(): ConnectionCheck {
        val started = System.nanoTime()
        return try {
            val list = models()
            ConnectionCheck.Success(list.size, (System.nanoTime() - started) / 1_000_000)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            ConnectionCheck.Failure(NimErrors.friendlyFor(t))
        }
    }

    suspend fun generateImages(params: ImageGenParams): List<GeneratedImage> {
        val key = store.apiKey.first() ?: throw MissingApiKeyException()
        val base = store.baseUrl.first()
        return client.generateImages(key, base, params)
    }

    fun streamAgent(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        reasoningEffort: String? = null,
        maxTokens: Int = 16384,
    ): Flow<AgentStreamEvent> = flow {
        val key = store.apiKey.first() ?: throw MissingApiKeyException()
        val base = store.baseUrl.first()
        emitAll(
            client.streamAgent(
                key,
                base,
                model,
                messages,
                tools,
                maxTokens = maxTokens,
                reasoningEffort = reasoningEffort,
            ),
        )
    }

    suspend fun complete(
        model: String,
        messages: List<ChatMessage>,
        maxTokens: Int = 1024,
        temperature: Double = 0.2,
        reasoningEffort: String? = null,
    ): String {
        val key = store.apiKey.first() ?: throw MissingApiKeyException()
        val base = store.baseUrl.first()
        return client.complete(key, base, model, messages, temperature, maxTokens, reasoningEffort)
    }

    fun stream(
        model: String,
        turns: List<ChatTurn>,
        temperature: Double,
        maxTokens: Int,
        reasoningEffort: String? = null,
    ): Flow<NimStreamEvent> = flow {
        val key = store.apiKey.first() ?: throw MissingApiKeyException()
        val base = store.baseUrl.first()
        emitAll(
            client.streamChat(
                key,
                base,
                model,
                turns,
                temperature,
                maxTokens,
                reasoningEffort = reasoningEffort,
            ),
        )
    }
}
