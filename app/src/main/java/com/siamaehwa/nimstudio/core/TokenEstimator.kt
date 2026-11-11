package com.siamaehwa.nimstudio.core

import kotlin.math.ceil

object TokenEstimator {

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var weight = 0.0
        for (ch in text) {
            weight += when {
                ch.isCjk() -> 1.0
                ch.isWhitespace() -> 0.1
                ch.isLetterOrDigit() -> 0.28
                else -> 0.5
            }
        }
        return ceil(weight).toInt().coerceAtLeast(1)
    }

    private fun Char.isCjk(): Boolean {
        val code = code
        return code in 0xAC00..0xD7A3 ||
            code in 0x1100..0x11FF ||
            code in 0x3130..0x318F ||
            code in 0x4E00..0x9FFF ||
            code in 0x3040..0x30FF
    }
}
