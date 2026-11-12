package com.siamaehwa.nimstudio

import com.siamaehwa.nimstudio.core.hf.HfAvailability
import com.siamaehwa.nimstudio.core.hf.HfHub
import com.siamaehwa.nimstudio.core.hf.HfPayloads
import com.siamaehwa.nimstudio.core.hf.HfTask
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
          }
        ]
    """.trimIndent()

    @Test
    fun parsesListMappingAndTasks() {
        val models = HfHub.parseModelList(listBody)
        assertEquals(4, models.size)

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
                prompt = "a cat",
                width = 512,
                height = 512,
                steps = 4,
                seed = null,
                guidance = null,
            ),
        ).jsonObject
        assertEquals("a cat", fal["prompt"]!!.jsonPrimitive.content)
        assertEquals(512, fal["image_size"]!!.jsonObject["width"]!!.jsonPrimitive.content.toInt())
        assertFalse(fal.containsKey("seed"))
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
}
