package com.siamaehwa.nimstudio.core

enum class ModelCategory(val label: String) {
    Chat("채팅"),
    Image("이미지"),
    Other("기타"),
}

object ModelKinds {

    private val imageHints = listOf(
        "sdxl",
        "stable-diffusion",
        "flux",
        "diffusion",
        "dalle",
        "edify",
        "kandinsky",
        "consistory",
        "playground-v",
        "sd3",
        "imagegen",
        "qwen-image",
        "qwen_image",
        "imagen",
        "seedream",
        "sana-",
        "sd-3",
    )

    private val nonChatHints = listOf(
        "embed",
        "rerank",
        "reward",
        "guard",
        "retriever",
        "gliner",
        "deplot",
        "fuyu",
        "kosmos",
        "nvclip",
        "clip",
        "seamless",
        "parakeet",
        "canary",
        "riva",
        "bge-",
        "arctic-embed",
        "esm",
        "protein",
        "molmim",
        "diffdock",
        "openfold",
    )

    fun category(id: String): ModelCategory {
        val low = id.lowercase()
        if (imageHints.any { low.contains(it) }) return ModelCategory.Image
        if (nonChatHints.any { low.contains(it) }) return ModelCategory.Other
        return ModelCategory.Chat
    }

    fun isLikelyChat(id: String): Boolean = category(id) == ModelCategory.Chat

    fun isLikelyImage(id: String): Boolean = category(id) == ModelCategory.Image
}
