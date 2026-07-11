package com.fileweft.dev.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.atomic.AtomicBoolean

class DevPlatformSecurityTest {
    private val secret = "platform-security-test-secret-0123456789"
    private val filter = DevPlatformAuthenticationFilter(DevPlatformAuthenticator(secret))

    @Test
    fun `allows the unauthenticated health probe only`() {
        val invoked = AtomicBoolean(false)
        val response = invoke("/platform/v1/health", null, invoked)

        assertEquals(200, response.status)
        assertTrue(invoked.get())
    }

    @Test
    fun `rejects every protected platform request without a valid shared secret`() {
        val missingSecret = AtomicBoolean(false)
        val missingResponse = invoke("/platform/v1/documents", null, missingSecret)
        assertEquals(401, missingResponse.status)
        assertFalse(missingSecret.get())

        val wrongSecret = AtomicBoolean(false)
        val wrongResponse = invoke("/platform/v1/admin/fault-mode", "incorrect-platform-secret", wrongSecret)
        assertEquals(401, wrongResponse.status)
        assertFalse(wrongSecret.get())
    }

    @Test
    fun `forwards a validly authenticated platform request`() {
        val invoked = AtomicBoolean(false)
        val response = invoke("/platform/v1/documents/alpha/document-1", secret, invoked)

        assertEquals(200, response.status)
        assertTrue(invoked.get())
    }

    private fun invoke(path: String, key: String?, invoked: AtomicBoolean): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", path).apply {
            requestURI = path
            key?.let { addHeader(DevPlatformAuthenticator.HEADER_NAME, it) }
        }
        return MockHttpServletResponse().also { response ->
            filter.doFilter(request, response) { _, _ -> invoked.set(true) }
        }
    }
}
