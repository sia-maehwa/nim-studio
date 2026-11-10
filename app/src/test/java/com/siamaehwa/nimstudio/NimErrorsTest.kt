package com.siamaehwa.nimstudio

import com.siamaehwa.nimstudio.core.NimApiException
import com.siamaehwa.nimstudio.core.NimErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NimErrorsTest {

    @Test
    fun unauthorizedJwtDetailMapsToFormatHint() {
        val message = NimErrors.friendlyFor(
            401,
            "Unauthorized",
            "Invalid JWT serialization: Missing dot delimiter(s)",
        )
        assertTrue(message.contains("nvapi-"))
    }

    @Test
    fun forbiddenMapsToPermissionHint() {
        val message = NimErrors.friendlyFor(403, "Forbidden", "Authorization failed")
        assertTrue(message.contains("403"))
    }

    @Test
    fun rateLimitMentionsRetry() {
        val message = NimErrors.friendlyFor(429, null, null)
        assertTrue(message.contains("429"))
    }

    @Test
    fun apiExceptionUsesFriendlyField() {
        val exception = NimApiException(403, "Forbidden", "Authorization failed", "friendly-message")
        assertEquals("friendly-message", NimErrors.friendlyFor(exception))
    }

    @Test
    fun unknownHostMapsToNetworkHint() {
        val message = NimErrors.friendlyFor(java.net.UnknownHostException("api"))
        assertTrue(message.contains("네트워크"))
    }
}
