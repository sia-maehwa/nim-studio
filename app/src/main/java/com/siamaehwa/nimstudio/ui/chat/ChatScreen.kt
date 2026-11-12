@file:OptIn(ExperimentalMaterial3Api::class)

package com.siamaehwa.nimstudio.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.siamaehwa.nimstudio.NimStudioApp
import com.siamaehwa.nimstudio.core.AttachmentContent
import com.siamaehwa.nimstudio.core.NimModel
import com.siamaehwa.nimstudio.core.ReasoningEffort
import com.siamaehwa.nimstudio.core.db.ConversationEntity
import com.siamaehwa.nimstudio.ui.common.AttachmentChips
import com.siamaehwa.nimstudio.ui.common.AttachmentPreviews
import com.siamaehwa.nimstudio.ui.common.EmptyHint
import com.siamaehwa.nimstudio.ui.common.ReasoningChips
import com.siamaehwa.nimstudio.ui.common.RenderedMarkdown
import com.siamaehwa.nimstudio.ui.common.ScreenHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as NimStudioApp
    val vm: ChatViewModel = viewModel { ChatViewModel(app.container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var showConversations by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val systemDark = isSystemInDarkTheme()
    val darkMode by app.container.store.darkMode.collectAsStateWithLifecycle(initialValue = null)
    val isDark = darkMode ?: systemDark
    val attachPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.addAttachments(uris)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "채팅",
            subtitle = buildString {
                append(state.selectedModel ?: "모델을 선택하세요")
                if (state.searching) append(" · 웹 검색 중…")
                if (state.tokens > 0) {
                    append(" · ")
                    append(tokenLabel(state.tokens, state.tokensExact, state.tokensPerSecond))
                }
            },
        ) {
            IconButton(onClick = { showConversations = true }) {
                Icon(Icons.Filled.History, contentDescription = "대화 목록")
            }
            IconButton(onClick = { vm.setWebSearch(!state.webSearch) }) {
                Icon(
                    Icons.Filled.TravelExplore,
                    contentDescription = if (state.webSearch) {
                        "웹 검색 끄기"
                    } else {
                        "웹 검색 켜기"
                    },
                    tint = if (state.webSearch) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(
                onClick = {
                    scope.launch { app.container.store.saveDarkMode(!isDark) }
                },
            ) {
                Icon(
                    imageVector = if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    contentDescription = if (isDark) "라이트 모드로 전환" else "다크 모드로 전환",
                )
            }
            ModelPicker(
                models = state.chatModels,
                selected = state.selectedModel,
                loading = state.modelsLoading,
                reasoning = state.reasoning,
                onReasoning = vm::setReasoning,
                onPick = vm::pickModel,
                onRefresh = vm::refreshModels,
            )
            TextButton(onClick = vm::newChat, enabled = !state.streaming) {
                Text("새 대화")
            }
        }

        val banner = state.banner
        if (banner != null) {
            BannerBar(banner, onDismiss = vm::dismissBanner)
        }

        val notice = state.notice
        if (notice != null) {
            NoticeBar(notice, onDismiss = vm::dismissNotice)
        }

        if (state.hasKey == false) {
            EmptyHint(
                icon = Icons.Filled.Lock,
                title = "API 키가 필요합니다",
                body = "NVIDIA NIM API 키(nvapi-)를 등록하면 모델 목록과 채팅을 사용할 수 있습니다.",
                actionLabel = "설정 열기",
                onAction = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
        } else {
            PresetRow(selected = state.preset, onSelect = vm::setPreset)

            val reversed = remember(state.messages) { state.messages.asReversed() }
            LaunchedEffect(state.messages.size) {
                if (state.messages.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
                    listState.animateScrollToItem(0)
                }
            }

            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = reversed, key = { it.id }) { msg ->
                    MessageItem(
                        msg = msg,
                        onSaveToLibrary = { vm.saveMessageToLibrary(msg) },
                        onContinue = { vm.continueAnswer(msg.id) },
                    )
                }
            }

            InputBar(
                draft = draft,
                onDraftChange = { draft = it },
                streaming = state.streaming,
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

    if (showConversations) {
        ModalBottomSheet(onDismissRequest = { showConversations = false }) {
            ConversationSheet(
                conversations = state.conversations,
                currentId = state.conversationId,
                hasMessages = state.messages.isNotEmpty(),
                onOpen = { id ->
                    showConversations = false
                    vm.openConversation(id)
                },
                onDelete = vm::deleteConversation,
                onExport = {
                    showConversations = false
                    vm.exportConversation()
                },
                onNew = {
                    showConversations = false
                    vm.newChat()
                },
            )
        }
    }
}

@Composable
private fun ConversationSheet(
    conversations: List<ConversationEntity>,
    currentId: Long?,
    hasMessages: Boolean,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onExport: () -> Unit,
    onNew: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "대화 목록",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onNew) { Text("새 대화") }
        }
        HorizontalDivider()
        if (conversations.isEmpty()) {
            Text(
                "저장된 대화가 없습니다",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(items = conversations, key = { it.id }) { conversation ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(conversation.id) }
                            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    conversation.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (conversation.id == currentId) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "현재",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                "${formatChatDate(conversation.updatedAt)} · ${conversation.model}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onDelete(conversation.id) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "대화 삭제",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
        HorizontalDivider()
        TextButton(
            onClick = onExport,
            enabled = hasMessages,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text("현재 대화를 라이브러리에 내보내기")
        }
    }
}

@Composable
private fun NoticeBar(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
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
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "닫기",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

private val chatDateFormatter = DateTimeFormatter
    .ofPattern("M월 d일 HH:mm", Locale.KOREA)
    .withZone(ZoneId.systemDefault())

private fun formatChatDate(ms: Long): String =
    chatDateFormatter.format(Instant.ofEpochMilli(ms))

@Composable
private fun MessageItem(
    msg: ChatMsg,
    onSaveToLibrary: () -> Unit,
    onContinue: () -> Unit,
) {
    when (msg.role) {
        "user" -> UserBubble(msg)
        "error" -> ErrorBubble(msg)
        else -> AssistantMessage(msg, onSaveToLibrary, onContinue)
    }
}

@Composable
private fun UserBubble(msg: ChatMsg) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
                    if (msg.content.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
                if (msg.content.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = msg.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    msg: ChatMsg,
    onSaveToLibrary: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (msg.reasoning.isNotBlank()) {
            ReasoningBlock(msg.reasoning, live = msg.streaming)
            Spacer(Modifier.height(6.dp))
        }
        if (msg.content.isNotBlank()) {
            if (msg.streaming) {
                Text(msg.content, style = MaterialTheme.typography.bodyLarge)
            } else {
                RenderedMarkdown(msg.content)
            }
        }
        if (msg.truncated && !msg.streaming) {
            Spacer(Modifier.height(6.dp))
            Text(
                "응답이 중간에 끊겼습니다(길이 제한 또는 연결 종료). " +
                    "아래 버튼으로 계속 생성할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onContinue) { Text("이어서 생성") }
        }
        if (msg.streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (msg.content.isBlank() && msg.reasoning.isBlank()) {
                    Text(
                        "응답 생성 중…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                BlinkingCursor()
            }
        }
        val detail = msg.detail
        if (detail != null && !msg.streaming) {
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!msg.streaming && msg.content.isNotBlank()) {
            MessageCopyRow(msg.content, onSaveToLibrary)
        }
    }
}

@Composable
private fun ErrorBubble(msg: ChatMsg) {
    Surface(
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
            Column {
                Text(
                    msg.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                val detail = msg.detail
                if (detail != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(reasoning: String, live: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (live) "생각 과정 (진행 중)" else "생각 과정",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "접기" else "보기",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
private fun MessageCopyRow(text: String, onSaveToLibrary: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    LaunchedEffect(saved) {
        if (saved) {
            delay(1500)
            saved = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = "답변 복사",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = {
                onSaveToLibrary()
                saved = true
            },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = if (saved) Icons.Filled.Check else Icons.Filled.BookmarkAdd,
                contentDescription = "라이브러리에 저장",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
private fun PresetRow(selected: ChatPreset, onSelect: (ChatPreset) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "프리셋",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChatPreset.entries.forEach { preset ->
            FilterChip(
                selected = selected == preset,
                onClick = { onSelect(preset) },
                label = { Text(preset.label) },
            )
        }
    }
}

@Composable
private fun ModelPicker(
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
                modifier = Modifier.widthIn(max = 150.dp),
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
            if (models.size > 50) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "나머지는 ‘모델’ 탭에서 검색하세요",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    streaming: Boolean,
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
                maxLines = 6,
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
                onClick = { if (streaming) onStop() else onSend() },
                enabled = streaming || draft.isNotBlank() || attachments.isNotEmpty(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = if (streaming) {
                        Icons.Filled.Stop
                    } else {
                        Icons.AutoMirrored.Filled.Send
                    },
                    contentDescription = if (streaming) "중지" else "전송",
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
