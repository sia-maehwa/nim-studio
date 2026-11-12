@file:OptIn(ExperimentalMaterial3Api::class)

package com.siamaehwa.nimstudio.ui.hf

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.siamaehwa.nimstudio.NimStudioApp
import com.siamaehwa.nimstudio.core.hf.HfApiStyle
import com.siamaehwa.nimstudio.core.hf.HfAvailability
import com.siamaehwa.nimstudio.core.hf.HfChatMessage
import com.siamaehwa.nimstudio.core.hf.HfModelSummary
import com.siamaehwa.nimstudio.core.hf.HfSavedModel
import com.siamaehwa.nimstudio.core.hf.HfTask
import com.siamaehwa.nimstudio.ui.common.EmptyHint
import com.siamaehwa.nimstudio.ui.common.RenderedMarkdown
import com.siamaehwa.nimstudio.ui.common.ScreenHeader
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HfScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as NimStudioApp
    val vm: HfViewModel = viewModel { HfViewModel(app.container) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Hugging Face",
            subtitle = "Inference Providers · Inference Endpoints",
        ) {
            if (state.tokenPresent) {
                Text(
                    state.user?.let { "토큰 연결됨 · $it" } ?: "토큰 연결됨",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = { vm.setTab(3) }) { Text("토큰 등록") }
            }
        }

        TabRow(selectedTabIndex = state.tab) {
            listOf("모델 탐색", "채팅", "이미지 생성", "연결 설정").forEachIndexed { index, label ->
                Tab(
                    selected = state.tab == index,
                    onClick = { vm.setTab(index) },
                    text = { Text(label) },
                )
            }
        }

        when (state.tab) {
            0 -> ExploreTab(state, vm)
            1 -> ChatTab(state, vm)
            2 -> ImageTab(state, vm)
            else -> SettingsTab(state, vm)
        }
    }
}

@Composable
private fun ExploreTab(state: HfUiState, vm: HfViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (!state.tokenPresent) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    "토큰 없이도 공개 모델을 검색할 수 있습니다. 채팅·이미지 생성에는 " +
                        "Hugging Face 토큰이 필요합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("모델 검색 (예: Qwen, FLUX, Llama)") },
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = vm::search,
                enabled = !state.searching,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.availableOnly,
                onClick = vm::toggleAvailableOnly,
                label = { Text("실행 가능만") },
            )
            listOf(
                "all" to "전체",
                "chat" to "채팅",
                "image" to "이미지",
                "edit" to "이미지 편집",
            ).forEach { (id, label) ->
                FilterChip(
                    selected = state.taskFilter == id,
                    onClick = { vm.setTaskFilter(id) },
                    label = { Text(label) },
                )
            }
        }

        if (state.saved.isNotEmpty()) {
            Text(
                "즐겨찾기",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.saved.forEach { saved ->
                    SavedChip(
                        saved = saved,
                        opening = state.opening,
                        onOpen = { vm.openSaved(saved) },
                        onDelete = { vm.removeSaved(saved) },
                    )
                }
            }
        }

        if (state.searchError != null) {
            Text(
                state.searchError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        if (state.openError != null) {
            Text(
                state.openError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        val filtered = state.results.filter { model ->
            when (state.taskFilter) {
                "chat" -> model.task == HfTask.Chat
                "image" -> model.task == HfTask.TextToImage || model.task == HfTask.ImageToImage
                "edit" -> model.task == HfTask.ImageToImage
                else -> true
            }
        }

        when {
            state.searching && state.results.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            filtered.isEmpty() -> EmptyHint(
                icon = Icons.Filled.Cloud,
                title = "표시할 모델이 없습니다",
                body = if (state.availableOnly) {
                    "실행 가능한 모델이 없습니다. '실행 가능만'을 끄거나 검색어를 바꿔 보세요."
                } else {
                    "검색어를 바꾸거나 새로고침하세요. HF Hub에는 배포가 필요한 모델도 많습니다."
                },
                actionLabel = "새로고침",
                onAction = vm::search,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 6.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        tokenPresent = state.tokenPresent,
                        saved = state.saved.any { it.id == model.id },
                        onChat = { vm.selectForChat(model) },
                        onImage = { vm.selectForImage(model) },
                        onSave = {
                            vm.toggleSaved(
                                model,
                                model.task,
                                model.liveProviders(model.task).firstOrNull()?.provider,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedChip(
    saved: HfSavedModel,
    opening: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        ) {
            TextButton(onClick = onOpen, enabled = !opening) {
                Text(
                    saved.id.substringAfter('/').take(24),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, contentDescription = "삭제", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: HfModelSummary,
    tokenPresent: Boolean,
    saved: Boolean,
    onChat: () -> Unit,
    onImage: () -> Unit,
    onSave: () -> Unit,
) {
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.getDefault()) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.id,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${model.author} · 다운로드 ${numberFormat.format(model.downloads)} · " +
                            "좋아요 ${model.likes}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onSave) {
                    Icon(
                        if (saved) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (saved) "즐겨찾기 해제" else "즐겨찾기",
                        tint = if (saved) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvailabilityBadge(
                    model.availabilityFor(model.task, tokenPresent),
                )
                Text(
                    model.task.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                model.license?.let {
                    Text(
                        "· $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            if (model.providers.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    model.providers
                        .joinToString(" · ") { provider ->
                            val status = if (provider.status == "live") "" else "(${provider.status})"
                            provider.provider + status
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = onChat) { Text("채팅에 사용") }
                TextButton(onClick = onImage) { Text("이미지에 사용") }
            }
        }
    }
}

@Composable
private fun AvailabilityBadge(availability: HfAvailability) {
    val container = when (availability) {
        HfAvailability.Ready -> MaterialTheme.colorScheme.secondaryContainer
        HfAvailability.NoProvider, HfAvailability.Unknown ->
            MaterialTheme.colorScheme.surfaceVariant

        HfAvailability.Gated, HfAvailability.AuthRequired ->
            MaterialTheme.colorScheme.errorContainer

        HfAvailability.NeedsDeployment -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = when (availability) {
        HfAvailability.Ready -> MaterialTheme.colorScheme.onSecondaryContainer
        HfAvailability.NoProvider, HfAvailability.Unknown ->
            MaterialTheme.colorScheme.onSurfaceVariant

        HfAvailability.Gated, HfAvailability.AuthRequired ->
            MaterialTheme.colorScheme.onErrorContainer

        HfAvailability.NeedsDeployment -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(color = container, shape = RoundedCornerShape(8.dp)) {
        Text(
            availability.label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ChatTab(state: HfUiState, vm: HfViewModel) {
    if (!state.chatReady && !state.useEndpoint) {
        EmptyHint(
            icon = Icons.Filled.Chat,
            title = "채팅 모델을 선택하세요",
            body = "모델 탐색 탭에서 채팅 모델을 고르면 Inference Provider를 통해 " +
                "바로 대화할 수 있습니다.",
            actionLabel = "모델 탐색",
            onAction = { vm.setTab(0) },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.chatModel ?: "Endpoint 채팅",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.useEndpoint) {
                        "Endpoint 사용 중"
                    } else {
                        "Provider: ${state.chatProvider ?: "자동"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.useEndpoint && state.chatProviders.isNotEmpty()) {
                ProviderMenu(
                    providers = state.chatProviders,
                    selected = state.chatProvider,
                    onPick = vm::setChatProvider,
                    allowAuto = true,
                )
            }
            IconButton(onClick = vm::clearChat) {
                Icon(Icons.Filled.Delete, contentDescription = "대화 비우기")
            }
        }
        HorizontalDivider()

        val listState = rememberLazyListState()
        LaunchedEffect(state.chatMessages.size, state.chatStreaming) {
            if (state.chatMessages.isNotEmpty()) {
                listState.scrollToItem(state.chatMessages.lastIndex)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.chatMessages.size) { index ->
                ChatBubble(state.chatMessages[index])
            }
        }

        state.chatNotice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        state.chatUsage?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        state.chatError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = state.chatInput,
                onValueChange = vm::setChatInput,
                modifier = Modifier.weight(1f),
                maxLines = 4,
                placeholder = { Text("메시지를 입력하세요") },
            )
            Spacer(Modifier.width(8.dp))
            if (state.chatStreaming) {
                OutlinedButton(onClick = vm::stopChat) { Text("중단") }
            } else {
                Button(
                    onClick = vm::sendChat,
                    enabled = state.chatInput.isNotBlank(),
                ) { Text("전송") }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: HfChatMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (!isUser) {
                    Text(
                        "assistant",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isUser) {
                    Text(message.content)
                } else if (message.content.isBlank()) {
                    Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    RenderedMarkdown(message.content)
                }
            }
        }
    }
}

@Composable
private fun ProviderMenu(
    providers: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
    allowAuto: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(selected ?: if (allowAuto) "자동" else "Provider")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowAuto) {
                DropdownMenuItem(
                    text = { Text("자동 (가장 빠른 Provider)") },
                    onClick = {
                        expanded = false
                        onPick("")
                    },
                )
            }
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider) },
                    onClick = {
                        expanded = false
                        onPick(provider)
                    },
                )
            }
        }
    }
}

@Composable
private fun ImageTab(state: HfUiState, vm: HfViewModel) {
    val context = LocalContext.current
    val referencePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        vm.setReferenceImage(bytes)
    }
    if (!state.imageReady && !state.useEndpoint) {
        EmptyHint(
            icon = Icons.Filled.Image,
            title = "이미지 모델을 선택하세요",
            body = "모델 탐색 탭에서 텍스트→이미지 또는 이미지 편집 모델을 고르세요. " +
                "hf-inference와 fal-ai Provider를 지원하며, 참조 이미지 편집은 fal-ai만 지원합니다.",
            actionLabel = "모델 탐색",
            onAction = { vm.setTab(0) },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.imageModel ?: "Endpoint 이미지",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.useEndpoint) {
                        "Endpoint 사용 중"
                    } else {
                        "Provider: ${state.imageProvider ?: "없음"} · " +
                            HfTask.fromId(state.imageTaskId).label
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.useEndpoint && state.imageProviders.isNotEmpty()) {
                ProviderMenu(
                    providers = state.imageProviders.map { it.provider },
                    selected = state.imageProvider,
                    onPick = vm::setImageProvider,
                )
            }
        }

        if (!state.useEndpoint && state.imageProvider !in listOf("hf-inference", "fal-ai")) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    "선택한 Provider의 이미지 생성은 아직 지원하지 않습니다. " +
                        "hf-inference 또는 fal-ai 모델을 선택하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.imagePrompt,
            onValueChange = vm::setImagePrompt,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            label = { Text("프롬프트") },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.negative,
            onValueChange = vm::setNegative,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("네거티브 프롬프트 (선택)") },
        )

        if (!state.useEndpoint && state.imageNeedsReference) {
            Spacer(Modifier.height(10.dp))
            Text("참조 이미지 (필수)", style = MaterialTheme.typography.labelLarge)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                val reference = state.referenceImage
                if (reference != null) {
                    val bitmap = remember(reference) {
                        runCatching {
                            BitmapFactory.decodeByteArray(reference, 0, reference.size)
                                ?.asImageBitmap()
                        }.getOrNull()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "참조 이미지",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(88.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }
                    TextButton(onClick = vm::clearReferenceImage) { Text("삭제") }
                }
                OutlinedButton(onClick = { referencePicker.launch("image/*") }) {
                    Text(if (reference == null) "이미지 선택" else "이미지 변경")
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("크기", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HfViewModel.sizes.forEachIndexed { index, size ->
                FilterChip(
                    selected = state.sizeIndex == index,
                    onClick = { vm.setSizeIndex(index) },
                    label = { Text(size.label) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "스텝 ${state.steps}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(88.dp),
            )
            Slider(
                value = state.steps.toFloat(),
                onValueChange = { vm.setSteps(it.roundToInt()) },
                valueRange = 4f..50f,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = state.seed,
            onValueChange = vm::setSeed,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("시드 (선택, 비우면 무작위)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = vm::generateImage,
                enabled = !state.generating && state.imagePrompt.isNotBlank() &&
                    (!state.imageNeedsReference || state.useEndpoint ||
                        state.referenceImage != null),
                modifier = Modifier.weight(1f),
            ) {
                if (state.generating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("생성 중…")
                } else {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("이미지 생성")
                }
            }
            if (state.generating) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = vm::stopImage) { Text("중단") }
            }
        }

        state.imageNotice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        state.imageError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (state.images.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("최근 생성", style = MaterialTheme.typography.titleSmall)
            state.images.chunked(2).forEach { rowImages ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowImages.forEach { bytes ->
                        val bitmap = remember(bytes) {
                            runCatching {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    ?.asImageBitmap()
                            }.getOrNull()
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "생성된 이미지",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                )
                            } else {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("미리보기 불가", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    if (rowImages.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(state: HfUiState, vm: HfViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Hugging Face 토큰", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "Inference Providers 호출에는 'Make calls to Inference Providers' " +
                        "권한이 있는 토큰이 필요합니다. 토큰은 NIM 키와 분리된 Keystore " +
                        "별칭으로 암호화되어 저장됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (state.tokenPresent) {
                    Text(
                        state.user?.let { "연결됨: $it" } ?: "토큰이 저장되어 있습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(
                            onClick = vm::verifySavedToken,
                            enabled = !state.verifying,
                        ) { Text(if (state.verifying) "확인 중…" else "연결 확인") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = vm::clearToken) { Text("토큰 삭제") }
                    }
                } else {
                    OutlinedTextField(
                        value = state.tokenInput,
                        onValueChange = { vm.setTokenInput(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("hf_… 토큰") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(
                        onClick = vm::saveToken,
                        enabled = !state.verifying && state.tokenInput.isNotBlank(),
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(if (state.verifying) "확인 중…" else "저장 및 확인") }
                }
                state.tokenMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                state.tokenError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cloud, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Inference Endpoint / Custom GPU", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "전용 Endpoint(TGI/TEI 등)의 URL을 등록하면 Provider 목록에 없는 " +
                        "모델도 사용할 수 있습니다. URL은 https://를 권장합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                OutlinedTextField(
                    value = state.endpointUrl,
                    onValueChange = vm::setEndpointUrl,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Endpoint URL") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.endpointModel,
                    onValueChange = vm::setEndpointModel,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("모델 ID (선택, 예: tgi)") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.endpointTokenInput,
                    onValueChange = vm::setEndpointTokenInput,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(
                            if (state.endpointTokenPresent) {
                                "Endpoint 토큰 (저장됨, 변경 시 입력)"
                            } else {
                                "Endpoint 토큰 (선택)"
                            },
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(8.dp))
                Text("작업", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.endpointTaskId == HfTask.Chat.id,
                        onClick = { vm.setEndpointTask(HfTask.Chat) },
                        label = { Text("채팅") },
                    )
                    FilterChip(
                        selected = state.endpointTaskId == HfTask.TextToImage.id,
                        onClick = { vm.setEndpointTask(HfTask.TextToImage) },
                        label = { Text("이미지") },
                    )
                }
                if (state.endpointTaskId == HfTask.Chat.id) {
                    Spacer(Modifier.height(4.dp))
                    Text("API 방식", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HfApiStyle.entries.forEach { style ->
                            FilterChip(
                                selected = state.endpointStyleId == style.id,
                                onClick = { vm.setEndpointStyle(style) },
                                label = { Text(style.label) },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = vm::saveEndpoint) { Text("저장") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = vm::testEndpoint,
                        enabled = !state.endpointTesting,
                    ) { Text(if (state.endpointTesting) "테스트 중…" else "연결 테스트") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = vm::clearEndpoint) { Text("삭제") }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Switch(
                        checked = state.useEndpoint,
                        onCheckedChange = vm::setUseEndpoint,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "채팅·이미지 생성에 Endpoint 사용",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.endpointMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                state.endpointError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        Text(
            "Inference Providers는 Hugging Face 계정의 월 무료 크레딧을 사용합니다. " +
                "크레딧 소진 시 402/429 오류가 발생할 수 있습니다. " +
                "Provider 상태가 error인 모델은 다른 Provider를 선택하세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
