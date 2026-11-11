package com.siamaehwa.nimstudio.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siamaehwa.nimstudio.AppContainer
import com.siamaehwa.nimstudio.KeepAliveService
import com.siamaehwa.nimstudio.core.AttachmentContent
import com.siamaehwa.nimstudio.core.Attachments
import com.siamaehwa.nimstudio.core.ChatTurn
import com.siamaehwa.nimstudio.core.ModelKinds
import com.siamaehwa.nimstudio.core.NimErrors
import com.siamaehwa.nimstudio.core.NimModel
import com.siamaehwa.nimstudio.core.NimStreamEvent
import com.siamaehwa.nimstudio.core.ReasoningEffort
import com.siamaehwa.nimstudio.core.TokenEstimator
import com.siamaehwa.nimstudio.core.TokenUsage
import com.siamaehwa.nimstudio.core.db.ConversationEntity
import com.siamaehwa.nimstudio.core.db.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

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
    val attachmentNames: List<String> = emptyList(),
    val parts: JsonElement? = null,
)

data class ChatUiState(
    val modelsLoading: Boolean = false,
    val chatModels: List<NimModel> = emptyList(),
    val selectedModel: String? = null,
    val preset: ChatPreset = ChatPreset.General,
    val reasoning: ReasoningEffort = ReasoningEffort.Default,
    val messages: List<ChatMsg> = emptyList(),
    val attachments: List<AttachmentContent> = emptyList(),
    val attachmentsLoading: Boolean = false,
    val streaming: Boolean = false,
    val tokens: Int = 0,
    val tokensExact: Boolean = false,
    val tokensPerSecond: Int = 0,
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
            container.store.chatReasoning.collect { effort ->
                _state.update { it.copy(reasoning = effort) }
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

    fun setReasoning(effort: ReasoningEffort) {
        _state.update { it.copy(reasoning = effort) }
        viewModelScope.launch { container.store.saveChatReasoning(effort) }
    }

    fun addAttachments(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val snapshot = _state.value
        val room = Attachments.MAX_FILES - snapshot.attachments.size
        if (room <= 0) {
            _state.update { it.copy(notice = "첨부는 최대 ${Attachments.MAX_FILES}개까지 가능합니다") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(attachmentsLoading = true) }
            val loaded = mutableListOf<AttachmentContent>()
            var error: String? = null
            for (uri in uris.take(room)) {
                Attachments.load(container.appContext, uri)
                    .onSuccess { loaded.add(it) }
                    .onFailure { error = it.message }
            }
            _state.update { s ->
                s.copy(
                    attachments = (s.attachments + loaded).take(Attachments.MAX_FILES),
                    attachmentsLoading = false,
                    notice = error ?: if (loaded.isNotEmpty()) {
                        "첨부 ${loaded.size}개를 추가했습니다"
                    } else {
                        s.notice
                    },
                )
            }
        }
    }

    fun removeAttachment(index: Int) {
        _state.update { s ->
            s.copy(attachments = s.attachments.filterIndexed { i, _ -> i != index })
        }
    }

    fun newChat() {
        if (_state.value.streaming) return
        _state.update {
            it.copy(
                conversationId = null,
                messages = emptyList(),
                attachments = emptyList(),
                tokens = 0,
                tokensExact = false,
                tokensPerSecond = 0,
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
                    attachments = emptyList(),
                    tokens = 0,
                    tokensExact = false,
                    tokensPerSecond = 0,
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
        val attachments = snapshot.attachments
        if ((prompt.isEmpty() && attachments.isEmpty()) || snapshot.streaming) return
        if (snapshot.hasKey != true) {
            _state.update { it.copy(banner = "API 키를 먼저 설정하세요.") }
            return
        }
        val model = snapshot.selectedModel
        if (model == null) {
            _state.update { it.copy(banner = "사용할 모델을 선택하세요.") }
            return
        }

        val parts = Attachments.userContent(prompt, attachments)
        val userMsg = ChatMsg(
            id = nextId++,
            role = "user",
            content = prompt,
            attachmentNames = attachments.map { it.name },
            parts = parts,
        )
        val assistantId = nextId++
        _state.update {
            it.copy(
                messages = it.messages + userMsg +
                    ChatMsg(id = assistantId, role = "assistant", content = "", streaming = true),
                attachments = emptyList(),
                streaming = true,
                tokens = 0,
                tokensExact = false,
                tokensPerSecond = 0,
                banner = null,
                notice = null,
            )
        }

        val turns = buildTurns(snapshot.preset.systemPrompt, snapshot.messages + userMsg)
        val reasoning = snapshot.reasoning.id

        streamJob = viewModelScope.launch {
            val convId = runCatching {
                ensureConversation(prompt.ifBlank { attachments.firstOrNull()?.name ?: "첨부" }, model)
            }.getOrNull()
            if (convId != null) {
                runCatching {
                    dao.insertMessage(
                        MessageEntity(
                            conversationId = convId,
                            role = "user",
                            content = prompt.ifBlank {
                                attachments.joinToString(", ") { it.name }
                            },
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
                reasoningEffort = reasoning,
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
                tokens = 0,
                tokensExact = false,
                tokensPerSecond = 0,
                banner = null,
                notice = null,
                messages = s.messages.map {
                    if (it.id == id) it.copy(truncated = false, detail = null) else it
                },
            )
        }
        val reasoning = snapshot.reasoning.id
        streamJob = viewModelScope.launch {
            streamInto(
                turns = turns,
                assistantId = id,
                initialContent = msg.content,
                initialReasoning = msg.reasoning,
                model = model,
                convId = snapshot.conversationId,
                reasoningEffort = reasoning,
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
        reasoningEffort: String?,
    ): Unit = coroutineScope {
        val content = StringBuilder(initialContent)
        val reasoning = StringBuilder(initialReasoning)
        val channel = Channel<Pair<Boolean, String>>(Channel.UNLIMITED)
        var finishReason: String? = null
        var usage: TokenUsage? = null
        val promptEstimate = TokenEstimator.estimate(
            turns.joinToString("\n") { it.content },
        )
        var firstTokenAt = 0L

        KeepAliveService.begin(container.appContext)
        val producer = async {
            try {
                container.repository
                    .stream(
                        model = model,
                        turns = turns,
                        temperature = 0.7,
                        maxTokens = MAX_RESPONSE_TOKENS,
                        reasoningEffort = reasoningEffort,
                    )
                    .collect { event ->
                        when (event) {
                            is NimStreamEvent.Content -> channel.trySend(true to event.text)
                            is NimStreamEvent.Reasoning -> channel.trySend(false to event.text)
                            is NimStreamEvent.Finished -> {
                                finishReason = event.reason
                                usage = event.usage
                            }
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
                val now = System.currentTimeMillis()
                if (firstTokenAt == 0L) firstTokenAt = now
                updateAssistant(
                    id = assistantId,
                    content = content.toString(),
                    reasoning = reasoning.toString(),
                    streaming = true,
                    detail = null,
                )
                val completion = TokenEstimator.estimate(
                    content.toString() + reasoning.toString(),
                )
                val elapsed = if (firstTokenAt > 0L) {
                    (now - firstTokenAt).coerceAtLeast(1L)
                } else {
                    0L
                }
                val speed = if (elapsed >= 250L) {
                    (completion * 1000.0 / elapsed).toInt()
                } else {
                    0
                }
                _state.update {
                    it.copy(
                        tokens = promptEstimate + completion,
                        tokensExact = false,
                        tokensPerSecond = speed,
                    )
                }
                delay(33)
            }
            producer.await()
            val now = System.currentTimeMillis()
            val elapsed = if (firstTokenAt > 0L) {
                (now - firstTokenAt).coerceAtLeast(1L)
            } else {
                0L
            }
            val completion = TokenEstimator.estimate(content.toString() + reasoning.toString())
            val finalUsage = usage
            val speed = if (elapsed >= 250L) {
                val tokens = finalUsage?.completionTokens?.takeIf { it > 0 } ?: completion
                (tokens * 1000.0 / elapsed).toInt()
            } else {
                0
            }
            _state.update {
                it.copy(
                    tokens = finalUsage?.totalTokens ?: (promptEstimate + completion),
                    tokensExact = finalUsage != null,
                    tokensPerSecond = speed,
                )
            }
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
        } finally {
            KeepAliveService.end(container.appContext)
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
            .filter { it.content.isNotBlank() || it.parts != null }
            .takeLast(20)
            .forEach { turns.add(ChatTurn(it.role, it.content, it.parts)) }
        return turns
    }
}
