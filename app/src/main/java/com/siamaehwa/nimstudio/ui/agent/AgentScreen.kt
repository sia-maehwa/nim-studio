@file:OptIn(ExperimentalMaterial3Api::class)

package com.siamaehwa.nimstudio.ui.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.siamaehwa.nimstudio.NimStudioApp
import com.siamaehwa.nimstudio.core.AgentPermission
import com.siamaehwa.nimstudio.core.AttachmentContent
import com.siamaehwa.nimstudio.core.NimModel
import com.siamaehwa.nimstudio.core.ReasoningEffort
import com.siamaehwa.nimstudio.core.db.AgentSessionSummary
import com.siamaehwa.nimstudio.ui.common.AttachmentChips
import com.siamaehwa.nimstudio.ui.common.AttachmentPreviews
import com.siamaehwa.nimstudio.ui.common.EmptyHint
import com.siamaehwa.nimstudio.ui.common.ReasoningChips
import com.siamaehwa.nimstudio.ui.common.RenderedMarkdown
import com.siamaehwa.nimstudio.ui.common.ScreenHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AgentScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NimStudioApp
    val vm: AgentViewModel = viewModel { AgentViewModel(app.container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) vm.openWorkspace(uri)
    }

    val attachPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.addAttachments(uris)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "에이전트",
            subtitle = buildString {
                append(
                    state.workspaceName
                        ?: if (state.workspaceLoading) "폴더 분석 중…" else "작업 폴더를 선택하세요",
                )
                if (state.tokens > 0) {
                    append(" · ")
                    append(tokenLabel(state.tokens, state.tokensExact, state.tokensPerSecond))
                }
            },
        ) {
            IconButton(onClick = { showHistory = true }) {
                Icon(Icons.Filled.History, contentDescription = "에이전트 기록")
            }
            IconButton(onClick = { folderPicker.launch(null) }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "작업 폴더 선택")
            }
            AgentPermissionPicker(
                permission = state.permission,
                onSelect = vm::setPermission,
            )
            AgentModelPicker(
                models = state.models,
                selected = state.selectedModel,
                loading = state.modelsLoading,
                reasoning = state.reasoning,
                onReasoning = vm::setReasoning,
                onPick = vm::pickModel,
                onRefresh = vm::refreshModels,
            )
            TextButton(onClick = vm::newSession, enabled = !state.running) {
                Text("새 세션")
            }
        }

        val banner = state.banner
        if (banner != null) {
            BannerBar(banner, onDismiss = vm::dismissBanner)
        }

        when {
            state.hasKey == false -> EmptyHint(
                icon = Icons.Filled.Lock,
                title = "API 키가 필요합니다",
                body = "NVIDIA NIM API 키(nvapi-)를 등록하면 코딩 에이전트를 사용할 수 있습니다.",
                actionLabel = "설정 열기",
                onAction = onOpenSettings,
                modifier = Modifier.weight(1f),
            )

            !state.workspaceReady -> {
                if (state.workspaceLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    EmptyHint(
                        icon = Icons.Filled.FolderOpen,
                        title = "프로젝트 폴더 선택",
                        body = "에이전트가 읽고 고칠 폴더를 한 번만 허용하세요. " +
                            "예: /AI_Work/nim-studio. 파일은 기기 안에서만 다뤄지고, " +
                            "빌드·실행은 Termux에서 합니다.",
                        actionLabel = "폴더 선택",
                        onAction = { folderPicker.launch(null) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            else -> {
                val messages = state.messages
                val lastTextLength = messages.lastOrNull()?.text?.length ?: 0
                val lastReasoningLength = messages.lastOrNull()?.reasoning?.length ?: 0
                LaunchedEffect(
                    messages.size,
                    lastTextLength,
                    lastReasoningLength,
                    state.pendingQuestion,
                    state.pendingPermission,
                ) {
                    if (messages.isNotEmpty()) {
                        listState.scrollToItem(messages.lastIndex)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items = messages, key = { it.id }) { msg ->
                        AgentMessageItem(msg = msg, onUndo = { vm.undo(msg.id) })
                    }
                }

                if (state.running) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "작업 중… 단계 ${state.step}/${state.maxSteps}" +
                                if (state.tokens > 0) {
                                    " · ${tokenLabel(state.tokens, state.tokensExact, state.tokensPerSecond)}"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.todos.isNotEmpty()) {
                    TodoPanel(todos = state.todos)
                }

                val permissionRequest = state.pendingPermission
                val question = state.pendingQuestion
                if (permissionRequest != null) {
                    PermissionCard(
                        request = permissionRequest,
                        onAllow = { vm.answerPermission(true) },
                        onDeny = { vm.answerPermission(false) },
                    )
                } else if (question != null) {
                    QuestionCard(
                        question = question,
                        onAnswer = vm::answerQuestion,
                        onCancel = vm::stop,
                    )
                } else {
                    AgentInputBar(
                        draft = draft,
                        onDraftChange = { draft = it },
                        running = state.running,
                        attachments = state.attachments,
                        attachmentsLoading = state.attachmentsLoading,
                        onAttach = { attachPicker.launch(arrayOf("*/*")) },
                        onRemoveAttachment = vm::removeAttachment,
                        onSend = {
                            val text = draft
                            draft = ""
                            vm.send(text)
                        },
                        onStop = vm::stop,
                    )
                }
            }
        }
    }

    if (showHistory) {
        AgentHistorySheet(
            sessions = state.history,
            view = state.historyView,
            onOpen = vm::openHistory,
            onCloseView = vm::closeHistoryView,
            onDelete = vm::deleteHistory,
            onResume = { id ->
                showHistory = false
                vm.resumeSession(id)
            },
            onDismiss = {
                showHistory = false
                vm.closeHistoryView()
            },
        )
    }
}

private val historyTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM.dd HH:mm")

private fun formatHistoryTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(historyTimeFormat)

@Composable
private fun AgentHistorySheet(
    sessions: List<AgentSessionSummary>,
    view: AgentHistoryView?,
    onOpen: (Long) -> Unit,
    onCloseView: () -> Unit,
    onDelete: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (view != null) {
                    TextButton(onClick = onCloseView) { Text("뒤로") }
                }
                Text(
                    view?.session?.title ?: "에이전트 기록",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("닫기") }
            }

            if (view != null) {
                Text(
                    "${view.session.model} · ${view.session.workspaceName} · " +
                        formatHistoryTime(view.session.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(
                    onClick = { onResume(view.session.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("이 세션 이어서 작업")
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items = view.messages, key = { it.id }) { msg ->
                        HistoryMessageItem(msg)
                    }
                }
            } else if (sessions.isEmpty()) {
                Text(
                    "저장된 기록이 없습니다. 에이전트에게 요청하면 자동으로 기록됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                ) {
                    items(items = sessions, key = { it.id }) { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(session.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    session.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${session.model} · ${session.workspaceName} · " +
                                        "${formatHistoryTime(session.updatedAt)} · " +
                                        "${session.messageCount}개 메시지",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onDelete(session.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "기록 삭제")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryMessageItem(msg: AgentMsg) {
    when (msg.kind) {
        "user" -> Column {
            Text(
                "나",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            SelectionContainer {
                Text(msg.text, style = MaterialTheme.typography.bodyMedium)
            }
        }

        "assistant" -> RenderedMarkdown(msg.text)

        "tool" -> Column {
            Text(
                "도구 · ${msg.toolName ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                msg.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        "error" -> Text(
            msg.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        else -> Text(
            msg.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentMessageItem(msg: AgentMsg, onUndo: () -> Unit) {
    when (msg.kind) {
        "user" -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (msg.attachments.isNotEmpty()) {
                        AttachmentPreviews(
                            attachments = msg.attachments,
                            thumbnailSize = 130.dp,
                        )
                        if (msg.text.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    if (msg.text.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }

        "assistant" -> Column(modifier = Modifier.fillMaxWidth()) {
            if (msg.reasoning.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            if (msg.streaming) "생각 과정 (진행 중)" else "생각 과정",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            msg.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (msg.text.isNotBlank()) {
                if (msg.streaming) {
                    Text(msg.text, style = MaterialTheme.typography.bodyLarge)
                } else {
                    RenderedMarkdown(msg.text)
                }
            }
            if (msg.streaming) {
                if (msg.text.isBlank()) {
                    Text(
                        "생각 중…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BlinkingCursor()
            }
        }

        "tool" -> ToolCard(msg = msg, onUndo = onUndo)

        "error" -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        else -> Text(
            msg.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun ToolCard(msg: AgentMsg, onUndo: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val detail = msg.text.lineSequence().drop(1).joinToString("\n").trim()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = toolIcon(msg.toolName),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    msg.toolName ?: "tool",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                when (msg.toolStatus) {
                    "running" -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )

                    "error" -> Text(
                        "오류",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Text(
                        "완료",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (expanded) detail else detail.lineSequence().take(4).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (detail.isNotBlank()) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "접기" else "자세히")
                    }
                }
                if (msg.canUndo) {
                    TextButton(onClick = onUndo) { Text("되돌리기") }
                }
                if (msg.undone) {
                    Text(
                        "되돌림",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun toolIcon(name: String?): ImageVector = when (name) {
    "read_file" -> Icons.Filled.Description
    "write_file", "edit_file" -> Icons.Filled.Edit
    "search_files" -> Icons.Filled.Search
    else -> Icons.Filled.FolderOpen
}

@Composable
private fun BlinkingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Text(
        text = "▍",
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun BannerBar(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "닫기",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: AgentQuestion,
    onAnswer: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by rememberSaveable(question.question) { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "확인이 필요합니다",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                question.question,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            question.options.forEach { option ->
                OutlinedButton(
                    onClick = { onAnswer(option) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(option)
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("직접 입력") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("작업 취소") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onAnswer(draft) },
                    enabled = draft.isNotBlank(),
                ) {
                    Text("답변")
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    request: AgentPermissionRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "실행 권한 확인",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                "${request.toolName} 도구를 실행할까요?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                request.summary,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDeny) { Text("거부") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onAllow) { Text("허용") }
            }
        }
    }
}

@Composable
private fun TodoPanel(todos: List<AgentTodo>) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val done = todos.count { it.status == "completed" }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Icon(
                    Icons.Filled.Checklist,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "할 일 $done/${todos.size}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "접기" else "펼치기",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                todos.forEach { todo ->
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = todoIcon(todo.status),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = todoTint(todo.status),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            todo.content,
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = if (todo.status == "completed") {
                                TextDecoration.LineThrough
                            } else {
                                null
                            },
                            color = if (todo.status == "cancelled") {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun todoIcon(status: String): ImageVector = when (status) {
    "completed" -> Icons.Filled.CheckCircle
    "in_progress" -> Icons.Filled.RadioButtonChecked
    "cancelled" -> Icons.Filled.Cancel
    else -> Icons.Filled.Circle
}

@Composable
private fun todoTint(status: String) = when (status) {
    "completed", "in_progress" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun AgentPermissionPicker(
    permission: AgentPermission,
    onSelect: (AgentPermission) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.Security,
                contentDescription = "권한 범위: ${permission.label}",
                tint = if (permission == AgentPermission.Full) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            AgentPermission.entries.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.label)
                            Text(
                                item.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = {
                        if (item == permission) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun AgentModelPicker(
    models: List<NimModel>,
    selected: String?,
    loading: Boolean,
    reasoning: ReasoningEffort,
    onReasoning: (ReasoningEffort) -> Unit,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(
                text = selected ?: if (loading) "불러오는 중…" else "모델 선택",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "모델 변경")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "추론 강도",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp),
            )
            ReasoningChips(
                selected = reasoning,
                onSelect = {
                    onReasoning(it)
                    open = false
                },
                modifier = Modifier
                    .width(300.dp)
                    .padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("모델 목록 새로고침") },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = {
                    open = false
                    onRefresh()
                },
            )
            HorizontalDivider()
            models.take(50).forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(model.id, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingIcon = {
                        if (model.id == selected) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        open = false
                        onPick(model.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun AgentInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    running: Boolean,
    attachments: List<AttachmentContent>,
    attachmentsLoading: Boolean,
    onAttach: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        AttachmentChips(
            attachments = attachments,
            loading = attachmentsLoading,
            onRemove = onRemoveAttachment,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onAttach) {
                Icon(Icons.Filled.AttachFile, contentDescription = "파일 첨부")
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("메시지 입력…") },
                maxLines = 5,
                shape = RoundedCornerShape(18.dp),
                trailingIcon = {
                    IconButton(onClick = {
                        keyboard?.hide()
                        focus.clearFocus()
                    }) {
                        Icon(
                            Icons.Filled.KeyboardHide,
                            contentDescription = "키보드 내리기",
                        )
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { if (running) onStop() else onSend() },
                enabled = running || draft.isNotBlank() || attachments.isNotEmpty(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = if (running) {
                        Icons.Filled.Stop
                    } else {
                        Icons.AutoMirrored.Filled.Send
                    },
                    contentDescription = if (running) "중지" else "전송",
                )
            }
        }
    }
}

private fun tokenLabel(tokens: Int, exact: Boolean, speed: Int): String = buildString {
    append("tok ")
    if (!exact) append("≈")
    append("%,d".format(tokens))
    if (speed > 0) append(" · ").append(speed).append(" tok/s")
}
