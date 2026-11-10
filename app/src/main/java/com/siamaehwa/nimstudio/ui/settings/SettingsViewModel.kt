package com.siamaehwa.nimstudio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siamaehwa.nimstudio.AppContainer
import com.siamaehwa.nimstudio.core.ConnectionCheck
import com.siamaehwa.nimstudio.core.KeyFormat
import com.siamaehwa.nimstudio.core.NimStore
import com.siamaehwa.nimstudio.core.plugin.PluginManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CheckResult {
    data class Success(val modelCount: Int, val latencyMs: Long) : CheckResult
    data class Failure(val message: String) : CheckResult
}

data class SettingsUiState(
    val initialized: Boolean = false,
    val keyInput: String = "",
    val keyVisible: Boolean = false,
    val hasStoredKey: Boolean = false,
    val baseUrlInput: String = NimStore.DEFAULT_BASE_URL,
    val checking: Boolean = false,
    val result: CheckResult? = null,
    val plugins: List<PluginManifest> = emptyList(),
    val pluginMessage: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { container.plugins.reload() }
        viewModelScope.launch {
            container.plugins.plugins.collect { list ->
                _state.update { it.copy(plugins = list) }
            }
        }
        viewModelScope.launch {
            val key = container.store.apiKey.first()
            val base = container.store.baseUrl.first()
            _state.update {
                it.copy(
                    initialized = true,
                    keyInput = key.orEmpty(),
                    hasStoredKey = !key.isNullOrBlank(),
                    baseUrlInput = base,
                )
            }
        }
    }

    fun onKeyInput(value: String) = _state.update { it.copy(keyInput = value) }

    fun toggleVisible() = _state.update { it.copy(keyVisible = !it.keyVisible) }

    fun onBaseUrlInput(value: String) = _state.update { it.copy(baseUrlInput = value) }

    fun resetBaseUrl() = _state.update { it.copy(baseUrlInput = NimStore.DEFAULT_BASE_URL) }

    fun saveAndTest() {
        if (_state.value.checking) return
        viewModelScope.launch {
            _state.update { it.copy(checking = true, result = null) }
            val raw = KeyFormat.sanitize(_state.value.keyInput)
            if (raw.isNotEmpty()) {
                container.store.saveApiKey(raw)
            }
            container.store.saveBaseUrl(_state.value.baseUrlInput)
            val key = container.store.apiKey.first()
            val result: CheckResult = if (key.isNullOrBlank()) {
                CheckResult.Failure("API 키를 입력하세요. build.nvidia.com → API Keys에서 발급합니다.")
            } else {
                when (val check = container.repository.verifyConnection()) {
                    is ConnectionCheck.Success -> CheckResult.Success(check.modelCount, check.latencyMs)
                    is ConnectionCheck.Failure -> CheckResult.Failure(check.message)
                }
            }
            _state.update {
                it.copy(
                    checking = false,
                    hasStoredKey = !key.isNullOrBlank(),
                    result = result,
                    keyInput = key.orEmpty(),
                )
            }
        }
    }

    fun deleteKey() {
        viewModelScope.launch {
            container.store.clearApiKey()
            _state.update { it.copy(keyInput = "", hasStoredKey = false, result = null) }
        }
    }

    fun addPlugin(raw: String) {
        viewModelScope.launch {
            val result = container.plugins.addFromJson(raw)
            _state.update {
                it.copy(
                    pluginMessage = result.fold(
                        onSuccess = { count -> "플러그인 ${count}개를 저장했습니다." },
                        onFailure = { error -> "추가 실패: ${error.message}" },
                    ),
                )
            }
        }
    }

    fun togglePlugin(name: String, enabled: Boolean) {
        viewModelScope.launch { container.plugins.setEnabled(name, enabled) }
    }

    fun removePlugin(name: String) {
        viewModelScope.launch {
            container.plugins.remove(name)
            _state.update { it.copy(pluginMessage = "삭제했습니다: $name") }
        }
    }
}
