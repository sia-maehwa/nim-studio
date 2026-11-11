package com.siamaehwa.nimstudio.ui.agent

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siamaehwa.nimstudio.AppContainer
import com.siamaehwa.nimstudio.KeepAliveService
import com.siamaehwa.nimstudio.core.AgentStreamEvent
import com.siamaehwa.nimstudio.core.AgentToolCall
import com.siamaehwa.nimstudio.core.AttachmentContent
import com.siamaehwa.nimstudio.core.Attachments
import com.siamaehwa.nimstudio.core.ChatMessage
import com.siamaehwa.nimstudio.core.ModelKinds
import com.siamaehwa.nimstudio.core.NimErrors
import com.siamaehwa.nimstudio.core.NimModel
import com.siamaehwa.nimstudio.core.ReasoningEffort
import com.siamaehwa.nimstudio.core.TokenEstimator
import com.siamaehwa.nimstudio.core.ToolCall
import com.siamaehwa.nimstudio.core.ToolCallFunction
import com.siamaehwa.nimstudio.core.agent.AgentTools
import com.siamaehwa.nimstudio.core.agent.ToolOutcome
import com.siamaehwa.nimstudio.core.agent.UndoInfo
import com.siamaehwa.nimstudio.core.agent.Workspace
import com.siamaehwa.nimstudio.core.asMessageText
import com.siamaehwa.nimstudio.core.db.AgentSessionSummary
import com.siamaehwa.nimstudio.core.plugin.PluginManifest
import com.siamaehwa.nimstudio.core.plugin.PluginTool
import com.siamaehwa.nimstudio.core.textContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class AgentTodo(
    val id: String,
    val content: String,
    val status: String,
)

data class AgentQuestion(
    val question: String,
    val options: List<String>,
)

data class AgentMsg(
    val id: Long,
    val kind: String,
    val text: String = "",
    val streaming: Boolean = false,
    val toolName: String? = null,
    val toolStatus: String? = null,
    val canUndo: Boolean = false,
    val undone: Boolean = false,
)

data class AgentHistoryView(
    val session: AgentSessionSummary,
    val messages: List<AgentMsg>,
)

data class AgentUiState(
    val hasKey: Boolean? = null,
    val workspaceName: String? = null,
    val workspaceReady: Boolean = false,
    val workspaceLoading: Boolean = false,
    val workspaceTruncated: Boolean = false,
    val fileCount: Int = 0,
    val models: List<NimModel> = emptyList(),
    val modelsLoading: Boolean = false,
    val selectedModel: String? = null,
    val reasoning: ReasoningEffort = ReasoningEffort.Default,
    val messages: List<AgentMsg> = emptyList(),
    val attachments: List<AttachmentContent> = emptyList(),
    val attachmentsLoading: Boolean = false,
    val running: Boolean = false,
    val step: Int = 0,
    val maxSteps: Int = MAX_STEPS,
    val tokens: Int = 0,
    val tokensExact: Boolean = false,
    val tokensPerSecond: Int = 0,
    val todos: List<AgentTodo> = emptyList(),
    val pendingQuestion: AgentQuestion? = null,
    val pluginToolCount: Int = 0,
    val history: List<AgentSessionSummary> = emptyList(),
    val historyView: AgentHistoryView? = null,
    val banner: String? = null,
) {
    companion object {
        const val MAX_STEPS = 12
    }
}

private val TODO_STATUSES = setOf("pending", "in_progress", "completed", "cancelled")

class AgentViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var workspace: Workspace? = null
    private var tools: AgentTools? = null
    private val apiHistory = mutableListOf<ChatMessage>()
    private val undoData = mutableMapOf<Long, UndoInfo>()
    private var nextId = 1L
    private var job: Job? = null
    private var currentPlugins: List<PluginManifest> = emptyList()
    private var questionDeferred: CompletableDeferred<String>? = null
    private var sessionId: Long? = null
    private val historyJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    init {
        viewModelScope.launch {
            container.store.apiKey.collect { key ->
                val has = !key.isNullOrBlank()
                _state.update { it.copy(hasKey = has) }
                if (has && _state.value.models.isEmpty() && !_state.value.modelsLoading) {
                    refreshModels()
                }
            }
        }
        viewModelScope.launch {
            container.store.agentModel.collect { saved ->
                if (!saved.isNullOrBlank() && _state.value.selectedModel == null) {
                    _state.update { it.copy(selectedModel = saved) }
                }
            }
        }
        viewModelScope.launch {
            container.store.agentReasoning.collect { effort ->
                _state.update { it.copy(reasoning = effort) }
            }
        }
        viewModelScope.launch {
            val saved = runCatching { container.store.agentTreeUri.first() }.getOrNull()
            if (!saved.isNullOrBlank()) {
                openWorkspace(Uri.parse(saved))
            }
        }
        viewModelScope.launch {
            container.plugins.reload()
        }
        viewModelScope.launch {
            container.agentHistory.observeSessions().collect { list ->
                _state.update { it.copy(history = list) }
            }
        }
        viewModelScope.launch {
            container.plugins.plugins.collect { list ->
                currentPlugins = list
                _state.update {
                    it.copy(
                        pluginToolCount = list.filter { plugin -> plugin.enabled }
                            .sumOf { plugin -> plugin.tools.size },
                    )
                }
            }
        }
        refreshModels()
    }

    fun refreshModels() {
        if (_state.value.hasKey != true) return
        if (_state.value.modelsLoading) return
        viewModelScope.launch {
            _state.update { it.copy(modelsLoading = true) }
            val models = try {
                container.repository.models()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Exception) {
                _state.update {
                    it.copy(modelsLoading = false, banner = NimErrors.friendlyFor(t))
                }
                return@launch
            }
            val chatModels = models.filter { ModelKinds.isLikelyChat(it.id) }
            _state.update { s ->
                val current = s.selectedModel
                val selected = if (current != null && chatModels.any { it.id == current }) {
                    current
                } else {
                    preferredAgentModel(chatModels)
                }
                s.copy(modelsLoading = false, models = chatModels, selectedModel = selected)
            }
        }
    }

    private fun preferredAgentModel(models: List<NimModel>): String? {
        val preferred = listOf(
            "qwen/qwen2.5-coder-32b-instruct",
            "qwen/qwen2.5-coder-7b-instruct",
            "meta/llama-3.3-70b-instruct",
            "nvidia/llama-3.3-nemotron-super-49b-v1",
            "deepseek-ai/deepseek-v3",
            "mistralai/mistral-nemo-12b-instruct",
            "meta/llama-3.1-70b-instruct",
        )
        return preferred.firstOrNull { p -> models.any { it.id == p } } ?: models.firstOrNull()?.id
    }

    fun pickModel(id: String) {
        _state.update { it.copy(selectedModel = id) }
        viewModelScope.launch { container.store.saveAgentModel(id) }
    }

    fun setReasoning(effort: ReasoningEffort) {
        _state.update { it.copy(reasoning = effort) }
        viewModelScope.launch { container.store.saveAgentReasoning(effort) }
    }

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val snapshot = _state.value
        val room = Attachments.MAX_FILES - snapshot.attachments.size
        if (room <= 0) {
            banner("첨부는 최대 ${Attachments.MAX_FILES}개까지 가능합니다")
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
                    banner = error ?: s.banner,
                )
            }
        }
    }

    fun removeAttachment(index: Int) {
        _state.update { s ->
            s.copy(attachments = s.attachments.filterIndexed { i, _ -> i != index })
        }
    }

    fun dismissBanner() {
        _state.update { it.copy(banner = null) }
    }

    fun openWorkspace(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(workspaceLoading = true, banner = null) }
            try {
                val ws = Workspace.open(container.appContext, uri)
                ws.load()
                workspace = ws
                tools = AgentTools(ws)
                apiHistory.clear()
                undoData.clear()
                sessionId = null
                container.store.saveAgentTreeUri(uri.toString())
                _state.update {
                    it.copy(
                        workspaceName = ws.name,
                        workspaceReady = true,
                        workspaceLoading = false,
                        workspaceTruncated = ws.truncated,
                        fileCount = ws.fileCount,
                        messages = emptyList(),
                        attachments = emptyList(),
                        attachmentsLoading = false,
                        todos = emptyList(),
                        tokens = 0,
                        tokensExact = false,
                        tokensPerSecond = 0,
                        banner = null,
                    )
                }
                addNotice(
                    "작업 폴더: ${ws.name} (${ws.fileCount}개 파일" +
                        (if (ws.truncated) ", 일부 생략" else "") + ")",
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Exception) {
                workspace = null
                tools = null
                _state.update {
                    it.copy(
                        workspaceLoading = false,
                        workspaceReady = false,
                        banner = "폴더를 열 수 없습니다: ${t.message}",
                    )
                }
            }
        }
    }

    fun newSession() {
        if (_state.value.running) return
        apiHistory.clear()
        undoData.clear()
        sessionId = null
        _state.update {
            it.copy(
                messages = emptyList(),
                attachments = emptyList(),
                attachmentsLoading = false,
                todos = emptyList(),
                pendingQuestion = null,
                tokens = 0,
                tokensExact = false,
                tokensPerSecond = 0,
                banner = null,
            )
        }
        addNotice("새 세션을 시작했습니다.")
    }

    fun stop() {
        job?.cancel()
    }

    fun answerQuestion(answer: String) {
        val deferred = questionDeferred ?: return
        val clean = answer.trim()
        if (clean.isEmpty()) return
        questionDeferred = null
        _state.update { it.copy(pendingQuestion = null) }
        addMessage(AgentMsg(id = nextId++, kind = "user", text = clean))
        viewModelScope.launch { persist("user", clean) }
        deferred.complete(clean)
    }

    fun undo(id: Long) {
        val info = undoData[id] ?: return
        val ws = workspace ?: return
        viewModelScope.launch {
            val ok = runCatching {
                val previous = info.previousContent
                if (previous == null) {
                    ws.delete(info.path)
                } else {
                    ws.write(info.path, previous)
                }
            }.isSuccess
            if (ok) {
                undoData.remove(id)
                updateMessage(id) { it.copy(canUndo = false, undone = true) }
                addNotice("되돌렸습니다: ${info.path}")
            } else {
                addNotice("되돌리기에 실패했습니다: ${info.path}")
            }
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        val snapshot = _state.value
        val attachments = snapshot.attachments
        if ((prompt.isEmpty() && attachments.isEmpty()) || snapshot.running) return
        if (snapshot.hasKey != true) {
            banner("API 키를 먼저 설정하세요.")
            return
        }
        val ws = workspace
        if (ws == null || !snapshot.workspaceReady) {
            banner("작업 폴더를 먼저 선택하세요.")
            return
        }
        val runner = tools ?: return
        val model = snapshot.selectedModel
        if (model == null) {
            banner("모델을 선택하세요.")
            return
        }

        val displayText = prompt.ifBlank {
            attachments.joinToString(", ") { it.name }
        }
        addMessage(AgentMsg(id = nextId++, kind = "user", text = displayText))
        apiHistory.add(
            ChatMessage(
                role = "user",
                content = Attachments.userContent(prompt, attachments),
            ),
        )
        _state.update {
            it.copy(
                running = true,
                step = 0,
                attachments = emptyList(),
                attachmentsLoading = false,
                banner = null,
            )
        }
        val reasoning = snapshot.reasoning.id

        job = viewModelScope.launch {
            ensureSession(displayText, model, ws.name)
            persist("user", displayText)
            KeepAliveService.begin(container.appContext)
            val coreToolNames = AgentTools.specs.map { it.function.name }.toSet()
            val pluginSpecs = container.plugins.toolSpecs(currentPlugins)
                .filter { it.function.name !in coreToolNames }
            val specs = AgentTools.specs + pluginSpecs
            val rootListing = runCatching { ws.list("") }
                .getOrNull()
                ?.take(40)
                ?.joinToString(", ") { if (it.isDir) it.name + "/" else it.name }
                .orEmpty()
            var completed = false
            var activeCalls: List<AgentToolCall> = emptyList()
            val responded = mutableSetOf<String>()
            try {
                for (step in 1..AgentUiState.MAX_STEPS) {
                    _state.update { it.copy(step = step) }
                    val messages = listOf(
                        ChatMessage(
                            role = "system",
                            content = textContent(systemPrompt(ws, rootListing)),
                        ),
                    ) + apiHistory
                    val promptEstimate = TokenEstimator.estimate(
                        messages.joinToString("\n") { it.content.asMessageText() },
                    )
                    _state.update {
                        it.copy(tokens = promptEstimate, tokensExact = false, tokensPerSecond = 0)
                    }

                    val assistantId = nextId++
                    addMessage(
                        AgentMsg(id = assistantId, kind = "assistant", streaming = true),
                    )
                    val builder = StringBuilder()
                    var calls: List<AgentToolCall> = emptyList()
                    var finishReason: String? = null
                    var lastUi = 0L
                    var firstTokenAt = 0L

                    container.repository.streamAgent(
                        model,
                        messages,
                        specs,
                        reasoningEffort = reasoning,
                    )
                        .collect { event ->
                            when (event) {
                                is AgentStreamEvent.Content -> {
                                    builder.append(event.text)
                                    val now = System.currentTimeMillis()
                                    if (firstTokenAt == 0L) firstTokenAt = now
                                    if (now - lastUi > 40) {
                                        lastUi = now
                                        updateMessage(assistantId) {
                                            it.copy(text = builder.toString())
                                        }
                                        val completion = TokenEstimator.estimate(builder.toString())
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
                                    }
                                }

                                is AgentStreamEvent.Done -> {
                                    calls = event.toolCalls
                                    finishReason = event.finishReason
                                    event.usage?.let { usage ->
                                        val now = System.currentTimeMillis()
                                        val elapsed = if (firstTokenAt > 0L) {
                                            (now - firstTokenAt).coerceAtLeast(1L)
                                        } else {
                                            0L
                                        }
                                        val speed = if (elapsed >= 250L &&
                                            usage.completionTokens > 0
                                        ) {
                                            (usage.completionTokens * 1000.0 / elapsed).toInt()
                                        } else {
                                            0
                                        }
                                        _state.update {
                                            it.copy(
                                                tokens = usage.totalTokens,
                                                tokensExact = true,
                                                tokensPerSecond = speed,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                    val assistantText = builder.toString()
                    updateMessage(assistantId) {
                        it.copy(text = assistantText, streaming = false)
                    }
                    if (assistantText.isNotBlank()) {
                        persist("assistant", assistantText)
                    }
                    apiHistory.add(
                        ChatMessage(
                            role = "assistant",
                            content = textContent(assistantText.ifBlank { null }),
                            toolCalls = calls.ifEmpty { null }?.map { call ->
                                ToolCall(
                                    id = call.id,
                                    type = "function",
                                    function = ToolCallFunction(
                                        name = call.name,
                                        arguments = call.arguments,
                                    ),
                                )
                            },
                        ),
                    )
                    persistApiHistory()

                    if (calls.isEmpty()) {
                        if (finishReason != "stop") {
                            val notice = if (finishReason == "length") {
                                "응답이 토큰 한도에 도달해 잘렸습니다. '계속'을 요청하면 이어서 작성합니다."
                            } else {
                                "연결이 끊겨 응답이 중단되었습니다. 다시 요청하거나 '계속'을 입력하세요."
                            }
                            addNotice(notice)
                            persist("notice", notice)
                        }
                        completed = true
                        break
                    }

                    activeCalls = calls
                    responded.clear()
                    for (call in calls) {
                        val toolId = nextId++
                        addMessage(
                            AgentMsg(
                                id = toolId,
                                kind = "tool",
                                toolName = call.name,
                                toolStatus = "running",
                                streaming = true,
                            ),
                        )
                        val outcome = when (call.name) {
                            "ask_user" -> handleAskUser(call)
                            "todo_write" -> handleTodoWrite(call)
                            else -> {
                                val pluginTool = if (call.name in coreToolNames) {
                                    null
                                } else {
                                    container.plugins.findTool(currentPlugins, call.name)
                                }
                                if (pluginTool != null) {
                                    executePlugin(pluginTool, call)
                                } else {
                                    runner.execute(call)
                                }
                            }
                        }
                        updateMessage(toolId) {
                            it.copy(
                                text = outcome.summary + "\n" + outcome.detail,
                                toolStatus = if (outcome.isError) "error" else "ok",
                                streaming = false,
                                canUndo = outcome.undo != null,
                            )
                        }
                        persist("tool", outcome.summary + "\n" + outcome.detail, call.name)
                        outcome.undo?.let { undoData[toolId] = it }
                        apiHistory.add(
                            ChatMessage(
                                role = "tool",
                                content = textContent(outcome.result),
                                toolCallId = call.id,
                            ),
                        )
                        responded.add(call.id)
                    }
                    persistApiHistory()
                }
                if (!completed) {
                    val notice = "최대 단계(${AgentUiState.MAX_STEPS})에 도달했습니다. " +
                        "계속하려면 다시 요청하세요."
                    addNotice(notice)
                    persist("notice", notice)
                }
            } catch (c: CancellationException) {
                respondMissing(activeCalls, responded)
                withContext(NonCancellable) { persistApiHistory() }
                addNotice("작업을 중지했습니다.")
                persist("notice", "작업을 중지했습니다.")
                throw c
            } catch (t: Exception) {
                respondMissing(activeCalls, responded)
                persistApiHistory()
                val message = NimErrors.friendlyFor(t)
                addMessage(
                    AgentMsg(
                        id = nextId++,
                        kind = "error",
                        text = message,
                    ),
                )
                persist("error", message)
            } finally {
                KeepAliveService.end(container.appContext)
                questionDeferred = null
                _state.update { it.copy(running = false, pendingQuestion = null) }
            }
        }
    }

    private suspend fun handleAskUser(call: AgentToolCall): ToolOutcome {
        val args = parseArgs(call.arguments)
        val question = (args?.get("question") as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "계속 진행할까요?"
        val options = (args?.get("options") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val deferred = CompletableDeferred<String>()
        questionDeferred = deferred
        _state.update { it.copy(pendingQuestion = AgentQuestion(question, options)) }
        try {
            val answer = deferred.await()
            return ToolOutcome(
                summary = "ask_user · 답변",
                detail = answer,
                result = "사용자 답변: $answer",
            )
        } finally {
            questionDeferred = null
            _state.update { it.copy(pendingQuestion = null) }
        }
    }

    private fun handleTodoWrite(call: AgentToolCall): ToolOutcome {
        val args = parseArgs(call.arguments)
        val array = args?.get("todos") as? JsonArray
            ?: return ToolOutcome(
                summary = "todo_write · 오류",
                detail = "todos 배열이 필요합니다",
                result = "ERROR: todos 배열이 필요합니다",
                isError = true,
            )
        val todos = array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val content = (obj["content"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "t${index + 1}"
            val status = (obj["status"] as? JsonPrimitive)?.contentOrNull?.lowercase()
                ?.takeIf { it in TODO_STATUSES } ?: "pending"
            AgentTodo(id, content, status)
        }
        _state.update { it.copy(todos = todos) }
        val done = todos.count { it.status == "completed" }
        val body = todos.joinToString("\n") { todo ->
            val mark = when (todo.status) {
                "completed" -> "[x]"
                "in_progress" -> "[>]"
                "cancelled" -> "[-]"
                else -> "[ ]"
            }
            "$mark ${todo.content}"
        }.ifBlank { "(빈 목록)" }
        return ToolOutcome(
            summary = "todo_write · $done/${todos.size} 완료",
            detail = body,
            result = "Todo list updated ($done/${todos.size})\n$body",
        )
    }

    private suspend fun executePlugin(tool: PluginTool, call: AgentToolCall): ToolOutcome {
        val args = parseArgs(call.arguments) ?: JsonObject(emptyMap())
        return try {
            val body = container.plugins.execute(tool, args)
            ToolOutcome(
                summary = "${call.name} · 완료",
                detail = body.take(1500),
                result = body,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            ToolOutcome(
                summary = "${call.name} · 오류",
                detail = t.message ?: "플러그인 실행 실패",
                result = "ERROR: ${t.message ?: "플러그인 실행 실패"}",
                isError = true,
            )
        }
    }

    private fun parseArgs(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject }.getOrNull()

    private fun respondMissing(calls: List<AgentToolCall>, responded: Set<String>) {
        calls.filter { it.id !in responded }.forEach { call ->
            apiHistory.add(
                ChatMessage(
                    role = "tool",
                    content = textContent("ERROR: 작업이 중단되었습니다"),
                    toolCallId = call.id,
                ),
            )
        }
    }

    private fun systemPrompt(workspace: Workspace, rootListing: String): String = buildString {
        appendLine("당신은 NIM Studio의 코딩 에이전트입니다. Android 기기의 SAF로 허용된 프로젝트 폴더에서 파일을 읽고 수정합니다.")
        appendLine("작업 폴더: ${workspace.name}")
        if (rootListing.isNotBlank()) appendLine("최상위 항목: $rootListing")
        appendLine("규칙:")
        appendLine("- 경로는 항상 작업 폴더 기준 상대 경로로 쓰세요 (예: app/src/main/java/.../Main.kt).")
        appendLine("- 수정 전에 반드시 read_file로 현재 내용을 확인하세요.")
        appendLine("- 부분 수정은 edit_file(정확히 일치하는 old_string), 새 파일/전체 교체는 write_file을 쓰세요.")
        appendLine("- 여러 단계 작업은 todo_write로 계획을 세우고, 진행할 때마다 전체 목록을 갱신하세요.")
        appendLine("- 사용자 결정이 필요하면 ask_user로 질문하세요. 선택지를 options에 넣으면 버튼으로 표시됩니다.")
        appendLine("- 셸/터미널은 없습니다. 빌드·실행은 사용자가 Termux에서 직접 합니다.")
        appendLine("- 요청이 모호하면 한 번만 짧게 되물어보세요.")
        appendLine("- 답변은 한국어로 간결하게. 작업 후 바꾼 파일을 요약하세요.")
        appendLine("- .git, build, node_modules 등 보호된 폴더는 수정할 수 없습니다.")
    }

    fun openHistory(id: Long) {
        viewModelScope.launch {
            val session = _state.value.history.firstOrNull { it.id == id } ?: return@launch
            val messages = runCatching { container.agentHistory.messages(id) }
                .getOrDefault(emptyList())
                .map { entity ->
                    AgentMsg(
                        id = entity.id,
                        kind = entity.kind,
                        text = entity.text,
                        toolName = entity.toolName,
                        toolStatus = if (entity.kind == "tool") "ok" else null,
                    )
                }
            _state.update { it.copy(historyView = AgentHistoryView(session, messages)) }
        }
    }

    fun closeHistoryView() {
        _state.update { it.copy(historyView = null) }
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch {
            runCatching { container.agentHistory.delete(id) }
            if (_state.value.historyView?.session?.id == id) {
                _state.update { it.copy(historyView = null) }
            }
        }
    }

    fun resumeSession(id: Long) {
        if (_state.value.running) return
        viewModelScope.launch {
            val session = container.agentHistory.session(id) ?: return@launch
            val uri = session.workspaceUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (uri != null && uri != workspace?.treeUri) {
                _state.update { it.copy(workspaceLoading = true, banner = null) }
                try {
                    val ws = Workspace.open(container.appContext, uri)
                    ws.load()
                    workspace = ws
                    tools = AgentTools(ws)
                    container.store.saveAgentTreeUri(uri.toString())
                    _state.update {
                        it.copy(
                            workspaceName = ws.name,
                            workspaceReady = true,
                            workspaceLoading = false,
                            workspaceTruncated = ws.truncated,
                            fileCount = ws.fileCount,
                        )
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Exception) {
                    _state.update {
                        it.copy(
                            workspaceLoading = false,
                            banner = "세션의 작업 폴더를 열 수 없습니다: ${t.message}",
                        )
                    }
                    return@launch
                }
            } else if (workspace == null) {
                banner("세션을 이어가려면 먼저 작업 폴더를 선택하세요.")
                return@launch
            }

            apiHistory.clear()
            val stored = session.apiJson?.let {
                runCatching { historyJson.decodeFromString<List<ChatMessage>>(it) }.getOrNull()
            }
            if (stored != null) apiHistory.addAll(stored)
            undoData.clear()
            sessionId = id
            val msgs = container.agentHistory.messages(id).map { entity ->
                AgentMsg(
                    id = entity.id,
                    kind = entity.kind,
                    text = entity.text,
                    toolName = entity.toolName,
                    toolStatus = if (entity.kind == "tool") "ok" else null,
                )
            }
            if (apiHistory.isEmpty()) {
                msgs.filter { it.kind == "user" || it.kind == "assistant" }
                    .filter { it.text.isNotBlank() }
                    .forEach { apiHistory.add(ChatMessage(it.kind, textContent(it.text))) }
            }
            nextId = maxOf(nextId, (msgs.maxOfOrNull { it.id } ?: 0L) + 1)
            _state.update {
                it.copy(
                    messages = msgs,
                    attachments = emptyList(),
                    attachmentsLoading = false,
                    todos = emptyList(),
                    pendingQuestion = null,
                    tokens = 0,
                    tokensExact = false,
                    tokensPerSecond = 0,
                    selectedModel = session.model,
                    historyView = null,
                    banner = null,
                )
            }
            container.store.saveAgentModel(session.model)
            addNotice("세션을 이어갑니다: ${session.title}")
        }
    }

    private suspend fun ensureSession(title: String, model: String, workspaceName: String) {
        if (sessionId != null) return
        try {
            sessionId = container.agentHistory.createSession(
                title = title.replace('\n', ' ').take(80),
                model = model,
                workspaceName = workspaceName,
                workspaceUri = workspace?.treeUri?.toString(),
            )
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
        }
    }

    private suspend fun persistApiHistory() {
        val id = sessionId ?: return
        val sanitized = apiHistory.map { message ->
            ChatMessage(
                role = message.role,
                content = sanitizeContent(message.content),
                toolCalls = message.toolCalls,
                toolCallId = message.toolCallId,
            )
        }
        try {
            container.agentHistory.saveApiHistory(id, historyJson.encodeToString(sanitized))
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
        }
    }

    private fun sanitizeContent(content: JsonElement?): JsonElement? {
        val array = content as? JsonArray ?: return content
        return JsonArray(
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull element
                if (obj["type"]?.jsonPrimitive?.contentOrNull == "image_url") {
                    buildJsonObject {
                        put("type", "text")
                        put("text", "[이미지 첨부]")
                    }
                } else {
                    obj
                }
            },
        )
    }

    private suspend fun persist(kind: String, text: String, toolName: String? = null) {
        val id = sessionId ?: return
        try {
            container.agentHistory.append(id, kind, text, toolName)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
        }
    }

    private fun addMessage(msg: AgentMsg) {
        _state.update { it.copy(messages = it.messages + msg) }
    }

    private fun addNotice(text: String) {
        addMessage(AgentMsg(id = nextId++, kind = "notice", text = text))
    }

    private fun updateMessage(id: Long, transform: (AgentMsg) -> AgentMsg) {
        _state.update { s ->
            s.copy(messages = s.messages.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun banner(text: String) {
        _state.update { it.copy(banner = text) }
    }
}
