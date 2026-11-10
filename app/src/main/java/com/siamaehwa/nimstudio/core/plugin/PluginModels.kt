package com.siamaehwa.nimstudio.core.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class PluginManifest(
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val tools: List<PluginTool> = emptyList(),
)

@Serializable
data class PluginTool(
    val name: String,
    val description: String = "",
    val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    },
    val action: PluginAction,
)

@Serializable
data class PluginAction(
    val type: String = "http",
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)
