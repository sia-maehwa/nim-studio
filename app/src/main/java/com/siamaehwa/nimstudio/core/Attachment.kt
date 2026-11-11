package com.siamaehwa.nimstudio.core

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

sealed interface AttachmentContent {
    val name: String

    data class Image(
        override val name: String,
        val mime: String,
        val base64: String,
    ) : AttachmentContent

    data class Text(
        override val name: String,
        val text: String,
    ) : AttachmentContent
}

object Attachments {
    const val MAX_FILES = 4
    private const val MAX_SOURCE_BYTES = 12L * 1024 * 1024
    private const val MAX_TEXT_CHARS = 30_000
    private const val MAX_IMAGE_DIMENSION = 1568

    private val textExtensions = setOf(
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "md", "txt", "json",
        "xml", "yaml", "yml", "toml", "gradle", "sh", "bash", "c", "h", "cpp",
        "hpp", "cs", "go", "rs", "rb", "php", "html", "css", "scss", "sql",
        "properties", "ini", "cfg", "log", "csv", "env",
    )

    private val textMimes = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-yaml",
        "application/yaml",
        "application/toml",
    )

    suspend fun load(context: Context, uri: Uri): Result<AttachmentContent> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val name = displayName(resolver, uri) ?: "첨부"
                val declaredSize = sizeOf(resolver, uri)
                if (declaredSize != null && declaredSize > MAX_SOURCE_BYTES) {
                    throw IOException("파일이 너무 큽니다: $name (최대 12MB)")
                }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("파일을 열 수 없습니다: $name")
                if (bytes.size > MAX_SOURCE_BYTES) {
                    throw IOException("파일이 너무 큽니다: $name (최대 12MB)")
                }
                val mime = resolver.getType(uri).orEmpty()
                when {
                    isImage(mime, name) -> image(name, bytes)
                    isText(mime, name, bytes) -> AttachmentContent.Text(
                        name = name,
                        text = bytes.toString(Charsets.UTF_8).take(MAX_TEXT_CHARS),
                    )
                    else -> throw IOException(
                        "지원하지 않는 형식입니다: $name (이미지 또는 텍스트 파일)",
                    )
                }
            }
        }

    fun userContent(text: String, attachments: List<AttachmentContent>): JsonElement {
        if (attachments.isEmpty()) return JsonPrimitive(text)
        return buildJsonArray {
            if (text.isNotBlank()) {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            }
            attachments.forEach { attachment ->
                when (attachment) {
                    is AttachmentContent.Image -> add(
                        buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put(
                                    "url",
                                    "data:${attachment.mime};base64,${attachment.base64}",
                                )
                            }
                        },
                    )
                    is AttachmentContent.Text -> add(
                        buildJsonObject {
                            put("type", "text")
                            put(
                                "text",
                                "파일 ${attachment.name}:\n```\n${attachment.text}\n```",
                            )
                        },
                    )
                }
            }
        }
    }

    private fun image(name: String, bytes: ByteArray): AttachmentContent.Image {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("이미지를 읽을 수 없습니다: $name")
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_IMAGE_DIMENSION ||
            bounds.outHeight / (sample * 2) >= MAX_IMAGE_DIMENSION
        ) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IOException("이미지를 읽을 수 없습니다: $name")
        val scaled = scale(decoded, MAX_IMAGE_DIMENSION)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return AttachmentContent.Image(
            name = name,
            mime = "image/jpeg",
            base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
        )
    }

    private fun scale(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun isImage(mime: String, name: String): Boolean {
        if (mime.startsWith("image/")) return true
        return name.substringAfterLast('.', "").lowercase() in
            setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
    }

    private fun isText(mime: String, name: String, bytes: ByteArray): Boolean {
        if (mime.startsWith("text/")) return true
        if (mime in textMimes) return true
        if (name.substringAfterLast('.', "").lowercase() in textExtensions) return true
        val probe = bytes.take(8000)
        return probe.isNotEmpty() && probe.none { it == 0.toByte() }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)
                    } else {
                        null
                    }
                }
        }.getOrNull()

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        cursor.getLong(0)
                    } else {
                        null
                    }
                }
        }.getOrNull()
}
