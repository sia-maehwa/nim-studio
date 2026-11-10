@file:OptIn(ExperimentalMaterial3Api::class)

package com.siamaehwa.nimstudio.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.siamaehwa.nimstudio.BuildConfig
import com.siamaehwa.nimstudio.NimStudioApp
import com.siamaehwa.nimstudio.core.KeyFormat
import com.siamaehwa.nimstudio.ui.common.ScreenHeader

@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as NimStudioApp
    val vm: SettingsViewModel = viewModel { SettingsViewModel(app.container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var showAddPlugin by rememberSaveable { mutableStateOf(false) }
    var pluginJson by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(title = "설정", subtitle = "키 관리와 연결 진단")

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "API 키",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (state.hasStoredKey) "저장됨" else "없음",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.hasStoredKey) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Text(
                        "build.nvidia.com → API Keys에서 발급한 nvapi- 키를 붙여넣으세요. 키는 이 기기에만 Keystore로 암호화되어 저장됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.keyInput,
                        onValueChange = vm::onKeyInput,
                        label = { Text("API 키") },
                        singleLine = true,
                        visualTransformation = if (state.keyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = vm::toggleVisible) {
                                Icon(
                                    imageVector = if (state.keyVisible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (state.keyVisible) "키 숨기기" else "키 보기",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.keyInput.isNotBlank() && !KeyFormat.looksLikeNimKey(state.keyInput)) {
                        Text(
                            "키가 nvapi- 로 시작하지 않습니다. 그래도 저장 후 테스트해 볼 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = vm::saveAndTest, enabled = !state.checking) {
                            if (state.checking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (state.checking) "확인 중…" else "저장하고 연결 테스트")
                        }
                        if (state.hasStoredKey) {
                            TextButton(onClick = vm::deleteKey) {
                                Text("키 삭제", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            val result = state.result
            if (result != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        when (result) {
                            is CheckResult.Success -> {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("연결 성공", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "모델 ${result.modelCount}개 · ${result.latencyMs}ms",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            is CheckResult.Failure -> {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("연결 실패", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        result.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("서버 주소", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "기본값은 NVIDIA 호스팅 엔드포인트입니다. 자체 NIM 서버가 있으면 주소를 바꾸세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.baseUrlInput,
                        onValueChange = vm::onBaseUrlInput,
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = vm::resetBaseUrl) { Text("기본값으로") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "에이전트 플러그인",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${state.plugins.count { it.enabled }}/${state.plugins.size} 사용 중",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "JSON 매니페스트로 HTTP 도구를 추가합니다. 추가한 도구는 에이전트가 필요할 때 호출합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.plugins.isEmpty()) {
                        Text(
                            "등록된 플러그인이 없습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.plugins.forEach { plugin ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(plugin.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "도구 ${plugin.tools.size}개 · " +
                                            plugin.description.ifBlank { "설명 없음" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(
                                    checked = plugin.enabled,
                                    onCheckedChange = { vm.togglePlugin(plugin.name, it) },
                                )
                                IconButton(onClick = { vm.removePlugin(plugin.name) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "플러그인 삭제",
                                    )
                                }
                            }
                        }
                    }
                    Row {
                        Button(onClick = { showAddPlugin = true }) {
                            Text("플러그인 추가")
                        }
                    }
                    state.pluginMessage?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("앱 정보", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "NIM Studio ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        uriHandler.openUri("https://github.com/sia-maehwa/nim-studio")
                    }) {
                        Text("GitHub 저장소 열기")
                    }
                    Text(
                        "입력한 API 키는 기기를 벗어나지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (showAddPlugin) {
            AlertDialog(
                onDismissRequest = { showAddPlugin = false },
                title = { Text("플러그인 추가") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "매니페스트 JSON(단일 객체 또는 배열)을 붙여넣으세요. " +
                                "HTTP 도구만 지원하며 {{이름}} 자리에 인자가 들어갑니다.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = pluginJson,
                            onValueChange = { pluginJson = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            placeholder = { Text("{\"name\": ...}") },
                        )
                        TextButton(onClick = { pluginJson = PLUGIN_EXAMPLE }) {
                            Text("예시 채우기")
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.addPlugin(pluginJson)
                            pluginJson = ""
                            showAddPlugin = false
                        },
                        enabled = pluginJson.isNotBlank(),
                    ) { Text("추가") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddPlugin = false }) { Text("취소") }
                },
            )
        }
    }
}

private val PLUGIN_EXAMPLE = """
{
  "name": "my-tools",
  "description": "예시 플러그인",
  "tools": [
    {
      "name": "http_get",
      "description": "URL에서 JSON을 가져옵니다",
      "parameters": {
        "type": "object",
        "properties": { "q": { "type": "string", "description": "검색어" } },
        "required": ["q"]
      },
      "action": {
        "type": "http",
        "method": "GET",
        "url": "https://api.example.com/search?q={{q}}"
      }
    }
  ]
}
""".trimIndent()
