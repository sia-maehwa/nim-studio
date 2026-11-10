package com.siamaehwa.nimstudio.core.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class Workspace private constructor(
    private val context: Context,
    val treeUri: Uri,
) {

    data class Node(val path: String, val docId: String, val isDir: Boolean, val size: Long)

    data class Entry(val name: String, val isDir: Boolean, val size: Long)

    class WorkspaceException(message: String) : Exception(message)

    private val resolver get() = context.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val nodes = LinkedHashMap<String, Node>()
    private val mutex = Mutex()
    private var loaded = false

    var name: String = "작업 폴더"
        private set

    var truncated: Boolean = false
        private set

    var fileCount: Int = 0
        private set

    companion object {
        private const val MAX_NODES = 3000
        private const val MAX_DEPTH = 10
        private const val MAX_READ_BYTES = 200_000
        private const val MAX_SEARCH_BYTES = 6_000_000L

        fun open(context: Context, uri: Uri): Workspace {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            return Workspace(context, uri)
        }
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { ensureIndex() }
    }

    suspend fun list(rel: String): List<Entry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = ensureIndex()
            val path = AgentPaths.normalize(rel)
                ?: throw WorkspaceException("잘못된 경로입니다: $rel")
            val dir = index[path] ?: throw WorkspaceException("폴더를 찾을 수 없습니다: $path")
            if (!dir.isDir) throw WorkspaceException("폴더가 아닙니다: $path")
            val prefix = if (path.isEmpty()) "" else "$path/"
            index.values.asSequence()
                .filter { it.path.isNotEmpty() && it.path.startsWith(prefix) }
                .filter { !it.path.removePrefix(prefix).contains('/') }
                .map { Entry(it.path.substringAfterLast('/'), it.isDir, it.size) }
                .sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
                .toList()
        }
    }

    suspend fun read(rel: String): String = withContext(Dispatchers.IO) {
        mutex.withLock { readNodeText(requireFile(rel)) }
    }

    suspend fun readIfExists(rel: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = ensureIndex()
            val path = AgentPaths.normalize(rel)
                ?: throw WorkspaceException("잘못된 경로입니다: $rel")
            val node = index[path] ?: return@withLock null
            if (node.isDir) throw WorkspaceException("폴더입니다: $path")
            readNodeText(node)
        }
    }

    suspend fun write(rel: String, content: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = ensureIndex()
            val path = AgentPaths.normalize(rel)
                ?: throw WorkspaceException("잘못된 경로입니다: $rel")
            if (path.isEmpty()) throw WorkspaceException("파일 경로가 필요합니다")
            if (AgentPaths.isBlocked(path)) {
                throw WorkspaceException("보호된 경로에는 쓸 수 없습니다: $path")
            }
            val existing = index[path]
            if (existing != null && existing.isDir) {
                throw WorkspaceException("폴더에는 쓸 수 없습니다: $path")
            }
            val parent = ensureDir(path.substringBeforeLast('/', ""))
            val docId = existing?.docId
                ?: createFile(parent, path.substringAfterLast('/'), path)
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (!writeBytes(docId, bytes)) {
                throw WorkspaceException("파일에 쓸 수 없습니다: $path")
            }
            nodes[path] = Node(path, docId, false, bytes.size.toLong())
            fileCount = nodes.values.count { !it.isDir }
        }
    }

    suspend fun delete(rel: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val node = requireFile(rel)
            val deleted = runCatching {
                DocumentsContract.deleteDocument(resolver, documentUri(node.docId))
            }.getOrDefault(false)
            if (!deleted) throw WorkspaceException("삭제할 수 없습니다: ${node.path}")
            nodes.remove(node.path)
            fileCount = nodes.values.count { !it.isDir }
        }
    }

    suspend fun search(query: String, base: String, max: Int): List<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val needle = query.trim()
                if (needle.isEmpty()) return@withLock emptyList()
                val index = ensureIndex()
                val basePath = AgentPaths.normalize(base)
                    ?: throw WorkspaceException("잘못된 경로입니다: $base")
                val prefix = if (basePath.isEmpty()) "" else "$basePath/"
                val results = ArrayList<String>()
                var scanned = 0L
                for (node in index.values) {
                    if (node.isDir) continue
                    if (basePath.isNotEmpty() &&
                        node.path != basePath &&
                        !node.path.startsWith(prefix)
                    ) {
                        continue
                    }
                    if (!AgentPaths.isTextFile(node.path)) continue
                    if (node.size > MAX_READ_BYTES) continue
                    if (scanned >= MAX_SEARCH_BYTES) break
                    val text = runCatching { readNodeText(node) }.getOrNull() ?: continue
                    scanned += text.length
                    var hits = 0
                    text.lineSequence().forEachIndexed { lineIndex, line ->
                        if (hits < 3 && line.contains(needle, ignoreCase = true)) {
                            results.add("${node.path}:${lineIndex + 1}: ${line.trim().take(160)}")
                            hits++
                        }
                    }
                    if (results.size >= max) break
                }
                results
            }
        }

    private fun ensureIndex(): Map<String, Node> {
        if (loaded) return nodes
        nodes.clear()
        nodes[""] = Node("", rootDocId, true, 0)
        name = displayName(rootDocId) ?: "작업 폴더"
        var count = 0
        var wasTruncated = false
        val queue = ArrayDeque<String>()
        queue.add("")
        while (queue.isNotEmpty()) {
            val dirPath = queue.removeFirst()
            val dir = nodes[dirPath] ?: continue
            val depth = dirPath.split('/').count { it.isNotEmpty() }
            if (depth >= MAX_DEPTH) {
                wasTruncated = true
                continue
            }
            val rows = children(dir.docId)
            for (row in rows) {
                count++
                if (count > MAX_NODES) {
                    wasTruncated = true
                    break
                }
                val childPath = if (dirPath.isEmpty()) row.name else "$dirPath/${row.name}"
                nodes[childPath] = Node(childPath, row.docId, row.isDir, row.size)
                if (row.isDir && !AgentPaths.isBlocked(childPath)) {
                    queue.add(childPath)
                }
            }
            if (count > MAX_NODES) break
        }
        loaded = true
        truncated = wasTruncated
        fileCount = nodes.values.count { !it.isDir }
        return nodes
    }

    private data class Row(
        val docId: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
    )

    private fun children(parentDocId: String): List<Row> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = ArrayList<Row>()
        runCatching {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    out.add(
                        Row(
                            docId = docId,
                            name = displayName,
                            isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = size,
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun displayName(docId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return runCatching {
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun requireFile(rel: String): Node {
        val index = ensureIndex()
        val path = AgentPaths.normalize(rel)
            ?: throw WorkspaceException("잘못된 경로입니다: $rel")
        val node = index[path] ?: throw WorkspaceException("파일을 찾을 수 없습니다: $path")
        if (node.isDir) throw WorkspaceException("폴더입니다: $path")
        return node
    }

    private fun readNodeText(node: Node): String {
        val bytes = resolver.openInputStream(documentUri(node.docId))?.use { it.readBytes() }
            ?: throw WorkspaceException("파일을 열 수 없습니다: ${node.path}")
        if (bytes.size > MAX_READ_BYTES) {
            throw WorkspaceException("파일이 너무 큽니다(${bytes.size}바이트). 200KB 이하만 읽습니다.")
        }
        if (looksBinary(bytes)) throw WorkspaceException("바이너리 파일입니다: ${node.path}")
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeBytes(docId: String, bytes: ByteArray): Boolean {
        val uri = documentUri(docId)
        for (mode in listOf("wt", "w")) {
            val ok = runCatching {
                resolver.openOutputStream(uri, mode)?.use { it.write(bytes) } != null
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun createFile(parent: Node, name: String, path: String): String {
        val uri = DocumentsContract.createDocument(
            resolver,
            documentUri(parent.docId),
            mimeFor(path),
            name,
        ) ?: throw WorkspaceException("파일을 만들 수 없습니다: $path")
        return DocumentsContract.getDocumentId(uri)
    }

    private fun ensureDir(path: String): Node {
        if (path.isEmpty()) return nodes[""] ?: Node("", rootDocId, true, 0)
        nodes[path]?.let { node ->
            if (!node.isDir) throw WorkspaceException("폴더가 아닙니다: $path")
            return node
        }
        if (AgentPaths.isBlocked(path)) throw WorkspaceException("보호된 경로입니다: $path")
        val parent = ensureDir(path.substringBeforeLast('/', ""))
        val uri = DocumentsContract.createDocument(
            resolver,
            documentUri(parent.docId),
            DocumentsContract.Document.MIME_TYPE_DIR,
            path.substringAfterLast('/'),
        ) ?: throw WorkspaceException("폴더를 만들 수 없습니다: $path")
        val node = Node(path, DocumentsContract.getDocumentId(uri), true, 0)
        nodes[path] = node
        return node
    }

    private fun documentUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "md" -> "text/markdown"
        "xml" -> "application/xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "text/plain"
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 1024)
        for (i in 0 until limit) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }
}
