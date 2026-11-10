package com.siamaehwa.nimstudio

import com.siamaehwa.nimstudio.core.ModelCategory
import com.siamaehwa.nimstudio.core.ModelKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelKindsTest {

    @Test
    fun chatModelsDetected() {
        assertTrue(ModelKinds.isLikelyChat("meta/llama-3.1-8b-instruct"))
        assertTrue(ModelKinds.isLikelyChat("deepseek-ai/deepseek-r1"))
        assertEquals(ModelCategory.Chat, ModelKinds.category("qwen/qwen2.5-coder-32b-instruct"))
    }

    @Test
    fun imageModelsDetected() {
        assertTrue(ModelKinds.isLikelyImage("stabilityai/stable-diffusion-xl"))
        assertTrue(ModelKinds.isLikelyImage("black-forest-labs/flux.1-dev"))
    }

    @Test
    fun nonChatModelsDetected() {
        assertEquals(ModelCategory.Other, ModelKinds.category("nvidia/nv-embed-v1"))
        assertEquals(ModelCategory.Other, ModelKinds.category("nvidia/llama-3.2-nv-rerankqa-1b-v2"))
    }
}
