package com.siamaehwa.nimstudio.ui.library

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siamaehwa.nimstudio.AppContainer
import com.siamaehwa.nimstudio.core.db.LibraryItemEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val items: List<LibraryItemEntity> = emptyList(),
    val query: String = "",
    val kind: String = "",
    val loading: Boolean = true,
    val notice: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    private val query = MutableStateFlow("")
    private val kind = MutableStateFlow("")

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(query, kind) { q, k -> q to k }
                .flatMapLatest { (q, k) -> container.library.observe(k, q) }
                .collect { items ->
                    _state.update { it.copy(items = items, loading = false) }
                }
        }
    }

    fun setQuery(value: String) {
        query.value = value
        _state.update { it.copy(query = value) }
    }

    fun setKind(value: String) {
        kind.value = value
        _state.update { it.copy(kind = value) }
    }

    fun delete(item: LibraryItemEntity) {
        viewModelScope.launch {
            container.library.delete(item)
            _state.update { it.copy(notice = "삭제했습니다") }
        }
    }

    fun exportToGallery(item: LibraryItemEntity) {
        viewModelScope.launch {
            val ok = container.library.exportImageToGallery(item)
            _state.update {
                it.copy(
                    notice = if (ok) {
                        "갤러리(Pictures/NIM Studio)에 저장했습니다"
                    } else {
                        "갤러리 저장에 실패했습니다"
                    },
                )
            }
        }
    }

    fun share(context: Context, item: LibraryItemEntity) {
        val intent = container.library.buildShareIntent(item) ?: return
        context.startActivity(Intent.createChooser(intent, "공유"))
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }
}
