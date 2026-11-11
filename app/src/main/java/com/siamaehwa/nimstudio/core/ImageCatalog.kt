package com.siamaehwa.nimstudio.core

enum class ImageFamily {
    Flux,
    Klein,
    Sdxl,
    Sd3,
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
            label = "Stable Diffusion 3.5 Large",
            family = ImageFamily.Sd3,
            defaultSteps = 50,
            cfgScale = 3.5,
            supportsNegative = true,
        ),
    )

    fun spec(id: String): ImageModelSpec? = specs.firstOrNull { it.id == id }

    fun label(id: String): String = spec(id)?.label ?: id

    fun curatedModels(): List<NimModel> = specs.map { spec ->
        NimModel(
            id = spec.id,
            objectType = "model",
            ownedBy = spec.id.substringBefore('/'),
        )
    }

    fun merge(remote: List<NimModel>): List<NimModel> {
        val curated = curatedModels()
        val extras = remote.filter { model ->
            ModelKinds.isLikelyImage(model.id) && specs.none { it.id == model.id }
        }
        return (curated + extras).distinctBy { it.id }
    }

    fun appendCurated(remote: List<NimModel>): List<NimModel> {
        val missing = curatedModels().filter { curated -> remote.none { it.id == curated.id } }
        return (remote + missing).distinctBy { it.id }.sortedBy { it.id }
    }
}
