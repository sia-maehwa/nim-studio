package com.siamaehwa.nimstudio.ui.agent

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siamaehwa.nimstudio.AppContainer
import com.siamaehwa.nimstudio.KeepAliveService
import com.siamaehwa.nimstudio.core.AgentPermission
import com.siamaehwa.nimstudio.core.AgentStreamEvent
import com.siamaehwa.nimstudio.core.AgentToolCall
import com.siamaehwa.nimstudio.core.AttachmentContent
import com.siamaehwa.nimstudio.core.Attachments
import com.siamaehwa.nimstudio.core.ChatMessage
import com.siamaehwa.nimstudio.core.ClockInfo
import com.siamaehwa.nimstudio.core.ModelKinds
import com.siamaehwa.nimstudio.core.NimErrors
import com.siamaehwa.nimstudio.core.NimModel
import com.siamaehwa.nimstudio.core.Notifier
import com.siamaehwa.nimstudio.core.ReasoningEffort
import com.siamaehwa.nimstudio.core.ShellAccess
import com.siamaehwa.nimstudio.core.TokenEstimator
import com.siamaehwa.nimstudio.core.ToolCall
import com.siamaehwa.nimstudio.core.ToolCallFunction
import com.siamaehwa.nimstudio.core.agent.AgentTools
import com.siamaehwa.nimstudio.core.agent.ShellRunner
import com.siamaehwa.nimstudio.core.agent.ToolOutcome
import com.siamaehwa.nimstudio.core.agent.UndoInfo
import com.siamaehwa.nimstudio.core.agent.Workspace
import com.siamaehwa.nimstudio.core.asMessageText
import com.siamaehwa.nimstudio.core.web.WebSearch
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

data class AgentPermissionRequest(
    val toolName: String,
    val summary: String,
)

data class AgentMsg(
    val id: Long,
    val kind: String,
    val text: String = "",
    val reasoning: String = "",
    val streaming: Boolean = false,
    val toolName: String? = null,
    val toolStatus: String? = null,
    val canUndo: Boolean = false,
    val undone: Boolean = false,
    val attachments: List<AttachmentContent> = emptyList(),
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
    val permission: AgentPermission = AgentPermission.AutoReview,
    val messages: List<AgentMsg> = emptyList(),
    val attachments: List<AttachmentContent> = emptyList(),
    val attachmentsLoading: Boolean = false,
    val running: Boolean = false,
    val step: Int = 0,
    val tokens: Int = 0,
    val tokensExact: Boolean = false,
    val tokensPerSecond: Int = 0,
    val todos: List<AgentTodo> = emptyList(),
    val pendingQuestion: AgentQuestion? = null,
    val pendingPermission: AgentPermissionRequest? = null,
    val pluginToolCount: Int = 0,
    val history: List<AgentSessionSummary> = emptyList(),
    val historyView: AgentHistoryView? = null,
    val banner: String? = null,
)

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
    private var permissionDeferred: CompletableDeferred<Boolean>? = null
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
            container.store.agentPermission.collect { permission ->
                _state.update { it.copy(permission = permission) }
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

    fun setPermission(permission: AgentPermission) {
        _state.update { it.copy(permission = permission) }
        viewModelScope.launch { container.store.saveAgentPermission(permission) }
    }

    fun answerPermission(allow: Boolean) {
        val deferred = permissionDeferred ?: return
        permissionDeferred = null
        _state.update { it.copy(pendingPermission = null) }
        addNotice(if (allow) "실행을 허용했습니다" else "실행을 거부했습니다")
        deferred.complete(allow)
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
            val hasImage = loaded.any { it is AttachmentContent.Image }
            _state.update { s ->
                s.copy(
                    attachments = (s.attachments + loaded).take(Attachments.MAX_FILES),
                    attachmentsLoading = false,
                    banner = error ?: s.banner,
                )
            }
            if (error == null && hasImage) {
                addNotice("이미지 첨부는 비전(VL) 모델에서만 인식됩니다")
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
                val terminal = if (
                    ws.fsPath != null && ShellAccess.hasStorageAccess(container.appContext)
                ) {
                    " · 터미널 사용 가능"
                } else {
                    ""
                }
                addNotice(
                    "작업 폴더: ${ws.name} (${ws.fileCount}개 파일" +
                        (if (ws.truncated) ", 일부 생략" else "") + ")$terminal",
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
                pendingPermission = null,
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
        addMessage(
            AgentMsg(id = nextId++, kind = "user", text = displayText, attachments = attachments),
        )
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
            var autoContinues = 0
            var step = 0
            var activeCalls: List<AgentToolCall> = emptyList()
            val responded = mutableSetOf<String>()
            try {
                while (true) {
                    step++
                    _state.update { it.copy(step = step) }
                    val systemMessage = ChatMessage(
                        role = "system",
                        content = textContent(
                            systemPrompt(ws, rootListing, shellAvailable()),
                        ),
                    )
                    var historyEstimate = estimateHistory(systemMessage)
                    if (historyEstimate > CONTEXT_COMPACT_TOKENS) {
                        if (compactHistory(model, reasoning)) {
                            historyEstimate = estimateHistory(systemMessage)
                        }
                    }
                    val messages = listOf(systemMessage) + apiHistory
                    val promptEstimate = historyEstimate
                    _state.update {
                        it.copy(tokens = promptEstimate, tokensExact = false, tokensPerSecond = 0)
                    }

                    val assistantId = nextId++
                    addMessage(
                        AgentMsg(id = assistantId, kind = "assistant", streaming = true),
                    )
                    val builder = StringBuilder()
                    val reasoningBuilder = StringBuilder()
                    var completionEstimate = 0
                    var calls: List<AgentToolCall> = emptyList()
                    var finishReason: String? = null
                    var lastUi = 0L
                    var firstTokenAt = 0L

                    fun elapsedMs(now: Long): Long =
                        if (firstTokenAt > 0L) (now - firstTokenAt).coerceAtLeast(1L) else 0L

                    fun refreshLive(now: Long, force: Boolean) {
                        if (!force && now - lastUi <= 40) return
                        lastUi = now
                        updateMessage(assistantId) {
                            it.copy(
                                text = builder.toString(),
                                reasoning = reasoningBuilder.toString(),
                            )
                        }
                        val elapsed = elapsedMs(now)
                        val speed = if (elapsed >= 250L) {
                            (completionEstimate * 1000.0 / elapsed).toInt()
                        } else {
                            0
                        }
                        _state.update {
                            it.copy(
                                tokens = promptEstimate + completionEstimate,
                                tokensExact = false,
                                tokensPerSecond = speed,
                            )
                        }
                    }

                    container.repository.streamAgent(
                        model,
                        messages,
                        specs,
                        reasoningEffort = reasoning,
                        maxTokens = MAX_AGENT_TOKENS,
                    )
                        .collect { event ->
                            when (event) {
                                is AgentStreamEvent.Content -> {
                                    builder.append(event.text)
                                    completionEstimate += TokenEstimator.estimate(event.text)
                                    val now = System.currentTimeMillis()
                                    if (firstTokenAt == 0L) firstTokenAt = now
                                    refreshLive(now, force = false)
                                }

                                is AgentStreamEvent.Reasoning -> {
                                    reasoningBuilder.append(event.text)
                                    completionEstimate += TokenEstimator.estimate(event.text)
                                    val now = System.currentTimeMillis()
                                    if (firstTokenAt == 0L) firstTokenAt = now
                                    refreshLive(now, force = false)
                                }

                                is AgentStreamEvent.Notice -> addNotice(event.text)

                                is AgentStreamEvent.Done -> {
                                    calls = event.toolCalls
                                    finishReason = event.finishReason
                                    event.usage?.let { usage ->
                                        val now = System.currentTimeMillis()
                                        val elapsed = elapsedMs(now)
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
                    val reasoningText = reasoningBuilder.toString()
                    refreshLive(System.currentTimeMillis(), force = true)
                    updateMessage(assistantId) {
                        it.copy(
                            text = assistantText,
                            reasoning = reasoningText,
                            streaming = false,
                        )
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
                        if ((finishReason == "length" || finishReason == "timeout") &&
                            assistantText.isNotBlank() &&
                            autoContinues < MAX_AUTO_CONTINUES
                        ) {
                            autoContinues++
                            apiHistory.add(
                                ChatMessage(
                                    role = "user",
                                    content = textContent(CONTINUE_INSTRUCTION),
                                ),
                            )
                            persistApiHistory()
                            val notice = "토큰 한도로 잘려 자동으로 이어서 생성합니다 " +
                                "($autoContinues/$MAX_AUTO_CONTINUES)"
                            addNotice(notice)
                            persist("notice", notice)
                            continue
                        }
                        if (finishReason != "stop") {
                            val notice = if (finishReason == "length") {
                                "자동 이어쓰기 한도(${MAX_AUTO_CONTINUES}회)에 도달했습니다. " +
                                    "'계속'을 입력하면 더 이어갑니다."
                            } else {
                                "연결이 끊겨 응답이 중단되었습니다. 다시 요청하거나 '계속'을 입력하세요."
                            }
                            addNotice(notice)
                            persist("notice", notice)
                        }
                        Notifier.completed(
                            container.appContext,
                            "작업 완료: ${displayText.take(60)}",
                        )
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
                        val pluginTool = if (call.name in coreToolNames) {
                            null
                        } else {
                            container.plugins.findTool(currentPlugins, call.name)
                        }
                        val gated = call.name in GATED_TOOLS || pluginTool != null
                        val permissionMode = _state.value.permission
                        val outcome = when {
                            call.name == "run_command" -> handleRunCommand(call)
                            call.name == "web_search" -> handleWebSearch(call)
                            call.name == "ask_user" -> handleAskUser(call)
                            call.name == "todo_write" -> handleTodoWrite(call)
                            gated && permissionMode == AgentPermission.ReadOnly -> ToolOutcome(
                                summary = "${call.name} · 차단됨",
                                detail = "읽기 전용 모드에서는 실행할 수 없습니다",
                                result = "ERROR: 읽기 전용 모드입니다. 파일 수정·플러그인 실행은 허용되지 않습니다.",
                                isError = true,
                            )

                            gated && permissionMode == AgentPermission.AutoReview -> {
                                if (requestPermission(call)) {
                                    if (pluginTool != null) {
                                        executePlugin(pluginTool, call)
                                    } else {
                                        runner.execute(call)
                                    }
                                } else {
                                    ToolOutcome(
                                        summary = "${call.name} · 거부됨",
                                        detail = "사용자가 실행을 거부했습니다",
                                        result = "ERROR: 사용자가 실행을 거부했습니다. 다른 방법을 제안하세요.",
                                        isError = true,
                                    )
                                }
                            }

                            pluginTool != null -> executePlugin(pluginTool, call)
                            else -> runner.execute(call)
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
                Notifier.completed(container.appContext, "작업 중단: $message")
            } finally {
                KeepAliveService.end(container.appContext)
                questionDeferred = null
                permissionDeferred = null
                _state.update {
                    it.copy(
                        running = false,
                        pendingQuestion = null,
                        pendingPermission = null,
                    )
                }
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
        Notifier.question(container.appContext, question)
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

    private fun shellAvailable(): Boolean =
        workspace?.fsPath != null && ShellAccess.hasStorageAccess(container.appContext)

    private suspend fun handleRunCommand(call: AgentToolCall): ToolOutcome {
        val args = parseArgs(call.arguments)
        val command = (args?.get("command") as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolOutcome(
                summary = "run_command · 오류",
                detail = "command가 필요합니다",
                result = "ERROR: command 파라미터가 필요합니다",
                isError = true,
            )
        val dir = workspace?.fsPath
            ?: return ToolOutcome(
                summary = "run_command · 불가",
                detail = "이 작업 폴더는 절대 경로로 변환할 수 없어 셸을 실행할 수 없습니다",
                result = "ERROR: 이 폴더에서는 명령 실행을 지원하지 않습니다. " +
                    "기기 저장소(내부 저장소) 폴더를 선택하세요.",
                isError = true,
            )
        if (!ShellAccess.hasStorageAccess(container.appContext)) {
            return ToolOutcome(
                summary = "run_command · 권한 필요",
                detail = "설정에서 '터미널 실행' 권한을 허용해야 합니다",
                result = "ERROR: 셸 실행 권한이 없습니다. 설정 → 터미널 실행에서 " +
                    "전체 파일 접근을 허용하세요.",
                isError = true,
            )
        }
        return try {
            val result = ShellRunner.run(dir, command)
            ToolOutcome(
                summary = "run_command · exit ${result.exitCode}",
                detail = result.output.take(1500).ifBlank { "(출력 없음)" },
                result = buildString {
                    append("exit code: ").append(result.exitCode).append('\n')
                    append(result.output)
                },
                isError = result.exitCode != 0,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            ToolOutcome(
                summary = "run_command · 오류",
                detail = t.message ?: "명령 실행 실패",
                result = "ERROR: ${t.message ?: "명령 실행 실패"}",
                isError = true,
            )
        }
    }

    private suspend fun handleWebSearch(call: AgentToolCall): ToolOutcome {
        val args = parseArgs(call.arguments)
        val query = (args?.get("query") as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolOutcome(
                summary = "web_search · 오류",
                detail = "query가 필요합니다",
                result = "ERROR: query 파라미터가 필요합니다",
                isError = true,
            )
        return try {
            val results = WebSearch.search(query)
            if (results.isEmpty()) {
                ToolOutcome(
                    summary = "web_search · 결과 없음",
                    detail = "검색 결과가 없습니다: $query",
                    result = "웹 검색 결과가 없습니다.",
                )
            } else {
                val formatted = WebSearch.format(results, query)
                ToolOutcome(
                    summary = "web_search · ${results.size}건",
                    detail = results.joinToString("\n") {
                        "${it.title} — ${it.snippet}"
                    }.take(1500),
                    result = formatted.take(6000),
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            ToolOutcome(
                summary = "web_search · 오류",
                detail = t.message ?: "웹 검색 실패",
                result = "ERROR: ${t.message ?: "웹 검색 실패"}",
                isError = true,
            )
        }
    }

    private suspend fun requestPermission(call: AgentToolCall): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        permissionDeferred = deferred
        val summary = call.arguments.take(300).ifBlank { "{}" }
        _state.update {
            it.copy(pendingPermission = AgentPermissionRequest(call.name, summary))
        }
        Notifier.permission(
            container.appContext,
            "${call.name} 실행 확인\n${summary.take(120)}",
        )
        return try {
            deferred.await()
        } finally {
            permissionDeferred = null
            _state.update { it.copy(pendingPermission = null) }
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

    private fun systemPrompt(
        workspace: Workspace,
        rootListing: String,
        shellAvailable: Boolean,
    ): String = buildString {
        appendLine("당신은 NIM Studio의 코딩 에이전트입니다. Android 기기의 SAF로 허용된 프로젝트 폴더에서 파일을 읽고 수정합니다.")
        appendLine("오늘 날짜: ${ClockInfo.todayLabel()}")
        appendLine("작업 폴더: ${workspace.name}")
        if (rootListing.isNotBlank()) appendLine("최상위 항목: $rootListing")
        appendLine("규칙:")
        appendLine("- 경로는 항상 작업 폴더 기준 상대 경로로 쓰세요 (예: app/src/main/java/.../Main.kt).")
        appendLine("- 수정 전에 반드시 read_file로 현재 내용을 확인하세요.")
        appendLine("- 부분 수정은 edit_file(정확히 일치하는 old_string), 새 파일/전체 교체는 write_file을 쓰세요.")
        appendLine("- 여러 단계 작업은 todo_write로 계획을 세우고, 진행할 때마다 전체 목록을 갱신하세요.")
        appendLine("- 최신 뉴스·시사·근황처럼 지식 컷오프 이후 정보가 필요하면 web_search 도구로 검색하세요.")
        appendLine("- 사용자 결정이 필요하면 ask_user로 질문하세요. 선택지를 options에 넣으면 버튼으로 표시됩니다.")
        if (shellAvailable) {
            appendLine(
                "- run_command로 셸 명령을 실행할 수 있습니다(Android 기본 toybox: ls, grep, " +
                    "sed, find, head, wc 등). JDK·git·node는 없어 빌드/컴파일은 불가합니다.",
            )
            appendLine("- 셸로 파일을 고치기보다 read_file/edit_file/write_file 도구를 우선 사용하세요.")
        } else {
            appendLine("- 셸/터미널을 쓸 수 없습니다. 빌드·실행은 사용자가 Termux에서 직접 합니다.")
        }
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
                    pendingPermission = null,
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

    private fun estimateHistory(systemMessage: ChatMessage): Int =
        TokenEstimator.estimate(
            (listOf(systemMessage) + apiHistory).joinToString("\n") {
                it.content.asMessageText()
            },
        )

    private suspend fun compactHistory(model: String, reasoningEffort: String?): Boolean {
        if (apiHistory.size <= KEEP_RECENT_MESSAGES + 2) return false
        var split = (apiHistory.size - KEEP_RECENT_MESSAGES).coerceAtLeast(0)
        while (split < apiHistory.size && apiHistory[split].role != "user") split++
        if (split >= apiHistory.size || split <= 0) return false

        val old = apiHistory.take(split)
        val recent = apiHistory.drop(split)
        val serialized = serializeForSummary(old)
        if (serialized.isBlank()) return false

        val summary = try {
            container.repository.complete(
                model = model,
                messages = listOf(
                    ChatMessage("system", textContent(SUMMARY_SYSTEM_PROMPT)),
                    ChatMessage("user", textContent("다음 기록을 요약하세요:\n\n$serialized")),
                ),
                maxTokens = SUMMARY_MAX_TOKENS,
                temperature = 0.2,
                reasoningEffort = reasoningEffort,
            ).trim()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            return false
        }
        if (summary.isBlank()) return false

        val firstUser = old.firstOrNull { it.role == "user" }
        apiHistory.clear()
        if (firstUser != null) apiHistory.add(firstUser)
        apiHistory.add(ChatMessage("user", textContent("[이전 대화 요약]\n$summary")))
        apiHistory.addAll(recent)

        persistApiHistory()
        val notice = "컨텍스트 압축: 이전 기록 ${old.size}건을 요약했습니다"
        addNotice(notice)
        persist("notice", notice)
        return true
    }

    private fun serializeForSummary(messages: List<ChatMessage>): String =
        messages.joinToString("\n\n") { message ->
            val text = message.content.asMessageText()
            when {
                message.role == "assistant" && message.toolCalls != null ->
                    buildString {
                        append("assistant(도구 호출): ")
                        append(
                            message.toolCalls.joinToString(", ") { call ->
                                "${call.function.name} ${call.function.arguments.take(200)}"
                            },
                        )
                        if (text.isNotBlank()) append("\n").append(text.take(400))
                    }

                message.role == "tool" -> "tool: ${text.take(400)}"
                else -> "${message.role}: ${text.take(2000)}"
            }
        }.take(60_000)

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
            var encoded = historyJson.encodeToString(sanitized)
            if (encoded.length > MAX_API_JSON_CHARS) {
                encoded = historyJson.encodeToString(sanitized.takeLast(24))
            }
            container.agentHistory.saveApiHistory(id, encoded)
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

    private companion object {
        const val MAX_API_JSON_CHARS = 400_000
        const val MAX_AGENT_TOKENS = 16384
        const val MAX_AUTO_CONTINUES = 4
        const val CONTEXT_COMPACT_TOKENS = 20_000
        const val KEEP_RECENT_MESSAGES = 10
        const val SUMMARY_MAX_TOKENS = 1024
        const val SUMMARY_SYSTEM_PROMPT =
            "당신은 코딩 에이전트 세션 기록을 압축하는 역할입니다. " +
                "사용자의 목표, 지금까지 한 일, 수정·생성한 파일 경로, 내린 결정, " +
                "해결된 오류, 남은 작업을 빠뜨리지 말고 한국어로 간결하게 요약하세요. " +
                "도구 호출 결과의 핵심만 남기고 원문 전체를 옮기지 마세요."
        const val CONTINUE_INSTRUCTION =
            "이어서 계속 작성해 주세요. 앞부분을 반복하지 말고 중단된 지점부터 " +
                "바로 이어서 쓰세요."
        val GATED_TOOLS = setOf("write_file", "edit_file", "run_command")
    }
}
