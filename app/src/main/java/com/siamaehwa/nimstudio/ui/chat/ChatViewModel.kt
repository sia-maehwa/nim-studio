package com.siamaehwa.nimstudio.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siamaehwa.nimstudio.AppContainer
import com.siamaehwa.nimstudio.core.ChatTurn
import com.siamaehwa.nimstudio.core.ModelKinds
import com.siamaehwa.nimstudio.core.NimErrors
import com.siamaehwa.nimstudio.core.NimModel
import com.siamaehwa.nimstudio.core.NimStreamEvent
import com.siamaehwa.nimstudio.core.db.ConversationEntity
import com.siamaehwa.nimstudio.core.db.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val truncated: Boolean = false,
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
    val notice: String? = null,
    val conversationId: Long? = null,
    val conversations: List<ConversationEntity> = emptyList(),
)

class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val dao = container.conversations
    private val library = container.library

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
                if (!saved.isNullOrBlank() && _state.value.selectedModel == null) {
                    _state.update { it.copy(selectedModel = saved) }
                }
            }
        }
        viewModelScope.launch {
            dao.observeAll().collect { list ->
                _state.update { it.copy(conversations = list) }
            }
        }
        viewModelScope.launch {
            runCatching {
                val latest = dao.latest() ?: return@runCatching
                val msgs = dao.messages(latest.id)
                if (msgs.isNotEmpty() && _state.value.messages.isEmpty()) {
                    val mapped = msgs.map { m -> ChatMsg(nextId++, m.role, m.content, m.reasoning) }
                    _state.update {
                        it.copy(conversationId = latest.id, messages = mapped)
                    }
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

    fun newChat() {
        if (_state.value.streaming) return
        _state.update {
            it.copy(
                conversationId = null,
                messages = emptyList(),
                banner = null,
                notice = null,
            )
        }
    }

    fun openConversation(id: Long) {
        if (_state.value.streaming) return
        viewModelScope.launch {
            val msgs = dao.messages(id)
            val mapped = msgs.map { m -> ChatMsg(nextId++, m.role, m.content, m.reasoning) }
            _state.update {
                it.copy(
                    conversationId = id,
                    messages = mapped,
                    banner = null,
                    notice = null,
                )
            }
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            dao.delete(id)
            if (_state.value.conversationId == id) {
                _state.update {
                    it.copy(conversationId = null, messages = emptyList())
                }
            }
        }
    }

    fun dismissBanner() {
        _state.update { it.copy(banner = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
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
                notice = null,
            )
        }

        val turns = buildTurns(snapshot.preset.systemPrompt, snapshot.messages + userMsg)

        streamJob = viewModelScope.launch {
            val convId = runCatching { ensureConversation(prompt, model) }.getOrNull()
            if (convId != null) {
                runCatching {
                    dao.insertMessage(
                        MessageEntity(
                            conversationId = convId,
                            role = "user",
                            content = prompt,
                            model = model,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }

            streamInto(
                turns = turns,
                assistantId = assistantId,
                initialContent = "",
                initialReasoning = "",
                model = model,
                convId = convId,
            )
        }
    }

    fun continueAnswer(id: Long) {
        val snapshot = _state.value
        if (snapshot.streaming) return
        val msg = snapshot.messages.firstOrNull { it.id == id } ?: return
        if (msg.role != "assistant" || msg.content.isBlank()) return
        val model = snapshot.selectedModel ?: return
        val turns = buildTurns(snapshot.preset.systemPrompt, snapshot.messages) +
            ChatTurn("user", CONTINUE_PROMPT)
        _state.update { s ->
            s.copy(
                streaming = true,
                banner = null,
                notice = null,
                messages = s.messages.map {
                    if (it.id == id) it.copy(truncated = false, detail = null) else it
                },
            )
        }
        streamJob = viewModelScope.launch {
            streamInto(
                turns = turns,
                assistantId = id,
                initialContent = msg.content,
                initialReasoning = msg.reasoning,
                model = model,
                convId = snapshot.conversationId,
            )
        }
    }

    private suspend fun streamInto(
        turns: List<ChatTurn>,
        assistantId: Long,
        initialContent: String,
        initialReasoning: String,
        model: String,
        convId: Long?,
    ) {
        val content = StringBuilder(initialContent)
        val reasoning = StringBuilder(initialReasoning)
        val channel = Channel<Pair<Boolean, String>>(Channel.UNLIMITED)
        var finishReason: String? = null

        val producer = async {
            try {
                container.repository
                    .stream(model, turns, temperature = 0.7, maxTokens = MAX_RESPONSE_TOKENS)
                    .collect { event ->
                        when (event) {
                            is NimStreamEvent.Content -> channel.trySend(true to event.text)
                            is NimStreamEvent.Reasoning -> channel.trySend(false to event.text)
                            is NimStreamEvent.Finished -> finishReason = event.reason
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
                truncated = finishReason != "stop",
            )
            _state.update { it.copy(streaming = false) }
            if (convId != null) {
                withContext(NonCancellable) {
                    persistAssistant(convId, model, content.toString(), reasoning.toString())
                }
            }
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
            if (convId != null) {
                withContext(NonCancellable) {
                    persistAssistant(convId, model, content.toString(), reasoning.toString())
                }
            }
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
            if (convId != null && hasText) {
                persistAssistant(convId, model, content.toString(), reasoning.toString())
            }
        }
    }

    private suspend fun ensureConversation(firstPrompt: String, model: String): Long {
        val existing = _state.value.conversationId
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        val id = dao.insertConversation(
            ConversationEntity(
                title = titleFrom(firstPrompt),
                model = model,
                createdAt = now,
                updatedAt = now,
            ),
        )
        _state.update { it.copy(conversationId = id) }
        return id
    }

    private suspend fun persistAssistant(
        conversationId: Long,
        model: String,
        content: String,
        reasoning: String,
    ) {
        if (content.isBlank() && reasoning.isBlank()) return
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = "assistant",
                content = content,
                reasoning = reasoning,
                model = model,
                createdAt = System.currentTimeMillis(),
            ),
        )
        dao.touch(conversationId, System.currentTimeMillis())
    }

    private fun titleFrom(prompt: String): String =
        prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40) ?: "새 대화"

    fun saveMessageToLibrary(msg: ChatMsg) {
        val content = msg.content.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            val kind = if (content.contains("```")) "code" else "text"
            val firstLine = content.lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(60)
                ?: "답변"
            runCatching {
                library.saveText(
                    kind = kind,
                    title = firstLine,
                    content = content,
                    source = "채팅 · ${_state.value.selectedModel ?: ""}",
                )
            }
            _state.update { it.copy(notice = "라이브러리에 저장했습니다") }
        }
    }

    fun exportConversation() {
        val snapshot = _state.value
        if (snapshot.messages.isEmpty()) return
        val title = snapshot.conversations
            .firstOrNull { it.id == snapshot.conversationId }
            ?.title
            ?: "대화"
        viewModelScope.launch {
            val markdown = buildString {
                append("# ").append(title).append("\n\n")
                snapshot.messages
                    .filter { it.role == "user" || it.role == "assistant" }
                    .filter { it.content.isNotBlank() }
                    .forEach { m ->
                        append("## ").append(if (m.role == "user") "사용자" else "어시스턴트")
                        append("\n\n")
                        append(m.content).append("\n\n")
                    }
            }
            runCatching {
                library.saveText(
                    kind = "text",
                    title = "대화 · $title",
                    content = markdown,
                    source = "채팅 내보내기",
                )
            }
            _state.update { it.copy(notice = "대화를 라이브러리에 저장했습니다") }
        }
    }

    private fun updateAssistant(
        id: Long,
        content: String,
        reasoning: String,
        streaming: Boolean,
        detail: String?,
        truncated: Boolean = false,
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
                            truncated = truncated,
                        )
                    } else {
                        m
                    }
                },
            )
        }
    }

    private companion object {
        const val MAX_RESPONSE_TOKENS = 4096
        const val CONTINUE_PROMPT =
            "이어서 계속 작성해 주세요. 앞부분을 반복하지 말고 중단된 지점부터 " +
                "자연스럽게 바로 이어서 작성하세요."
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
