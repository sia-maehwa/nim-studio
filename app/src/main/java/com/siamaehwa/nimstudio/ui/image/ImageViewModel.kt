package com.siamaehwa.nimstudio.ui.image

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siamaehwa.nimstudio.AppContainer
import com.siamaehwa.nimstudio.core.ImageCatalog
import com.siamaehwa.nimstudio.core.ImageGenParams
import com.siamaehwa.nimstudio.core.NimErrors
import com.siamaehwa.nimstudio.core.NimModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SizePreset(val label: String, val width: Int, val height: Int)

data class ImageUiState(
    val modelsLoading: Boolean = false,
    val imageModels: List<NimModel> = emptyList(),
    val selectedModel: String? = null,
    val prompt: String = "",
    val negative: String = "",
    val sizeIndex: Int = 0,
    val steps: Int = 30,
    val seed: String = "",
    val generating: Boolean = false,
    val elapsedSec: Int = 0,
    val results: List<ByteArray> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val hasKey: Boolean? = null,
)

class ImageViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        val sizes = listOf(
            SizePreset("1:1 · 1024", 1024, 1024),
            SizePreset("가로 · 1152×896", 1152, 896),
            SizePreset("세로 · 896×1152", 896, 1152),
            SizePreset("정사각 · 768", 768, 768),
        )
    }

    private val _state = MutableStateFlow(ImageUiState())
    val state: StateFlow<ImageUiState> = _state.asStateFlow()

    private var generateJob: Job? = null

    init {
        viewModelScope.launch {
            container.store.apiKey.collect { key ->
                _state.update { it.copy(hasKey = !key.isNullOrBlank()) }
            }
        }
        refreshModels()
    }

    fun refreshModels() {
        if (_state.value.modelsLoading) return
        viewModelScope.launch {
            val catalog = ImageCatalog.merge(emptyList())
            _state.update { s ->
                s.copy(
                    modelsLoading = true,
                    imageModels = catalog,
                    selectedModel = s.selectedModel
                        ?.takeIf { id -> catalog.any { it.id == id } }
                        ?: preferredImageModel(catalog),
                )
            }
            val models = try {
                container.repository.models()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Exception) {
                _state.update { it.copy(modelsLoading = false) }
                return@launch
            }
            val imageModels = ImageCatalog.merge(models)
            _state.update { s ->
                val current = s.selectedModel
                val selected = if (current != null && imageModels.any { it.id == current }) {
                    current
                } else {
                    preferredImageModel(imageModels)
                }
                s.copy(
                    modelsLoading = false,
                    imageModels = imageModels,
                    selectedModel = selected,
                    error = null,
                )
            }
        }
    }

    private fun preferredImageModel(models: List<NimModel>): String? {
        val preferred = listOf(
            "black-forest-labs/flux.1-dev",
            "black-forest-labs/flux.1-schnell",
            "stabilityai/stable-diffusion-xl",
            "stabilityai/sdxl-turbo",
        )
        return preferred.firstOrNull { p -> models.any { it.id == p } } ?: models.firstOrNull()?.id
    }

    fun pickModel(id: String) {
        val spec = ImageCatalog.spec(id)
        _state.update {
            it.copy(
                selectedModel = id,
                steps = spec?.defaultSteps?.coerceIn(4, 50) ?: it.steps,
            )
        }
    }

    fun setPrompt(value: String) {
        _state.update { it.copy(prompt = value) }
    }

    fun setNegative(value: String) {
        _state.update { it.copy(negative = value) }
    }

    fun pickSize(index: Int) {
        _state.update { it.copy(sizeIndex = index.coerceIn(0, sizes.lastIndex)) }
    }

    fun setSteps(value: Int) {
        _state.update { it.copy(steps = value.coerceIn(4, 50)) }
    }

    fun setSeed(value: String) {
        _state.update { it.copy(seed = value.filter { ch -> ch.isDigit() }.take(12)) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun cancel() {
        generateJob?.cancel()
    }

    fun generate() {
        val snapshot = _state.value
        if (snapshot.generating) return
        if (snapshot.hasKey != true) {
            _state.update { it.copy(error = "API 키를 먼저 설정하세요.") }
            return
        }
        val model = snapshot.selectedModel
        if (model == null) {
            _state.update { it.copy(error = "이미지 모델을 선택하세요.") }
            return
        }
        val prompt = snapshot.prompt.trim()
        if (prompt.isEmpty()) {
            _state.update { it.copy(error = "프롬프트를 입력하세요.") }
            return
        }
        val spec = ImageCatalog.spec(model)
        var size = sizes[snapshot.sizeIndex]
        val sizeNotice = if (spec?.squareOnly == true &&
            (size.width != 1024 || size.height != 1024)
        ) {
            size = sizes[0]
            "이 모델은 1024×1024만 지원해 정사각형으로 생성했습니다."
        } else {
            null
        }
        val seed = snapshot.seed.toLongOrNull()
        val params = ImageGenParams(
            model = model,
            prompt = prompt,
            negativePrompt = snapshot.negative.trim(),
            width = size.width,
            height = size.height,
            steps = spec?.fixedSteps ?: snapshot.steps,
            seed = seed,
        )

        _state.update {
            it.copy(generating = true, elapsedSec = 0, error = null, notice = null)
        }

        generateJob = viewModelScope.launch {
            val ticker = launch {
                var seconds = 0
                while (isActive) {
                    delay(1000)
                    seconds += 1
                    _state.update { it.copy(elapsedSec = seconds) }
                }
            }
            try {
                val images = container.repository.generateImages(params)
                val saved = images.mapNotNull { image ->
                    runCatching {
                        container.library.saveImage(
                            bytes = image.bytes,
                            prompt = prompt,
                            source = "이미지 · $model",
                        )
                    }.getOrNull()
                }
                val savedNotice = if (saved.isNotEmpty()) {
                    "라이브러리에 자동 저장했습니다 (${saved.size}장)"
                } else {
                    null
                }
                _state.update {
                    it.copy(
                        generating = false,
                        results = images.map { image -> image.bytes },
                        notice = listOfNotNull(sizeNotice, savedNotice)
                            .joinToString(" ")
                            .ifBlank { null },
                    )
                }
            } catch (c: CancellationException) {
                _state.update { it.copy(generating = false) }
                throw c
            } catch (t: Exception) {
                _state.update {
                    it.copy(generating = false, error = NimErrors.friendlyFor(t))
                }
            } finally {
                ticker.cancel()
            }
        }
    }

    fun saveToGallery(index: Int) {
        val bytes = _state.value.results.getOrNull(index) ?: return
        viewModelScope.launch {
            val ok = runCatching { container.library.exportBytesToGallery(bytes) }
                .getOrDefault(false)
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
}
