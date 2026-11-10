@file:OptIn(ExperimentalMaterial3Api::class)

package com.siamaehwa.nimstudio.ui.models

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.siamaehwa.nimstudio.NimStudioApp
import com.siamaehwa.nimstudio.core.ModelCategory
import com.siamaehwa.nimstudio.core.ModelKinds
import com.siamaehwa.nimstudio.core.NimModel
import com.siamaehwa.nimstudio.ui.common.EmptyHint
import com.siamaehwa.nimstudio.ui.common.ScreenHeader

@Composable
fun ModelsScreen(onOpenChat: () -> Unit) {
    val app = LocalContext.current.applicationContext as NimStudioApp
    val vm: ModelsViewModel = viewModel { ModelsViewModel(app.container) }
    val state by vm.state.collectAsStateWithLifecycle()

    val visible = remember(state.models, state.query, state.filter) {
        val q = state.query.trim().lowercase()
        state.models.filter { model ->
            val matchQuery = q.isEmpty() || model.id.lowercase().contains(q)
            val matchFilter = when (state.filter) {
                ModelFilter.All -> true
                ModelFilter.Chat -> ModelKinds.category(model.id) == ModelCategory.Chat
                ModelFilter.Image -> ModelKinds.category(model.id) == ModelCategory.Image
                ModelFilter.Other -> ModelKinds.category(model.id) == ModelCategory.Other
            }
            matchQuery && matchFilter
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "모델", subtitle = "목록 · 검색 · 응답 지연 테스트") {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = vm::refresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = vm::onQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("모델 검색 (예: llama, flux)") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModelFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { vm.onFilter(filter) },
                    label = { Text(filter.label) },
                )
            }
        }
        Text(
            text = "${visible.size}개 모델",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        val error = state.error
        when {
            state.loading && state.models.isEmpty() -> Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            error != null && state.models.isEmpty() -> EmptyHint(
                icon = Icons.Filled.Warning,
                title = "모델을 불러오지 못했습니다",
                body = error,
                actionLabel = "다시 시도",
                onAction = vm::refresh,
                modifier = Modifier.weight(1f),
            )
            visible.isEmpty() -> EmptyHint(
                icon = Icons.Filled.Search,
                title = "결과 없음",
                body = "검색어나 필터를 바꿔 보세요.",
                modifier = Modifier.weight(1f),
            )
            else -> LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(items = visible, key = { it.id }) { model ->
                    ModelRow(
                        model = model,
                        test = state.tests[model.id],
                        onTest = { vm.test(model) },
                        onUseInChat = {
                            vm.useInChat(model)
                            onOpenChat()
                        },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: NimModel,
    test: ModelTestState?,
    onTest: () -> Unit,
    onUseInChat: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = model.id,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(ModelKinds.category(model.id))
            val owned = model.ownedBy
            if (!owned.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    owned,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            when (test) {
                null -> TextButton(onClick = onTest) { Text("테스트") }
                is ModelTestState.Running -> {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "확인 중",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ModelTestState.Ok -> Text(
                    text = "정상 · ${test.latencyMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is ModelTestState.Fail -> TextButton(onClick = onTest) {
                    Text("재시도", color = MaterialTheme.colorScheme.error)
                }
            }
            if (ModelKinds.isLikelyChat(model.id)) {
                TextButton(onClick = onUseInChat) { Text("채팅") }
            }
        }
        if (test is ModelTestState.Fail) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = test.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CategoryBadge(category: ModelCategory) {
    val background: androidx.compose.ui.graphics.Color
    val foreground: androidx.compose.ui.graphics.Color
    when (category) {
        ModelCategory.Chat -> {
            background = MaterialTheme.colorScheme.primaryContainer
            foreground = MaterialTheme.colorScheme.onPrimaryContainer
        }
        ModelCategory.Image -> {
            background = MaterialTheme.colorScheme.secondaryContainer
            foreground = MaterialTheme.colorScheme.onSecondaryContainer
        }
        ModelCategory.Other -> {
            background = MaterialTheme.colorScheme.surfaceVariant
            foreground = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Surface(color = background, shape = RoundedCornerShape(6.dp)) {
        Text(
            text = category.label,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
