package com.siamaehwa.nimstudio.core.plugin

import android.content.Context
import com.siamaehwa.nimstudio.core.ToolFunctionSpec
import com.siamaehwa.nimstudio.core.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class PluginRepository(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val manifestSerializer = ListSerializer(PluginManifest.serializer())
    private val file = File(appContext.filesDir, "plugins/plugins.json")
    private val mutex = Mutex()
    private val _plugins = MutableStateFlow<List<PluginManifest>>(emptyList())
    val plugins: StateFlow<List<PluginManifest>> = _plugins.asStateFlow()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun reload() = withContext(Dispatchers.IO) {
        mutex.withLock { _plugins.value = readAll() }
    }

    suspend fun addFromJson(raw: String): Result<Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val text = raw.trim()
                require(text.isNotEmpty()) { "JSON이 비어 있습니다" }
                val parsed = if (text.startsWith("[")) {
                    json.decodeFromString(manifestSerializer, text)
                } else {
                    listOf(json.decodeFromString(PluginManifest.serializer(), text))
                }
                val valid = parsed.filter { manifest ->
                    manifest.name.isNotBlank() &&
                        manifest.tools.isNotEmpty() &&
                        manifest.tools.all { tool ->
                            validToolName(tool.name) && tool.action.url.isNotBlank()
                        }
                }
                require(valid.isNotEmpty()) { "유효한 플러그인이 없습니다(이름·tools·url 확인)" }
                val merged = readAll().toMutableList()
                valid.forEach { plugin ->
                    val index = merged.indexOfFirst { it.name == plugin.name }
                    if (index >= 0) merged[index] = plugin else merged.add(plugin)
                }
                writeAll(merged)
                _plugins.value = merged
                Result.success(valid.size)
            } catch (t: Exception) {
                Result.failure(t)
            }
        }
    }

    suspend fun setEnabled(name: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = readAll().map { plugin ->
                if (plugin.name == name) plugin.copy(enabled = enabled) else plugin
            }
            writeAll(updated)
            _plugins.value = updated
        }
    }

    suspend fun remove(name: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = readAll().filterNot { it.name == name }
            writeAll(updated)
            _plugins.value = updated
        }
    }

    fun toolSpecs(list: List<PluginManifest>): List<ToolSpec> =
        list.asSequence()
            .filter { it.enabled }
            .flatMap { it.tools.asSequence() }
            .filter { validToolName(it.name) }
            .distinctBy { it.name }
            .map { tool ->
                ToolSpec(
                    function = ToolFunctionSpec(
                        name = tool.name,
                        description = tool.description.ifBlank { "플러그인 도구: ${tool.name}" },
                        parameters = schemaOf(tool.parameters),
                    ),
                )
            }
            .toList()

    fun findTool(list: List<PluginManifest>, name: String): PluginTool? =
        list.asSequence()
            .filter { it.enabled }
            .flatMap { it.tools.asSequence() }
            .firstOrNull { it.name == name }

    suspend fun execute(tool: PluginTool, args: JsonObject): String = withContext(Dispatchers.IO) {
        val action = tool.action
        require(action.type == "http") { "지원하지 않는 action 형식: ${action.type}" }
        val method = action.method.uppercase().ifBlank { "GET" }
        val url = render(action.url, args, encode = true)
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "URL은 http(s)로 시작해야 합니다"
        }
        val builder = Request.Builder().url(url)
        action.headers.forEach { (key, value) ->
            if (key.isNotBlank() && !key.equals("Host", ignoreCase = true)) {
                builder.header(key, render(value, args, encode = false))
            }
        }
        val bodyText = action.body?.let { render(it, args, encode = false) }
        when (method) {
            "GET", "HEAD", "DELETE" -> builder.method(method, null)
            else -> builder.method(
                method,
                (bodyText ?: "{}").toRequestBody("application/json".toMediaType()),
            )
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val clipped = if (text.length > 20_000) {
                text.take(20_000) + "\n… (${text.length - 20_000}자 생략)"
            } else {
                text
            }
            "HTTP ${response.code}\n$clipped"
        }
    }

    private fun render(template: String, args: JsonObject, encode: Boolean): String {
        var out = template
        args.forEach { (key, value) ->
            val raw = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
            val replaced = if (encode) URLEncoder.encode(raw, "UTF-8") else raw
            out = out.replace("{{$key}}", replaced)
        }
        return out
    }

    private fun readAll(): List<PluginManifest> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(manifestSerializer, file.readText()) }
            .getOrDefault(emptyList())
    }

    private fun writeAll(list: List<PluginManifest>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(manifestSerializer, list))
    }

    private fun validToolName(name: String): Boolean =
        Regex("^[a-zA-Z0-9_-]{1,64}$").matches(name)

    private fun schemaOf(raw: JsonObject): JsonObject =
        if (raw.containsKey("type") || raw.containsKey("properties")) {
            raw
        } else {
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            }
        }
}
