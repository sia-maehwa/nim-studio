package com.siamaehwa.nimstudio.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siamaehwa.nimstudio.AppContainer
import com.siamaehwa.nimstudio.core.ChatTurn
import com.siamaehwa.nimstudio.core.ModelKinds
import com.siamaehwa.nimstudio.core.NimErrors
import com.siamaehwa.nimstudio.core.NimModel
import com.siamaehwa.nimstudio.core.NimStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChatPreset(val label: String, val systemPrompt: String) {
    General(
        label = "일반",
        systemPrompt = "You are a helpful assistant. Answer in Korean unless the user asks otherwise.",
    ),
    Coding(
        label = "코딩",
        systemPrompt = "You are an expert programming assistant. Give correct, complete, runnable code with brief explanations. Prefer Kotlin, Python, TypeScript or Bash unless the user specifies otherwise. Answer in Korean unless the user asks otherwise.",
    ),
}

data class ChatMsg(
    val id: Long,
    val role: String,
    val content: String,
    val reasoning: String = "",
    val streaming: Boolean = false,
    val detail: String? = null,
)

data class ChatUiState(
    val modelsLoading: Boolean = false,
    val chatModels: List<NimModel> = emptyList(),
    val selectedModel: String? = null,
    val preset: ChatPreset = ChatPreset.General,
    val messages: List<ChatMsg> = emptyList(),
    val streaming: Boolean = false,
    val hasKey: Boolean? = null,
    val banner: String? = null,
)

class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var nextId = 1L
    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            container.store.apiKey.collect { key ->
                _state.update { it.copy(hasKey = !key.isNullOrBlank()) }
            }
        }
        viewModelScope.launch {
            container.store.lastModel.collect { saved ->
                if (!saved.isNullOrBlank()) {
                    _state.update { it.copy(selectedModel = saved) }
                }
            }
        }
        refreshModels()
    }

    fun refreshModels() {
        if (_state.value.modelsLoading) return
        viewModelScope.launch {
            _state.update { it.copy(modelsLoading = true) }
            val models = try {
                container.repository.models()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Exception) {
                _state.update { it.copy(modelsLoading = false, banner = NimErrors.friendlyFor(t)) }
                return@launch
            }
            val chatModels = models.filter { ModelKinds.isLikelyChat(it.id) }
            _state.update { s ->
                val current = s.selectedModel
                val selected = if (current != null && chatModels.any { it.id == current }) {
                    current
                } else {
                    preferredChatModel(chatModels)
                }
                s.copy(
                    modelsLoading = false,
                    chatModels = chatModels,
                    selectedModel = selected,
                    banner = null,
                )
            }
        }
    }

    private fun preferredChatModel(models: List<NimModel>): String? {
        val preferred = listOf(
            "meta/llama-3.1-8b-instruct",
            "meta/llama-3.3-70b-instruct",
            "nvidia/llama-3.3-nemotron-super-49b-v1",
            "deepseek-ai/deepseek-r1",
        )
        return preferred.firstOrNull { p -> models.any { it.id == p } } ?: models.firstOrNull()?.id
    }

    fun pickModel(id: String) {
        _state.update { it.copy(selectedModel = id) }
        viewModelScope.launch { container.store.saveLastModel(id) }
    }

    fun setPreset(preset: ChatPreset) {
        _state.update { it.copy(preset = preset) }
    }

    fun clearChat() {
        if (_state.value.streaming) return
        _state.update { it.copy(messages = emptyList(), banner = null) }
    }

    fun dismissBanner() {
        _state.update { it.copy(banner = null) }
    }

    fun stop() {
        streamJob?.cancel()
    }

    fun send(text: String) {
        val prompt = text.trim()
        val snapshot = _state.value
        if (prompt.isEmpty() || snapshot.streaming) return
        if (snapshot.hasKey != true) {
            _state.update { it.copy(banner = "API 키를 먼저 설정하세요.") }
            return
        }
        val model = snapshot.selectedModel
        if (model == null) {
            _state.update { it.copy(banner = "사용할 모델을 선택하세요.") }
            return
        }

        val userMsg = ChatMsg(id = nextId++, role = "user", content = prompt)
        val assistantId = nextId++
        _state.update {
            it.copy(
                messages = it.messages + userMsg +
                    ChatMsg(id = assistantId, role = "assistant", content = "", streaming = true),
                streaming = true,
                banner = null,
            )
        }

        val turns = buildTurns(snapshot.preset.systemPrompt, snapshot.messages + userMsg)

        streamJob = viewModelScope.launch {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val channel = Channel<Pair<Boolean, String>>(Channel.UNLIMITED)

            val producer = async {
                try {
                    container.repository
                        .stream(model, turns, temperature = 0.7, maxTokens = 1024)
                        .collect { event ->
                            when (event) {
                                is NimStreamEvent.Content -> channel.trySend(true to event.text)
                                is NimStreamEvent.Reasoning -> channel.trySend(false to event.text)
                                is NimStreamEvent.Finished -> {}
                            }
                        }
                } finally {
                    channel.close()
                }
            }

            try {
                for ((isContent, piece) in channel) {
                    if (isContent) content.append(piece) else reasoning.append(piece)
                    while (true) {
                        val next = channel.tryReceive().getOrNull() ?: break
                        if (next.first) content.append(next.second) else reasoning.append(next.second)
                    }
                    updateAssistant(
                        id = assistantId,
                        content = content.toString(),
                        reasoning = reasoning.toString(),
                        streaming = true,
                        detail = null,
                    )
                    delay(33)
                }
                producer.await()
                updateAssistant(
                    id = assistantId,
                    content = content.toString(),
                    reasoning = reasoning.toString(),
                    streaming = false,
                    detail = null,
                )
                _state.update { it.copy(streaming = false) }
            } catch (c: CancellationException) {
                producer.cancel()
                updateAssistant(
                    id = assistantId,
                    content = content.toString(),
                    reasoning = reasoning.toString(),
                    streaming = false,
                    detail = null,
                )
                _state.update { it.copy(streaming = false) }
                throw c
            } catch (t: Exception) {
                producer.cancel()
                val friendly = NimErrors.friendlyFor(t)
                val hasText = content.isNotEmpty() || reasoning.isNotEmpty()
                _state.update { s ->
                    s.copy(
                        streaming = false,
                        messages = s.messages.map { m ->
                            if (m.id != assistantId) {
                                m
                            } else if (hasText) {
                                m.copy(
                                    content = content.toString(),
                                    reasoning = reasoning.toString(),
                                    streaming = false,
                                    detail = friendly,
                                )
                            } else {
                                ChatMsg(id = assistantId, role = "error", content = friendly)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun updateAssistant(
        id: Long,
        content: String,
        reasoning: String,
        streaming: Boolean,
        detail: String?,
    ) {
        _state.update { s ->
            s.copy(
                messages = s.messages.map { m ->
                    if (m.id == id) {
                        m.copy(
                            content = content,
                            reasoning = reasoning,
                            streaming = streaming,
                            detail = detail,
                        )
                    } else {
                        m
                    }
                },
            )
        }
    }

    private fun buildTurns(systemPrompt: String, messages: List<ChatMsg>): List<ChatTurn> {
        val turns = mutableListOf(ChatTurn("system", systemPrompt))
        messages
            .filter { it.role == "user" || it.role == "assistant" }
            .filter { it.content.isNotBlank() }
            .takeLast(20)
            .forEach { turns.add(ChatTurn(it.role, it.content)) }
        return turns
    }
}
