package com.siamaehwa.nimstudio

import com.siamaehwa.nimstudio.core.KeyFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFormatTest {

    @Test
    fun sanitizeTrimsAndStripsBearerPrefix() {
        assertEquals("nvapi-abc12345", KeyFormat.sanitize("Bearer nvapi-abc12345 "))
        assertEquals("nvapi-abc", KeyFormat.sanitize("  nvapi-abc  "))
    }

    @Test
    fun looksLikeNimKeyChecksPrefixAndLength() {
        assertTrue(KeyFormat.looksLikeNimKey("nvapi-1234567890"))
        assertFalse(KeyFormat.looksLikeNimKey("sk-1234567890"))
        assertFalse(KeyFormat.looksLikeNimKey("nvapi-"))
    }
}
