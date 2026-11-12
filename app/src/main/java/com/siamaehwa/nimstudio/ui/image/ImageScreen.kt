@file:OptIn(ExperimentalMaterial3Api::class)

package com.siamaehwa.nimstudio.ui.image

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.siamaehwa.nimstudio.NimStudioApp
import com.siamaehwa.nimstudio.core.ImageCatalog
import com.siamaehwa.nimstudio.core.ImageSupport
import com.siamaehwa.nimstudio.core.NimModel
import com.siamaehwa.nimstudio.ui.common.EmptyHint
import com.siamaehwa.nimstudio.ui.common.ScreenHeader
import kotlin.math.roundToInt

@Composable
fun ImageScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NimStudioApp
    val vm: ImageViewModel = viewModel { ImageViewModel(app.container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val selectedSpec = state.selectedModel?.let { ImageCatalog.spec(it) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "이미지 생성",
            subtitle = state.selectedModel?.let { ImageCatalog.label(it) } ?: "모델을 선택하세요",
        ) {
            ImageModelPicker(
                models = state.imageModels,
                selected = state.selectedModel,
                loading = state.modelsLoading,
                onPick = vm::pickModel,
                onRefresh = vm::refreshModels,
            )
        }

        val support = selectedSpec?.support
        if (support != null && support != ImageSupport.Verified) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    text = selectedSpec.note
                        ?: "이 모델은 실제 생성 지원이 확인되지 않았습니다. 404/422 오류가 날 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (state.hasKey == false) {
            EmptyHint(
                icon = Icons.Filled.Lock,
                title = "API 키가 필요합니다",
                body = "NVIDIA NIM API 키(nvapi-)를 등록하면 이미지 생성 모델을 사용할 수 있습니다.",
                actionLabel = "설정 열기",
                onAction = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = state.prompt,
                onValueChange = vm::setPrompt,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("프롬프트") },
                placeholder = { Text("예: 네온 빛이 흐르는 미래 도시의 밤, 시네마틱") },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(14.dp),
            )

            Spacer(Modifier.height(8.dp))

            if (selectedSpec == null || selectedSpec.supportsNegative) {
                OutlinedTextField(
                    value = state.negative,
                    onValueChange = vm::setNegative,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("네거티브 프롬프트 (선택)") },
                    placeholder = { Text("예: blurry, low quality, watermark") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )

                Spacer(Modifier.height(14.dp))
            }

            Text(
                "크기",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ImageViewModel.sizes.forEachIndexed { index, preset ->
                    FilterChip(
                        selected = state.sizeIndex == index,
                        onClick = { vm.pickSize(index) },
                        label = { Text(preset.label) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "스텝 ${selectedSpec?.fixedSteps ?: state.steps}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp),
                )
                Slider(
                    value = (selectedSpec?.fixedSteps ?: state.steps).toFloat(),
                    onValueChange = { vm.setSteps(it.roundToInt()) },
                    valueRange = 4f..50f,
                    steps = 45,
                    enabled = selectedSpec?.fixedSteps == null,
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = state.seed,
                onValueChange = vm::setSeed,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("시드 (선택)") },
                placeholder = { Text("비우면 매번 랜덤") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp),
            )

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = vm::generate,
                enabled = !state.generating &&
                    state.prompt.isNotBlank() &&
                    state.selectedModel != null &&
                    selectedSpec?.support != ImageSupport.Deprecated,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.generating) "생성 중… ${state.elapsedSec}초" else "이미지 생성")
            }

            if (state.generating) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = vm::cancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("중지")
                }
            }

            val error = state.error
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = vm::dismissError, modifier = Modifier.size(30.dp)) {
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

            val notice = state.notice
            if (notice != null) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }

            if (state.generating && state.results.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            if (state.results.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                state.results.forEachIndexed { index, bytes ->
                    ResultCard(
                        bytes = bytes,
                        onSaveToGallery = { vm.saveToGallery(index) },
                        onShare = {
                            val intent = app.container.library
                                .buildShareIntentForBytes(bytes, "image/png")
                            if (intent != null) {
                                context.startActivity(intent)
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            } else if (!state.generating) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "결과 이미지는 라이브러리에 자동 저장됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.imageModels.isEmpty() && !state.modelsLoading) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "사용 가능한 이미지 모델이 없습니다. 계정에 이미지 모델 접근 권한이 있는지, " +
                        "또는 ‘모델’ 탭에서 모델 목록을 확인하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResultCard(
    bytes: ByteArray,
    onSaveToGallery: () -> Unit,
    onShare: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            AsyncImage(
                model = bytes,
                contentDescription = "생성된 이미지",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSaveToGallery) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("갤러리에 저장")
                }
                TextButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("공유")
                }
            }
        }
    }
}

@Composable
private fun ImageModelPicker(
    models: List<NimModel>,
    selected: String?,
    loading: Boolean,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                text = selected ?: if (loading) "불러오는 중…" else "모델 선택",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "모델 변경")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("모델 목록 새로고침") },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = {
                    open = false
                    onRefresh()
                },
            )
            HorizontalDivider()
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("이미지 모델 없음", style = MaterialTheme.typography.bodySmall) },
                    enabled = false,
                    onClick = {},
                )
            }
            models.take(50).forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            ImageCatalog.label(model.id),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
