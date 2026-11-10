package com.siamaehwa.nimstudio.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.siamaehwa.nimstudio.core.db.LibraryDao
import com.siamaehwa.nimstudio.core.db.LibraryItemEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class LibraryRepository(
    private val context: Context,
    private val dao: LibraryDao,
) {

    private val libraryDir: File
        get() = File(context.filesDir, "library").apply { mkdirs() }

    fun observe(kind: String, query: String): Flow<List<LibraryItemEntity>> =
        dao.observe(kind, query)

    suspend fun saveImage(bytes: ByteArray, prompt: String, source: String): Long =
        withContext(Dispatchers.IO) {
            val mime = detectImageMime(bytes)
            val ext = if (mime == "image/png") "png" else "jpg"
            val file = File(
                libraryDir,
                "img_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext",
            )
            file.writeBytes(bytes)
            dao.insert(
                LibraryItemEntity(
                    kind = "image",
                    title = prompt.ifBlank { "생성 이미지" }.take(80),
                    subtitle = "이미지 · ${formatSize(bytes.size.toLong())}",
                    content = file.absolutePath,
                    mime = mime,
                    source = source,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

    suspend fun saveText(
        kind: String,
        title: String,
        content: String,
        source: String,
    ): Long = withContext(Dispatchers.IO) {
        val label = if (kind == "code") "코드" else "텍스트"
        dao.insert(
            LibraryItemEntity(
                kind = kind,
                title = title.ifBlank { "저장된 텍스트" }.take(80),
                subtitle = "$label · ${content.length}자",
                content = content,
                mime = if (kind == "code") "text/plain" else "text/markdown",
                source = source,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(item: LibraryItemEntity) = withContext(Dispatchers.IO) {
        if (item.kind == "image") {
            runCatching { File(item.content).delete() }
        }
        dao.delete(item.id)
        Unit
    }

    suspend fun exportImageToGallery(item: LibraryItemEntity): Boolean =
        withContext(Dispatchers.IO) {
            if (item.kind != "image") return@withContext false
            val file = File(item.content)
            if (!file.exists()) return@withContext false
            MediaExporter.saveImage(context, file.readBytes()) != null
        }

    suspend fun exportBytesToGallery(bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            MediaExporter.saveImage(context, bytes) != null
        }

    fun buildShareIntentForBytes(bytes: ByteArray, mime: String): Intent? {
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val ext = if (mime == "image/png") "png" else "jpg"
        val file = File(shareDir, "nim_${System.currentTimeMillis()}.$ext")
        return runCatching {
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, authority(), file)
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrNull()
    }

    fun buildShareIntent(item: LibraryItemEntity): Intent? {
        val uri = when (item.kind) {
            "image" -> {
                val file = File(item.content)
                if (!file.exists()) return null
                FileProvider.getUriForFile(context, authority(), file)
            }

            else -> {
                val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
                val ext = if (item.kind == "code") "txt" else "md"
                val file = File(shareDir, "${sanitize(item.title)}.$ext")
                file.writeText(item.content)
                FileProvider.getUriForFile(context, authority(), file)
            }
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = item.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun authority(): String = "${context.packageName}.fileprovider"

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim()
        return cleaned.ifBlank { "nim-studio" }.take(40)
    }

    private fun detectImageMime(bytes: ByteArray): String {
        val isPng = bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        return if (isPng) "image/png" else "image/jpeg"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%dKB".format(bytes / 1024)
        else -> "${bytes}B"
    }
}
