package com.siamaehwa.nimstudio.core.hf

import com.siamaehwa.nimstudio.core.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class HfRepository(
    private val store: HfStore,
    private val client: HfClient,
    private val library: LibraryRepository,
) {
    val token: Flow<String?> = store.token
    val endpoint: Flow<HfEndpointConfig> = store.endpoint
    val endpointToken: Flow<String?> = store.endpointToken
    val useEndpoint: Flow<Boolean> = store.useEndpoint
    val savedModels: Flow<List<HfSavedModel>> = store.savedModels
    val lastChatModel: Flow<String?> = store.lastChatModel
    val lastChatProvider: Flow<String?> = store.lastChatProvider
    val lastImageModel: Flow<String?> = store.lastImageModel
    val lastImageProvider: Flow<String?> = store.lastImageProvider

    suspend fun saveToken(token: String) = store.saveToken(token)
    suspend fun clearToken() = store.clearToken()

    suspend fun verifyToken(token: String): String = client.whoAmI(token)

    suspend fun searchModels(
        token: String?,
        query: String,
        availableOnly: Boolean = false,
    ): List<HfModelSummary> = client.searchModels(token, query, availableOnly = availableOnly)

    suspend fun modelDetails(token: String?, modelId: String): HfModelSummary =
        client.modelDetails(token, modelId)

    fun chatStream(
        token: String,
        model: String,
        provider: String?,
        messages: List<HfChatMessage>,
    ): Flow<HfChatEvent> = client.chatStream(token, model, provider, messages)

    fun endpointChatStream(
        config: HfEndpointConfig,
        token: String?,
        messages: List<HfChatMessage>,
    ): Flow<HfChatEvent> = client.endpointChatStream(config, token, messages)

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
        referenceImage: ByteArray? = null,
    ): ByteArray = client.generateImage(
        token = token,
        model = model,
        provider = provider,
        providerId = providerId,
        prompt = prompt,
        negativePrompt = negativePrompt,
        width = width,
        height = height,
        steps = steps,
        seed = seed,
        guidance = guidance,
        referenceImage = referenceImage,
    )

    suspend fun endpointGenerateImage(
        config: HfEndpointConfig,
        token: String?,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Long?,
    ): ByteArray = client.endpointGenerateImage(
        config = config,
        token = token,
        prompt = prompt,
        negativePrompt = negativePrompt,
        width = width,
        height = height,
        steps = steps,
        seed = seed,
    )

    suspend fun testEndpoint(config: HfEndpointConfig, token: String?): String =
        client.testEndpointChat(config, token)

    suspend fun saveGeneratedImage(bytes: ByteArray, prompt: String, model: String): Long =
        library.saveImage(bytes, prompt, "Hugging Face · $model")

    suspend fun saveEndpoint(config: HfEndpointConfig, token: String?) =
        store.saveEndpoint(config, token)

    suspend fun clearEndpoint() = store.clearEndpoint()

    suspend fun setUseEndpoint(enabled: Boolean) = store.saveUseEndpoint(enabled)

    suspend fun rememberChat(model: String, provider: String?) =
        store.saveLastChat(model, provider)

    suspend fun rememberImage(model: String, provider: String?) =
        store.saveLastImage(model, provider)

    suspend fun addSaved(model: HfModelSummary, task: HfTask, provider: String?) {
        val current = store.savedModels.first()
        if (current.any { it.id == model.id }) return
        store.saveSavedModels(
            current + HfSavedModel(
                id = model.id,
                taskId = task.id,
                provider = provider,
                providerId = model.providers.firstOrNull { it.provider == provider }?.providerId,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeSaved(id: String) {
        val current = store.savedModels.first()
        store.saveSavedModels(current.filterNot { it.id == id })
    }

    suspend fun recordTest(id: String, ok: Boolean, message: String?) {
        val current = store.savedModels.first()
        val stamp = System.currentTimeMillis().toString()
        store.saveSavedModels(
            current.map { saved ->
                if (saved.id != id) saved else saved.copy(
                    lastTest = if (ok) stamp else saved.lastTest,
                    lastError = if (ok) null else message,
                )
            },
        )
    }
}
