package com.siamaehwa.nimstudio

import com.siamaehwa.nimstudio.core.hf.HfAvailability
import com.siamaehwa.nimstudio.core.hf.HfHub
import com.siamaehwa.nimstudio.core.hf.HfPayloads
import com.siamaehwa.nimstudio.core.hf.HfTask
import com.siamaehwa.nimstudio.core.hf.falQueueUrls
import com.siamaehwa.nimstudio.core.hf.sseDataPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfLogicTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val listBody = """
        [
          {
            "id": "Qwen/Qwen2.5-7B-Instruct",
            "pipeline_tag": "text-generation",
            "downloads": 12345,
            "likes": 10,
            "tags": ["license:apache-2.0"],
            "gated": false,
            "inferenceProviderMapping": [
              {
                "provider": "novita",
                "providerId": "qwen/qwen2.5-7b-instruct",
                "status": "live",
                "task": "conversational",
                "features": {"toolCalling": true}
              }
            ]
          },
          {
            "id": "black-forest-labs/FLUX.1-dev",
            "pipeline_tag": "text-to-image",
            "gated": "auto",
            "private": false,
            "inferenceProviderMapping": [
              {
                "provider": "fal-ai",
                "providerId": "fal-ai/flux/dev",
                "status": "live",
                "task": "text-to-image"
              }
            ]
          },
          {
            "id": "meta-llama/Llama-3.1-8B",
            "pipeline_tag": "text-generation",
            "gated": "manual",
            "inferenceProviderMapping": []
          },
          {
            "id": "org/custom-model",
            "pipeline_tag": "text-generation"
          },
          {
            "id": "Qwen/Qwen-Image-Edit",
            "pipeline_tag": "image-to-image",
            "gated": false,
            "inferenceProviderMapping": [
              {
                "provider": "fal-ai",
                "providerId": "fal-ai/qwen-image-edit",
                "status": "live",
                "task": "image-to-image"
              }
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun parsesListMappingAndTasks() {
        val models = HfHub.parseModelList(listBody)
        assertEquals(5, models.size)

        val qwen = models[0]
        assertEquals(HfTask.Chat, qwen.task)
        assertEquals(1, qwen.providers.size)
        assertEquals("novita", qwen.providers[0].provider)
        assertTrue(qwen.providers[0].supportsTools)
        assertEquals("apache-2.0", qwen.license)
        assertEquals(HfAvailability.Ready, qwen.availabilityFor(HfTask.Chat, tokenPresent = false))

        val flux = models[1]
        assertEquals(HfTask.TextToImage, flux.task)
        assertEquals("auto", flux.gated)
        assertEquals(
            HfAvailability.Gated,
            flux.availabilityFor(HfTask.TextToImage, tokenPresent = false),
        )
        assertEquals(
            HfAvailability.Ready,
            flux.availabilityFor(HfTask.TextToImage, tokenPresent = true),
        )

        val edit = models[4]
        assertEquals(HfTask.ImageToImage, edit.task)
        assertEquals(
            HfAvailability.Ready,
            edit.availabilityFor(HfTask.ImageToImage, tokenPresent = true),
        )
        assertEquals(
            "fal-ai/qwen-image-edit",
            edit.liveProviders(HfTask.ImageToImage).first().providerId,
        )
    }

    @Test
    fun gatedAndDeploymentStates() {
        val models = HfHub.parseModelList(listBody)
        val llama = models[2]
        assertEquals("manual", llama.gated)
        assertEquals(HfAvailability.Gated, llama.availabilityFor(HfTask.Chat, false))
        assertEquals(HfAvailability.NeedsDeployment, llama.availabilityFor(HfTask.Chat, true))

        val custom = models[3]
        assertNull(custom.gated)
        assertEquals(HfAvailability.NeedsDeployment, custom.availabilityFor(HfTask.Chat, true))
        assertEquals(0, custom.providers.size)
    }

    @Test
    fun parsesDetailsObjectMapping() {
        val body = """
            {
              "id": "x/y",
              "pipeline_tag": "text-to-image",
              "inferenceProviderMapping": {
                "hf-inference": {
                  "status": "live",
                  "task": "text-to-image",
                  "providerId": null
                },
                "fal-ai": {
                  "status": "error",
                  "task": "text-to-image",
                  "providerId": "fal-ai/x"
                }
              }
            }
        """.trimIndent()
        val model = HfHub.parseModelDetails(body)
        assertNotNull(model)
        assertEquals(2, model!!.providers.size)
        assertEquals(1, model.liveProviders(HfTask.TextToImage).size)
        assertEquals("fal-ai/x", model.providers.first { it.provider == "fal-ai" }.providerId)
        assertEquals(HfAvailability.Ready, model.availabilityFor(HfTask.TextToImage, true))
    }

    @Test
    fun privateModelNeedsAuthWithoutToken() {
        val body = """[{"id":"org/private","pipeline_tag":"text-generation","private":true}]"""
        val model = HfHub.parseModelList(body).single()
        assertEquals(HfAvailability.AuthRequired, model.availabilityFor(HfTask.Chat, false))
    }

    @Test
    fun buildsChatPayload() {
        val payload = json.parseToJsonElement(
            HfPayloads.chatRequest(
                model = "Qwen/Qwen2.5-7B-Instruct:novita",
                messages = listOf(
                    com.siamaehwa.nimstudio.core.hf.HfChatMessage("user", "hello"),
                ),
                maxTokens = 256,
                stream = true,
            ),
        ).jsonObject
        assertEquals("Qwen/Qwen2.5-7B-Instruct:novita", payload["model"]!!.jsonPrimitive.content)
        assertTrue(payload["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(256, payload["max_tokens"]!!.jsonPrimitive.content.toInt())
        val messages = payload["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("hello", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildsImagePayloads() {
        val hf = json.parseToJsonElement(
            HfPayloads.imageRequest(
                prompt = "a cat",
                negativePrompt = "blurry",
                width = 1024,
                height = 768,
                steps = 28,
                seed = 42L,
                guidance = 3.5,
            ),
        ).jsonObject
        assertEquals("a cat", hf["inputs"]!!.jsonPrimitive.content)
        val parameters = hf["parameters"]!!.jsonObject
        assertEquals("blurry", parameters["negative_prompt"]!!.jsonPrimitive.content)
        assertEquals(1024, parameters["width"]!!.jsonPrimitive.content.toInt())
        assertEquals(42, parameters["seed"]!!.jsonPrimitive.content.toInt())

        val fal = json.parseToJsonElement(
            HfPayloads.falImageRequest(
                providerId = "fal-ai/flux/dev",
                prompt = "a cat",
                negativePrompt = "blurry",
                width = 512,
                height = 512,
                steps = 28,
                seed = null,
                guidance = null,
            ),
        ).jsonObject
        assertEquals("a cat", fal["prompt"]!!.jsonPrimitive.content)
        assertEquals(512, fal["image_size"]!!.jsonObject["width"]!!.jsonPrimitive.content.toInt())
        assertFalse(fal.containsKey("seed"))
        assertFalse(fal.containsKey("negative_prompt"))

        val sdxl = json.parseToJsonElement(
            HfPayloads.falImageRequest(
                providerId = "fal-ai/fast-sdxl",
                prompt = "a cat",
                negativePrompt = "blurry",
                width = 1024,
                height = 1024,
                steps = 28,
                seed = 1L,
                guidance = null,
            ),
        ).jsonObject
        assertEquals("blurry", sdxl["negative_prompt"]!!.jsonPrimitive.content)

        val schnell = json.parseToJsonElement(
            HfPayloads.falImageRequest(
                providerId = "fal-ai/flux/schnell",
                prompt = "a cat",
                negativePrompt = "",
                width = 512,
                height = 512,
                steps = 28,
                seed = null,
                guidance = null,
            ),
        ).jsonObject
        assertEquals(12, schnell["num_inference_steps"]!!.jsonPrimitive.content.toInt())

        val zImage = json.parseToJsonElement(
            HfPayloads.falImageRequest(
                providerId = "fal-ai/z-image/turbo",
                prompt = "a cat",
                negativePrompt = "",
                width = 512,
                height = 512,
                steps = 28,
                seed = null,
                guidance = null,
            ),
        ).jsonObject
        assertEquals(8, zImage["num_inference_steps"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun buildsFalEditPayloads() {
        val dataUrl = "data:image/jpeg;base64,AAAA"
        val qwen = json.parseToJsonElement(
            HfPayloads.falEditRequest(
                providerId = "fal-ai/qwen-image-edit",
                prompt = "edit it",
                negativePrompt = "blurry",
                imageDataUrl = dataUrl,
                steps = 30,
                seed = null,
                guidance = null,
            ),
        ).jsonObject
        assertEquals(dataUrl, qwen["image_url"]!!.jsonPrimitive.content)
        assertFalse(qwen.containsKey("image_urls"))
        assertEquals("blurry", qwen["negative_prompt"]!!.jsonPrimitive.content)

        val flux2 = json.parseToJsonElement(
            HfPayloads.falEditRequest(
                providerId = "fal-ai/flux-2/edit",
                prompt = "edit it",
                negativePrompt = "blurry",
                imageDataUrl = dataUrl,
                steps = 28,
                seed = null,
                guidance = null,
            ),
        ).jsonObject
        assertFalse(flux2.containsKey("image_url"))
        assertEquals(
            dataUrl,
            flux2["image_urls"]!!.jsonArray.first()!!.jsonPrimitive.content,
        )
        assertFalse(flux2.containsKey("negative_prompt"))

        val klein = json.parseToJsonElement(
            HfPayloads.falEditRequest(
                providerId = "fal-ai/flux-2/klein/4b/distilled/edit",
                prompt = "edit it",
                negativePrompt = "",
                imageDataUrl = dataUrl,
                steps = 28,
                seed = null,
                guidance = null,
            ),
        ).jsonObject
        assertEquals(8, klein["num_inference_steps"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun sseAndBase64Helpers() {
        assertEquals("{\"a\":1}", sseDataPayload("data: {\"a\":1}"))
        assertEquals("[DONE]", sseDataPayload("data: [DONE]"))
        assertNull(sseDataPayload("event: ping"))
        assertNull(sseDataPayload("data:"))

        val raw = "A".repeat(120)
        assertEquals(
            raw,
            HfHub.extractBase64Image("{\"images\":[\"data:image/png;base64,$raw\"]}"),
        )
        assertEquals(
            raw,
            HfHub.extractBase64Image("{\"output\":{\"base64\":\"$raw\"}}"),
        )
        assertNull(HfHub.extractBase64Image("{\"images\":[{\"url\":\"https://x/y.png\"}]}"))
        assertNull(HfHub.extractBase64Image("{\"error\":\"boom\"}"))
    }

    @Test
    fun falQueueUrlsFollowRouterBase() {
        val requestUrl =
            "https://router.huggingface.co/fal-ai/fal-ai/flux/dev?_subdomain=queue"
        val (status, result) = falQueueUrls(
            "https://queue.fal.run/fal-ai/flux/dev/requests/abc",
            requestUrl,
        )
        assertEquals(
            "https://router.huggingface.co/fal-ai/fal-ai/flux/dev/requests/abc/status?_subdomain=queue",
            status,
        )
        assertEquals(
            "https://router.huggingface.co/fal-ai/fal-ai/flux/dev/requests/abc?_subdomain=queue",
            result,
        )

        val (directStatus, directResult) = falQueueUrls(
            "https://queue.fal.run/fal-ai/flux/dev/requests/abc",
            "https://queue.fal.run/fal-ai/flux/dev",
        )
        assertEquals("https://queue.fal.run/fal-ai/flux/dev/requests/abc/status", directStatus)
        assertEquals("https://queue.fal.run/fal-ai/flux/dev/requests/abc", directResult)
    }
}
