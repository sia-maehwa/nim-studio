package com.siamaehwa.nimstudio.core

object KeyFormat {
    const val PREFIX = "nvapi-"

    fun sanitize(raw: String): String {
        var key = raw.trim()
        if (key.startsWith("Bearer ", ignoreCase = true)) {
            key = key.substring(7).trim()
        }
        return key
    }

    fun looksLikeNimKey(raw: String): Boolean {
        val key = sanitize(raw)
        return key.startsWith(PREFIX) && key.length > PREFIX.length + 8
    }
}
