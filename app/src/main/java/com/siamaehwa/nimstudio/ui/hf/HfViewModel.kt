package com.siamaehwa.nimstudio.ui.hf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siamaehwa.nimstudio.AppContainer
import com.siamaehwa.nimstudio.core.hf.HfApiStyle
import com.siamaehwa.nimstudio.core.hf.HfChatEvent
import com.siamaehwa.nimstudio.core.hf.HfChatMessage
import com.siamaehwa.nimstudio.core.hf.HfEndpointConfig
import com.siamaehwa.nimstudio.core.hf.HfErrors
import com.siamaehwa.nimstudio.core.hf.HfModelSummary
import com.siamaehwa.nimstudio.core.hf.HfProviderEntry
import com.siamaehwa.nimstudio.core.hf.HfSavedModel
import com.siamaehwa.nimstudio.core.hf.HfTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HfUiState(
    val tab: Int = 0,
    val tokenPresent: Boolean = false,
    val user: String? = null,
    val tokenInput: String = "",
    val verifying: Boolean = false,
    val tokenMessage: String? = null,
    val tokenError: String? = null,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<HfModelSummary> = emptyList(),
    val searchError: String? = null,
    val taskFilter: String = "all",
    val availableOnly: Boolean = true,
    val saved: List<HfSavedModel> = emptyList(),
    val opening: Boolean = false,
    val openError: String? = null,
    val chatModel: String? = null,
    val chatProvider: String? = null,
    val chatProviders: List<String> = emptyList(),
    val chatMessages: List<HfChatMessage> = emptyList(),
    val chatInput: String = "",
    val chatStreaming: Boolean = false,
    val chatNotice: String? = null,
    val chatError: String? = null,
    val chatUsage: String? = null,
    val imageModel: String? = null,
    val imageProvider: String? = null,
    val imageProviderId: String? = null,
    val imageProviders: List<HfProviderEntry> = emptyList(),
    val imagePrompt: String = "",
    val negative: String = "",
    val sizeIndex: Int = 0,
    val steps: Int = 28,
    val seed: String = "",
    val generating: Boolean = false,
    val images: List<ByteArray> = emptyList(),
    val imageError: String? = null,
    val imageNotice: String? = null,
    val endpointUrl: String = "",
    val endpointModel: String = "",
    val endpointTaskId: String = HfTask.Chat.id,
    val endpointStyleId: String = HfApiStyle.OpenAi.id,
    val endpointTokenInput: String = "",
    val endpointTokenPresent: Boolean = false,
    val endpointTesting: Boolean = false,
    val endpointMessage: String? = null,
    val endpointError: String? = null,
    val useEndpoint: Boolean = false,
    val endpointConfigured: Boolean = false,
) {
    val chatReady: Boolean get() = !chatModel.isNullOrBlank()
    val imageReady: Boolean get() = !imageModel.isNullOrBlank()
}

data class HfSize(val label: String, val width: Int, val height: Int)

class HfViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        val sizes = listOf(
            HfSize("1:1 · 1024", 1024, 1024),
            HfSize("가로 · 1152×896", 1152, 896),
            HfSize("세로 · 896×1152", 896, 1152),
            HfSize("정사각 · 768", 768, 768),
        )

        private val imageProviderOrder = listOf("hf-inference", "fal-ai")
    }

    private val repository = container.hfRepository

    private val _state = MutableStateFlow(HfUiState())
    val state: StateFlow<HfUiState> = _state.asStateFlow()

    private var currentToken: String? = null
    private var endpointToken: String? = null
    private var chatJob: Job? = null
    private var imageJob: Job? = null

    init {
        viewModelScope.launch {
            repository.token.collect { token ->
                currentToken = token
                _state.update { it.copy(tokenPresent = !token.isNullOrBlank()) }
            }
        }
        viewModelScope.launch {
            repository.endpointToken.collect { token ->
                endpointToken = token
                _state.update { it.copy(endpointTokenPresent = !token.isNullOrBlank()) }
            }
        }
        viewModelScope.launch {
            repository.endpoint.collect { config ->
                _state.update {
                    it.copy(
                        endpointUrl = config.url,
                        endpointModel = config.modelId,
                        endpointTaskId = config.taskId,
                        endpointStyleId = config.apiStyleId,
                        endpointConfigured = config.configured,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.useEndpoint.collect { enabled ->
                _state.update { it.copy(useEndpoint = enabled) }
            }
        }
        viewModelScope.launch {
            repository.savedModels.collect { models ->
                _state.update { it.copy(saved = models) }
            }
        }
        viewModelScope.launch {
            repository.lastChatModel.collect { model ->
                if (!model.isNullOrBlank()) {
                    _state.update { it.copy(chatModel = it.chatModel ?: model) }
                }
            }
        }
        viewModelScope.launch {
            repository.lastChatProvider.collect { provider ->
                if (!provider.isNullOrBlank()) {
                    _state.update { it.copy(chatProvider = it.chatProvider ?: provider) }
                }
            }
        }
        viewModelScope.launch {
            repository.lastImageModel.collect { model ->
                if (!model.isNullOrBlank()) {
                    _state.update { it.copy(imageModel = it.imageModel ?: model) }
                }
            }
        }
        viewModelScope.launch {
            repository.lastImageProvider.collect { provider ->
                if (!provider.isNullOrBlank()) {
                    _state.update { it.copy(imageProvider = it.imageProvider ?: provider) }
                }
            }
        }
        search()
    }

    fun setTab(index: Int) {
        _state.update { it.copy(tab = index.coerceIn(0, 3)) }
    }

    fun setTokenInput(value: String) {
        _state.update { it.copy(tokenInput = value) }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun setTaskFilter(value: String) {
        _state.update { it.copy(taskFilter = value) }
    }

    fun toggleAvailableOnly() {
        _state.update { it.copy(availableOnly = !it.availableOnly) }
        search()
    }

    fun saveToken() {
        val input = _state.value.tokenInput.trim()
        if (input.isEmpty()) {
            _state.update { it.copy(tokenError = "HF 토큰을 입력하세요.") }
            return
        }
        if (_state.value.verifying) return
        viewModelScope.launch {
            _state.update { it.copy(verifying = true, tokenError = null, tokenMessage = null) }
            runCatching { repository.verifyToken(input) }
                .onSuccess { name ->
                    repository.saveToken(input)
                    _state.update {
                        it.copy(
                            verifying = false,
                            user = name,
                            tokenInput = "",
                            tokenMessage = "토큰 확인 완료: $name",
                        )
                    }
                    search()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            verifying = false,
                            tokenError = HfErrors.friendlyFor(error),
                        )
                    }
                }
        }
    }

    fun verifySavedToken() {
        val token = currentToken ?: return
        viewModelScope.launch {
            _state.update { it.copy(verifying = true, tokenError = null, tokenMessage = null) }
            runCatching { repository.verifyToken(token) }
                .onSuccess { name ->
                    _state.update {
                        it.copy(
                            verifying = false,
                            user = name,
                            tokenMessage = "토큰 확인 완료: $name",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(verifying = false, tokenError = HfErrors.friendlyFor(error))
                    }
                }
        }
    }

    fun clearToken() {
        viewModelScope.launch {
            repository.clearToken()
            _state.update {
                it.copy(
                    user = null,
                    tokenMessage = "HF 토큰을 삭제했습니다.",
                    tokenError = null,
                )
            }
        }
    }

    fun search() {
        if (_state.value.searching) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true, searchError = null) }
            runCatching {
                repository.searchModels(
                    currentToken,
                    _state.value.query,
                    availableOnly = _state.value.availableOnly,
                )
            }
                .onSuccess { models ->
                    _state.update { it.copy(searching = false, results = models) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(searching = false, searchError = HfErrors.friendlyFor(error))
                    }
                }
        }
    }

    fun toggleSaved(model: HfModelSummary, task: HfTask, provider: String?) {
        viewModelScope.launch {
            val exists = _state.value.saved.any { it.id == model.id }
            if (exists) {
                repository.removeSaved(model.id)
            } else {
                repository.addSaved(model, task, provider)
            }
        }
    }

    fun removeSaved(saved: HfSavedModel) {
        viewModelScope.launch { repository.removeSaved(saved.id) }
    }

    fun openSaved(saved: HfSavedModel) {
        if (_state.value.opening) return
        viewModelScope.launch {
            _state.update { it.copy(opening = true, openError = null) }
            runCatching { repository.modelDetails(currentToken, saved.id) }
                .onSuccess { model ->
                    _state.update { it.copy(opening = false) }
                    repository.recordTest(saved.id, ok = true, message = null)
                    val task = HfTask.fromId(saved.taskId)
                    if (task == HfTask.TextToImage) {
                        selectForImage(model, saved.provider)
                    } else {
                        selectForChat(model, saved.provider)
                    }
                }
                .onFailure { error ->
                    val message = HfErrors.friendlyFor(error)
                    _state.update { it.copy(opening = false, openError = message) }
                    repository.recordTest(saved.id, ok = false, message = message)
                }
        }
    }

    fun selectForChat(model: HfModelSummary, preferred: String? = null) {
        val live = model.liveProviders(HfTask.Chat).map { it.provider }
        val provider = preferred?.takeIf { it in live } ?: live.firstOrNull()
        _state.update {
            it.copy(
                chatModel = model.id,
                chatProviders = live,
                chatProvider = provider,
                tab = 1,
                chatError = null,
                chatNotice = if (live.isEmpty()) {
                    "이 모델은 채팅 Provider가 없습니다. Endpoint로 배포해야 합니다."
                } else {
                    null
                },
            )
        }
        viewModelScope.launch { repository.rememberChat(model.id, provider) }
    }

    fun selectForImage(model: HfModelSummary, preferred: String? = null) {
        val live = model.liveProviders(HfTask.TextToImage)
        val supported = live.filter { it.provider in imageProviderOrder }
        val chosen = preferred?.let { name -> supported.firstOrNull { it.provider == name } }
            ?: supported.firstOrNull()
            ?: live.firstOrNull()
        _state.update {
            it.copy(
                imageModel = model.id,
                imageProviders = live,
                imageProvider = chosen?.provider,
                imageProviderId = chosen?.providerId,
                tab = 2,
                imageError = null,
                imageNotice = if (chosen == null) {
                    "이 모델은 이미지 Provider가 없습니다. Endpoint로 배포해야 합니다."
                } else if (chosen.provider !in imageProviderOrder) {
                    "이 Provider(${chosen.provider})의 이미지 생성은 아직 지원하지 않습니다."
                } else {
                    null
                },
            )
        }
        viewModelScope.launch { repository.rememberImage(model.id, chosen?.provider) }
    }

    fun setChatProvider(provider: String) {
        _state.update { it.copy(chatProvider = provider) }
        val model = _state.value.chatModel
        if (!model.isNullOrBlank()) {
            viewModelScope.launch { repository.rememberChat(model, provider) }
        }
    }

    fun setImageProvider(provider: String) {
        val entry = _state.value.imageProviders.firstOrNull { it.provider == provider }
        _state.update {
            it.copy(
                imageProvider = provider,
                imageProviderId = entry?.providerId,
                imageError = null,
            )
        }
        val model = _state.value.imageModel
        if (!model.isNullOrBlank()) {
            viewModelScope.launch { repository.rememberImage(model, provider) }
        }
    }

    fun setChatInput(value: String) {
        _state.update { it.copy(chatInput = value) }
    }

    fun sendChat() {
        val text = _state.value.chatInput.trim()
        if (text.isEmpty() || _state.value.chatStreaming) return
        val token = currentToken
        if (token.isNullOrBlank()) {
            _state.update { it.copy(chatError = "Hugging Face 토큰을 먼저 등록하세요.") }
            return
        }
        val model = _state.value.chatModel
        if (model.isNullOrBlank()) {
            _state.update { it.copy(chatError = "채팅 모델을 먼저 선택하세요.") }
            return
        }
        val endpointMode = _state.value.useEndpoint &&
            _state.value.endpointConfigured &&
            HfTask.fromId(_state.value.endpointTaskId) == HfTask.Chat
        if (!endpointMode && _state.value.chatProvider.isNullOrBlank()) {
            _state.update {
                it.copy(chatError = "채팅 Provider가 없습니다. 다른 모델을 선택하세요.")
            }
            return
        }
        val history = _state.value.chatMessages + HfChatMessage("user", text)
        _state.update {
            it.copy(
                chatMessages = history + HfChatMessage("assistant", ""),
                chatInput = "",
                chatStreaming = true,
                chatError = null,
                chatNotice = null,
                chatUsage = null,
            )
        }
        chatJob = viewModelScope.launch {
            val builder = StringBuilder()
            try {
                val flow = if (endpointMode) {
                    repository.endpointChatStream(
                        config = endpointConfig(),
                        token = endpointToken ?: currentToken,
                        messages = history,
                    )
                } else {
                    repository.chatStream(
                        token = token,
                        model = model,
                        provider = _state.value.chatProvider,
                        messages = history,
                    )
                }
                flow.collect { event ->
                    when (event) {
                        is HfChatEvent.Content -> {
                            builder.append(event.text)
                            updateAssistant(builder.toString())
                        }

                        is HfChatEvent.Notice -> {
                            _state.update { it.copy(chatNotice = event.text) }
                        }

                        is HfChatEvent.Done -> {
                            val usage = if (event.promptTokens + event.completionTokens > 0) {
                                "토큰 ${event.promptTokens} + ${event.completionTokens}"
                            } else {
                                null
                            }
                            _state.update {
                                it.copy(
                                    chatStreaming = false,
                                    chatUsage = usage,
                                    chatNotice = if (event.reason == "length") {
                                        "응답이 길이 제한으로 잘렸습니다. 계속하려면 '계속'을 입력하세요."
                                    } else {
                                        it.chatNotice
                                    },
                                )
                            }
                        }
                    }
                }
                _state.update { it.copy(chatStreaming = false) }
                repository.rememberChat(model, _state.value.chatProvider)
            } catch (cancel: CancellationException) {
                _state.update {
                    it.copy(chatStreaming = false, chatNotice = "생성이 중단되었습니다.")
                }
                throw cancel
            } catch (error: Throwable) {
                val message = HfErrors.friendlyFor(error)
                _state.update { state ->
                    val messages = if (
                        state.chatMessages.lastOrNull()?.role == "assistant" &&
                        state.chatMessages.last().content.isBlank()
                    ) {
                        state.chatMessages.dropLast(1)
                    } else {
                        state.chatMessages
                    }
                    state.copy(chatStreaming = false, chatError = message, chatMessages = messages)
                }
            }
        }
    }

    fun stopChat() {
        chatJob?.cancel()
    }

    fun clearChat() {
        chatJob?.cancel()
        _state.update {
            it.copy(
                chatMessages = emptyList(),
                chatNotice = null,
                chatError = null,
                chatUsage = null,
                chatStreaming = false,
            )
        }
    }

    fun setImagePrompt(value: String) {
        _state.update { it.copy(imagePrompt = value) }
    }

    fun setNegative(value: String) {
        _state.update { it.copy(negative = value) }
    }

    fun setSizeIndex(index: Int) {
        _state.update { it.copy(sizeIndex = index.coerceIn(0, sizes.lastIndex)) }
    }

    fun setSteps(value: Int) {
        _state.update { it.copy(steps = value.coerceIn(4, 50)) }
    }

    fun setSeed(value: String) {
        _state.update { it.copy(seed = value.filter { ch -> ch.isDigit() }.take(12)) }
    }

    fun generateImage() {
        if (_state.value.generating) return
        val token = currentToken
        if (token.isNullOrBlank()) {
            _state.update { it.copy(imageError = "Hugging Face 토큰을 먼저 등록하세요.") }
            return
        }
        val prompt = _state.value.imagePrompt.trim()
        if (prompt.isEmpty()) {
            _state.update { it.copy(imageError = "프롬프트를 입력하세요.") }
            return
        }
        val endpointMode = _state.value.useEndpoint &&
            _state.value.endpointConfigured &&
            HfTask.fromId(_state.value.endpointTaskId) == HfTask.TextToImage
        val model = _state.value.imageModel
        if (!endpointMode) {
            if (model.isNullOrBlank()) {
                _state.update { it.copy(imageError = "이미지 모델을 먼저 선택하세요.") }
                return
            }
            if (_state.value.imageProvider !in imageProviderOrder) {
                _state.update {
                    it.copy(
                        imageError = "이 Provider의 이미지 생성은 지원하지 않습니다. " +
                            "hf-inference 또는 fal-ai 모델을 선택하세요.",
                    )
                }
                return
            }
        }
        val size = sizes[_state.value.sizeIndex]
        _state.update {
            it.copy(generating = true, imageError = null, imageNotice = null)
        }
        imageJob = viewModelScope.launch {
            try {
                val bytes = if (endpointMode) {
                    repository.endpointGenerateImage(
                        config = endpointConfig(),
                        token = endpointToken ?: currentToken,
                        prompt = prompt,
                        negativePrompt = _state.value.negative.trim(),
                        width = size.width,
                        height = size.height,
                        steps = _state.value.steps,
                        seed = _state.value.seed.toLongOrNull(),
                    )
                } else {
                    repository.generateImage(
                        token = token,
                        model = model.orEmpty(),
                        provider = _state.value.imageProvider,
                        providerId = _state.value.imageProviderId,
                        prompt = prompt,
                        negativePrompt = _state.value.negative.trim(),
                        width = size.width,
                        height = size.height,
                        steps = _state.value.steps,
                        seed = _state.value.seed.toLongOrNull(),
                        guidance = null,
                    )
                }
                val saved = runCatching {
                    repository.saveGeneratedImage(
                        bytes,
                        prompt,
                        model ?: "endpoint",
                    )
                }.isSuccess
                _state.update {
                    it.copy(
                        generating = false,
                        images = (listOf(bytes) + it.images).take(12),
                        imageNotice = if (saved) {
                            "이미지 생성 완료 · 라이브러리에 저장됨"
                        } else {
                            "이미지 생성 완료 · 라이브러리 저장 실패"
                        },
                    )
                }
                if (!model.isNullOrBlank()) {
                    repository.rememberImage(model, _state.value.imageProvider)
                }
            } catch (cancel: CancellationException) {
                _state.update {
                    it.copy(generating = false, imageNotice = "이미지 생성이 중단되었습니다.")
                }
                throw cancel
            } catch (error: Throwable) {
                _state.update {
                    it.copy(generating = false, imageError = HfErrors.friendlyFor(error))
                }
            }
        }
    }

    fun stopImage() {
        imageJob?.cancel()
    }

    fun setEndpointUrl(value: String) {
        _state.update { it.copy(endpointUrl = value, endpointMessage = null) }
    }

    fun setEndpointModel(value: String) {
        _state.update { it.copy(endpointModel = value, endpointMessage = null) }
    }

    fun setEndpointTask(task: HfTask) {
        _state.update { it.copy(endpointTaskId = task.id, endpointMessage = null) }
    }

    fun setEndpointStyle(style: HfApiStyle) {
        _state.update { it.copy(endpointStyleId = style.id, endpointMessage = null) }
    }

    fun setEndpointTokenInput(value: String) {
        _state.update { it.copy(endpointTokenInput = value) }
    }

    fun saveEndpoint() {
        val state = _state.value
        val url = state.endpointUrl.trim()
        if (url.isEmpty()) {
            _state.update { it.copy(endpointError = "Endpoint URL을 입력하세요.") }
            return
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            _state.update {
                it.copy(endpointError = "URL은 https:// 또는 http://로 시작해야 합니다.")
            }
            return
        }
        val config = endpointConfig()
        viewModelScope.launch {
            repository.saveEndpoint(config, state.endpointTokenInput.ifBlank { null })
            _state.update {
                it.copy(
                    endpointTokenInput = "",
                    endpointConfigured = true,
                    endpointError = null,
                    endpointMessage = if (url.startsWith("http://")) {
                        "저장됨 · HTTP는 암호화되지 않습니다. 가능하면 HTTPS를 사용하세요."
                    } else {
                        "Endpoint 설정을 저장했습니다."
                    },
                )
            }
        }
    }

    fun testEndpoint() {
        if (_state.value.endpointTesting) return
        val config = endpointConfig()
        if (!config.configured) {
            _state.update { it.copy(endpointError = "Endpoint URL을 먼저 입력하세요.") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    endpointTesting = true,
                    endpointError = null,
                    endpointMessage = null,
                )
            }
            runCatching {
                if (config.task == HfTask.TextToImage) {
                    val bytes = repository.endpointGenerateImage(
                        config = config,
                        token = endpointToken ?: currentToken,
                        prompt = "a simple red circle on white background",
                        negativePrompt = "",
                        width = 512,
                        height = 512,
                        steps = 4,
                        seed = null,
                    )
                    "이미지 Endpoint 응답 확인 완료 (${bytes.size} bytes)"
                } else {
                    repository.testEndpoint(config, endpointToken ?: currentToken)
                }
            }
                .onSuccess { message ->
                    _state.update {
                        it.copy(endpointTesting = false, endpointMessage = message)
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            endpointTesting = false,
                            endpointError = HfErrors.friendlyFor(error),
                        )
                    }
                }
        }
    }

    fun clearEndpoint() {
        viewModelScope.launch {
            repository.clearEndpoint()
            _state.update {
                it.copy(
                    endpointUrl = "",
                    endpointModel = "",
                    endpointTokenInput = "",
                    endpointConfigured = false,
                    endpointMessage = "Endpoint 설정을 삭제했습니다.",
                    endpointError = null,
                )
            }
        }
    }

    fun setUseEndpoint(enabled: Boolean) {
        if (enabled && !_state.value.endpointConfigured) {
            _state.update {
                it.copy(endpointError = "Endpoint 설정을 먼저 저장하세요.")
            }
            return
        }
        viewModelScope.launch {
            repository.setUseEndpoint(enabled)
            _state.update { it.copy(endpointError = null) }
        }
    }

    private fun endpointConfig(): HfEndpointConfig = HfEndpointConfig(
        url = _state.value.endpointUrl.trim(),
        modelId = _state.value.endpointModel.trim(),
        taskId = _state.value.endpointTaskId,
        apiStyleId = _state.value.endpointStyleId,
    )

    private fun updateAssistant(text: String) {
        _state.update { state ->
            val messages = state.chatMessages.toMutableList()
            if (messages.lastOrNull()?.role == "assistant") {
                messages[messages.lastIndex] = HfChatMessage("assistant", text)
            } else {
                messages.add(HfChatMessage("assistant", text))
            }
            state.copy(chatMessages = messages)
        }
    }
}
