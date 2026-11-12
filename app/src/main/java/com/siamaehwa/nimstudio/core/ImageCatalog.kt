package com.siamaehwa.nimstudio.core

enum class ImageFamily {
    Flux,
    Klein,
    Sdxl,
    Sd3,
}

enum class ImageSupport(val label: String) {
    Verified("지원 확인"),
    Unverified("검증되지 않음"),
    Deprecated("지원 중단"),
}

data class ImageModelSpec(
    val id: String,
    val label: String,
    val family: ImageFamily,
    val defaultSteps: Int,
    val fixedSteps: Int? = null,
    val cfgScale: Double? = null,
    val supportsNegative: Boolean = false,
    val squareOnly: Boolean = false,
    val support: ImageSupport = ImageSupport.Verified,
    val note: String? = null,
)

object ImageCatalog {

    val specs = listOf(
        ImageModelSpec(
            id = "black-forest-labs/flux.1-dev",
            label = "FLUX.1 Dev",
            family = ImageFamily.Flux,
            defaultSteps = 50,
            cfgScale = 3.5,
        ),
        ImageModelSpec(
            id = "black-forest-labs/flux.1-schnell",
            label = "FLUX.1 Schnell",
            family = ImageFamily.Flux,
            defaultSteps = 4,
            fixedSteps = 4,
        ),
        ImageModelSpec(
            id = "black-forest-labs/flux.2-klein-4b",
            label = "FLUX.2 Klein 4B",
            family = ImageFamily.Klein,
            defaultSteps = 4,
        ),
        ImageModelSpec(
            id = "stabilityai/stable-diffusion-xl",
            label = "Stable Diffusion XL",
            family = ImageFamily.Sdxl,
            defaultSteps = 30,
            cfgScale = 7.5,
            supportsNegative = true,
            squareOnly = true,
        ),
        ImageModelSpec(
            id = "stabilityai/stable-diffusion-3-medium",
            label = "Stable Diffusion 3 Medium",
            family = ImageFamily.Sd3,
            defaultSteps = 50,
            cfgScale = 3.5,
            supportsNegative = true,
        ),
        ImageModelSpec(
            id = "stabilityai/stable-diffusion-3.5-large",
            label = "Stable Diffusion 3.5 Large (지원 중단)",
            family = ImageFamily.Sd3,
            defaultSteps = 50,
            cfgScale = 3.5,
            supportsNegative = true,
            support = ImageSupport.Deprecated,
            note = "NVIDIA API 레퍼런스에 없는 모델입니다. 호출 시 404가 발생합니다.",
        ),
    )

    /** 실제 NVIDIA API 문서에서 엔드포인트가 확인된 모델만 목록에 추가한다. */
    val activeSpecs: List<ImageModelSpec> = specs.filter {
        it.support != ImageSupport.Deprecated
    }

    fun spec(id: String): ImageModelSpec? = specs.firstOrNull { it.id == id }

    fun label(id: String): String = spec(id)?.label ?: id

    fun support(id: String): ImageSupport =
        spec(id)?.support ?: ImageSupport.Unverified

    fun curatedModels(): List<NimModel> = activeSpecs.map { spec ->
        NimModel(
            id = spec.id,
            objectType = "model",
            ownedBy = spec.id.substringBefore('/'),
        )
    }

    fun merge(remote: List<NimModel>): List<NimModel> {
        val curated = curatedModels()
        val extras = remote.filter { model ->
            ModelKinds.isLikelyImage(model.id) &&
                specs.none { it.id == model.id }
        }
        return (curated + extras).distinctBy { it.id }
    }

    fun appendCurated(remote: List<NimModel>): List<NimModel> {
        val missing = curatedModels().filter { curated -> remote.none { it.id == curated.id } }
        return (remote + missing).distinctBy { it.id }.sortedBy { it.id }
    }
}
