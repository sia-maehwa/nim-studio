package com.siamaehwa.nimstudio.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

object MediaExporter {

    fun saveImage(context: Context, bytes: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "NIMStudio_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NIM Studio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val wrote = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
        }.getOrDefault(false)
        if (!wrote) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
