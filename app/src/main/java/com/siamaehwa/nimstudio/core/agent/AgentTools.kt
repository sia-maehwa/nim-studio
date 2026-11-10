package com.siamaehwa.nimstudio.core.agent

import com.siamaehwa.nimstudio.core.AgentToolCall
import com.siamaehwa.nimstudio.core.ToolFunctionSpec
import com.siamaehwa.nimstudio.core.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class UndoInfo(val path: String, val previousContent: String?)

data class ToolOutcome(
    val summary: String,
    val detail: String,
    val result: String,
    val isError: Boolean = false,
    val undo: UndoInfo? = null,
)

class AgentTools(private val workspace: Workspace) {

    companion object {
        private const val MAX_RESULT = 12_000
        private const val MAX_DETAIL = 1_500

        val specs: List<ToolSpec> = listOf(
            tool(
                name = "list_files",
                description = "작업 폴더의 하위 파일/폴더 목록을 봅니다. path는 상대 경로(기본 \".\").",
                required = emptyList(),
                params = { str("path", "상대 경로. 기본값 \".\"") },
            ),
            tool(
                name = "read_file",
                description = "텍스트 파일을 줄 번호와 함께 읽습니다. 큰 파일은 offset/limit으로 나눠 읽으세요.",
                required = listOf("path"),
                params = {
                    str("path", "파일 상대 경로")
                    num("offset", "시작 줄(1부터). 기본 1")
                    num("limit", "읽을 줄 수. 기본 400, 최대 800")
                },
            ),
            tool(
                name = "write_file",
                description = "파일을 새로 만들거나 전체 내용을 덮어씁니다. 기존 파일 수정에는 edit_file을 우선 사용하세요.",
                required = listOf("path", "content"),
                params = {
                    str("path", "파일 상대 경로")
                    str("content", "저장할 전체 내용")
                },
            ),
            tool(
                name = "edit_file",
                description = "파일에서 old_string과 정확히 일치하는 부분을 new_string으로 바꿉니다. 수정 전 read_file로 확인하세요.",
                required = listOf("path", "old_string", "new_string"),
                params = {
                    str("path", "파일 상대 경로")
                    str("old_string", "바꿀 기존 문자열(공백/줄바꿈 포함 정확히)")
                    str("new_string", "새 문자열")
                    bool("replace_all", "모든 일치 항목 교체 여부(기본 false)")
                },
            ),
            tool(
                name = "search_files",
                description = "작업 폴더의 텍스트 파일 내용에서 문자열을 검색합니다.",
                required = listOf("query"),
                params = {
                    str("query", "검색할 문자열")
                    str("path", "검색 범위 상대 경로(기본 \".\")")
                },
            ),
        )

        private fun tool(
            name: String,
            description: String,
            required: List<String>,
            params: JsonObjectBuilder.() -> Unit,
        ): ToolSpec = ToolSpec(
            function = ToolFunctionSpec(
                name = name,
                description = description,
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") { params() }
                    if (required.isNotEmpty()) {
                        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
                    }
                },
            ),
        )

        private fun JsonObjectBuilder.str(name: String, description: String) =
            putJsonObject(name) {
                put("type", "string")
                put("description", description)
            }

        private fun JsonObjectBuilder.num(name: String, description: String) =
            putJsonObject(name) {
                put("type", "integer")
                put("description", description)
            }

        private fun JsonObjectBuilder.bool(name: String, description: String) =
            putJsonObject(name) {
                put("type", "boolean")
                put("description", description)
            }
    }

    suspend fun execute(call: AgentToolCall): ToolOutcome {
        val args = runCatching {
            Json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject
        }.getOrNull()
            ?: return errorOutcome("인자 JSON을 해석할 수 없습니다: ${call.arguments.take(200)}")

        return try {
            when (call.name) {
                "list_files" -> listFiles(args)
                "read_file" -> readFile(args)
                "write_file" -> writeFile(args)
                "edit_file" -> editFile(args)
                "search_files" -> searchFiles(args)
                else -> errorOutcome("알 수 없는 도구입니다: ${call.name}")
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            errorOutcome(t.message ?: "도구 실행 오류")
        }
    }

    private suspend fun listFiles(args: JsonObject): ToolOutcome {
        val path = arg(args, "path") ?: "."
        val entries = workspace.list(path)
        val body = if (entries.isEmpty()) {
            "빈 폴더입니다: $path"
        } else {
            buildString {
                entries.take(300).forEach { entry ->
                    if (entry.isDir) {
                        append("[DIR] ").append(entry.name).append("/\n")
                    } else {
                        append("[FILE] ").append(entry.name)
                            .append(" (").append(entry.size).append("B)\n")
                    }
                }
                if (entries.size > 300) append("…외 ${entries.size - 300}개")
            }
        }
        return ok("list_files · ${path.ifEmpty { "." }}", body, body)
    }

    private suspend fun readFile(args: JsonObject): ToolOutcome {
        val path = arg(args, "path") ?: return errorOutcome("path가 필요합니다")
        val content = workspace.read(path)
        val lines = content.lines()
        val offset = (intArg(args, "offset") ?: 1).coerceAtLeast(1)
        val limit = (intArg(args, "limit") ?: 400).coerceIn(1, 800)
        val slice = lines.drop(offset - 1).take(limit)
        val numbered = slice.mapIndexed { index, line -> "${offset + index}| $line" }
            .joinToString("\n")
        val header = "총 ${lines.size}줄 · ${offset}~${offset + slice.size - 1}줄"
        val body = if (numbered.isEmpty()) "$header\n(내용 없음)" else "$header\n$numbered"
        return ok("read_file · $path", body, body)
    }

    private suspend fun writeFile(args: JsonObject): ToolOutcome {
        val path = arg(args, "path") ?: return errorOutcome("path가 필요합니다")
        val content = arg(args, "content") ?: return errorOutcome("content가 필요합니다")
        val previous = workspace.readIfExists(path)
        workspace.write(path, content)
        val detail = "저장했습니다: $path (${content.length}자" +
            if (previous != null) ", 이전 ${previous.length}자)" else ", 새 파일)"
        return ok("write_file · $path", detail, detail, undo = UndoInfo(path, previous))
    }

    private suspend fun editFile(args: JsonObject): ToolOutcome {
        val path = arg(args, "path") ?: return errorOutcome("path가 필요합니다")
        val oldStr = arg(args, "old_string") ?: return errorOutcome("old_string이 필요합니다")
        val newStr = arg(args, "new_string") ?: return errorOutcome("new_string이 필요합니다")
        if (oldStr.isEmpty()) return errorOutcome("old_string이 비어 있습니다")
        val current = workspace.readIfExists(path)
            ?: return errorOutcome("파일이 없습니다: $path")
        val count = countOccurrences(current, oldStr)
        if (count == 0) {
            return errorOutcome("old_string을 찾지 못했습니다. read_file로 현재 내용을 확인하세요.")
        }
        val replaceAll = boolArg(args, "replace_all")
        val updated = if (replaceAll) {
            current.replace(oldStr, newStr)
        } else {
            current.replaceFirst(oldStr, newStr)
        }
        workspace.write(path, updated)
        val detail = "교체 ${if (replaceAll) count else 1}건: $path (${updated.length}자)"
        return ok("edit_file · $path", detail, detail, undo = UndoInfo(path, current))
    }

    private suspend fun searchFiles(args: JsonObject): ToolOutcome {
        val query = arg(args, "query") ?: return errorOutcome("query가 필요합니다")
        val base = arg(args, "path") ?: "."
        val results = workspace.search(query, base, 60)
        val body = if (results.isEmpty()) {
            "일치하는 내용이 없습니다."
        } else {
            results.joinToString("\n")
        }
        return ok("search_files · $query", body, body)
    }

    private fun arg(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun intArg(args: JsonObject, key: String): Int? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun boolArg(args: JsonObject, key: String): Boolean =
        (args[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun ok(
        summary: String,
        detail: String,
        result: String,
        undo: UndoInfo? = null,
    ): ToolOutcome = ToolOutcome(
        summary = summary,
        detail = clip(detail, MAX_DETAIL),
        result = clip(result, MAX_RESULT),
        isError = false,
        undo = undo,
    )

    private fun errorOutcome(message: String): ToolOutcome = ToolOutcome(
        summary = "오류",
        detail = message.take(MAX_DETAIL),
        result = "ERROR: ${message.take(1000)}",
        isError = true,
    )

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "\n… (${text.length - max}자 생략)"
}
