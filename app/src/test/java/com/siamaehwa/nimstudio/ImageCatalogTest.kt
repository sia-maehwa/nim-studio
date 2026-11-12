package com.siamaehwa.nimstudio

import com.siamaehwa.nimstudio.core.ImageCatalog
import com.siamaehwa.nimstudio.core.ImageSupport
import com.siamaehwa.nimstudio.core.NimModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCatalogTest {

    @Test
    fun catalogContainsHostedImageModels() {
        assertTrue(ImageCatalog.specs.any { it.id == "black-forest-labs/flux.1-dev" })
        assertTrue(ImageCatalog.specs.any { it.id == "stabilityai/stable-diffusion-xl" })
        assertTrue(ImageCatalog.specs.any { it.id == "stabilityai/stable-diffusion-3.5-large" })
    }

    @Test
    fun deprecatedModelsAreNotOfferedAsCurated() {
        assertEquals(
            ImageSupport.Deprecated,
            ImageCatalog.support("stabilityai/stable-diffusion-3.5-large"),
        )
        assertFalse(
            ImageCatalog.curatedModels().any {
                it.id == "stabilityai/stable-diffusion-3.5-large"
            },
        )
        assertTrue(ImageCatalog.curatedModels().any { it.id == "black-forest-labs/flux.1-dev" })
    }

    @Test
    fun deprecatedRemoteEntryIsNotReintroducedByMerge() {
        val merged = ImageCatalog.merge(
            listOf(NimModel(id = "stabilityai/stable-diffusion-3.5-large")),
        )
        assertFalse(merged.any { it.id == "stabilityai/stable-diffusion-3.5-large" })
    }

    @Test
    fun schnellHasFixedSteps() {
        assertEquals(4, ImageCatalog.spec("black-forest-labs/flux.1-schnell")?.fixedSteps)
    }

    @Test
    fun sdxlIsSquareOnly() {
        assertTrue(ImageCatalog.spec("stabilityai/stable-diffusion-xl")?.squareOnly == true)
    }

    @Test
    fun mergeKeepsCuratedModelsAndAddsRemoteWithoutDuplicates() {
        val merged = ImageCatalog.merge(
            listOf(
                NimModel(id = "black-forest-labs/flux.1-dev"),
                NimModel(id = "acme/sd3-turbo"),
            ),
        )
        assertEquals(ImageCatalog.activeSpecs.size + 1, merged.size)
        assertEquals(1, merged.count { it.id == "black-forest-labs/flux.1-dev" })
        assertTrue(merged.any { it.id == "acme/sd3-turbo" })
    }

    @Test
    fun appendCuratedKeepsRemoteChatModels() {
        val merged = ImageCatalog.appendCurated(
            listOf(
                NimModel(id = "meta/llama-3.3-70b-instruct"),
                NimModel(id = "black-forest-labs/flux.1-dev"),
            ),
        )
        assertEquals(1 + ImageCatalog.activeSpecs.size, merged.size)
        assertTrue(merged.any { it.id == "meta/llama-3.3-70b-instruct" })
        assertEquals(1, merged.count { it.id == "black-forest-labs/flux.1-dev" })
    }

    @Test
    fun labelFallsBackToModelId() {
        assertEquals("vendor/unknown-model", ImageCatalog.label("vendor/unknown-model"))
        assertEquals("FLUX.1 Dev", ImageCatalog.label("black-forest-labs/flux.1-dev"))
    }
}
