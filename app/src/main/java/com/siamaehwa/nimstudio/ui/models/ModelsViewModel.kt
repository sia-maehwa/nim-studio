package com.siamaehwa.nimstudio.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siamaehwa.nimstudio.AppContainer
import com.siamaehwa.nimstudio.core.ImageCatalog
import com.siamaehwa.nimstudio.core.ModelTestOutcome
import com.siamaehwa.nimstudio.core.NimErrors
import com.siamaehwa.nimstudio.core.NimModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ModelFilter(val label: String) {
    All("전체"),
    Chat("채팅"),
    Image("이미지"),
    Other("기타"),
}

sealed interface ModelTestState {
    data object Running : ModelTestState
    data class Ok(val latencyMs: Long) : ModelTestState
    data class Fail(val message: String) : ModelTestState
}

data class ModelsUiState(
    val loading: Boolean = false,
    val models: List<NimModel> = emptyList(),
    val query: String = "",
    val filter: ModelFilter = ModelFilter.All,
    val tests: Map<String, ModelTestState> = emptyMap(),
    val error: String? = null,
    val loadedOnce: Boolean = false,
)

class ModelsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val models = ImageCatalog.merge(container.repository.models())
                _state.update { it.copy(loading = false, models = models, loadedOnce = true) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        loadedOnce = true,
                        models = ImageCatalog.merge(emptyList()),
                        error = NimErrors.friendlyFor(t),
                    )
                }
            }
        }
    }

    fun onQuery(value: String) = _state.update { it.copy(query = value) }

    fun onFilter(value: ModelFilter) = _state.update { it.copy(filter = value) }

    fun test(model: NimModel) {
        if (_state.value.tests[model.id] is ModelTestState.Running) return
        viewModelScope.launch {
            _state.update { it.copy(tests = it.tests + (model.id to ModelTestState.Running)) }
            val outcome = container.repository.testModel(model.id)
            val result = when (outcome) {
                is ModelTestOutcome.Success -> ModelTestState.Ok(outcome.latencyMs)
                is ModelTestOutcome.Failure -> ModelTestState.Fail(outcome.message)
            }
            _state.update { it.copy(tests = it.tests + (model.id to result)) }
        }
    }

    fun useInChat(model: NimModel) {
        viewModelScope.launch { container.store.saveLastModel(model.id) }
    }
}
