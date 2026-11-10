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
            val (sample, elapsed) = client.testChat(key, base, model)
            ModelTestOutcome.Success(elapsed, sample.trim())
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            ModelTestOutcome.Failure(NimErrors.friendlyFor(t))
        }
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

    fun stream(
        model: String,
        turns: List<ChatTurn>,
        temperature: Double,
        maxTokens: Int,
    ): Flow<NimStreamEvent> = flow {
        val key = store.apiKey.first() ?: throw MissingApiKeyException()
        val base = store.baseUrl.first()
        emitAll(client.streamChat(key, base, model, turns, temperature, maxTokens))
    }
}
