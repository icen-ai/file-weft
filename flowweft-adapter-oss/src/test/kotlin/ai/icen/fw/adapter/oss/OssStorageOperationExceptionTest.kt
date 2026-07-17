package ai.icen.fw.adapter.oss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.io.StringWriter
import java.net.SocketTimeoutException

class OssStorageOperationExceptionTest {
    @Test
    fun `classifies stable provider codes without exposing provider details`() {
        val secret = "Authorization=secret-signed-header"
        val cases = mapOf(
            "InvalidAccessKeyId" to OssStorageFailureCategory.AUTHENTICATION,
            "AccessDenied" to OssStorageFailureCategory.AUTHORIZATION,
            "NoSuchKey" to OssStorageFailureCategory.NOT_FOUND,
            "PreconditionFailed" to OssStorageFailureCategory.CONFLICT,
            "SlowDown" to OssStorageFailureCategory.THROTTLED,
            "RequestTimeout" to OssStorageFailureCategory.TIMEOUT,
            "ServiceUnavailable" to OssStorageFailureCategory.UNAVAILABLE,
            "InvalidRegion" to OssStorageFailureCategory.CONFIGURATION,
            "InvalidPart" to OssStorageFailureCategory.INVALID_REQUEST,
            "InvalidResponse" to OssStorageFailureCategory.INTEGRITY,
        )

        cases.forEach { (code, expected) ->
            val translated = ossStorageFailure(
                OssStorageOperation.DOWNLOAD,
                serviceFailure(code, secret),
            )
            assertEquals(expected, translated.category, code)
            assertFalse(translated.message.orEmpty().contains(secret), code)
            assertFalse(translated.toString().contains(secret), code)
            assertFalse(translated.message.orEmpty().contains("request-id"), code)
        }
    }

    @Test
    fun `marks bounded transport failures retryable`() {
        val translated = ossStorageFailure(
            OssStorageOperation.DOWNLOAD,
            SocketTimeoutException("signed URL and credential must remain private"),
        )

        assertEquals(OssStorageFailureCategory.TIMEOUT, translated.category)
        assertTrue(translated.retryable)
        assertFalse(translated.message.orEmpty().contains("credential"))
    }

    @Test
    fun `never retains provider details in a normally logged stack trace`() {
        val secret =
            "https://bucket.example/object?x-oss-signature=secret Authorization=Bearer-private request-id=private"

        val translated = ossStorageFailure(
            OssStorageOperation.PRESIGN_UPLOAD,
            SocketTimeoutException(secret),
        )
        val rendered = StringWriter().also { writer ->
            translated.printStackTrace(PrintWriter(writer))
        }.toString()

        assertEquals(null, translated.cause)
        assertFalse(rendered.contains(secret))
        assertFalse(rendered.contains("x-oss-signature"))
        assertFalse(rendered.contains("Bearer-private"))
        assertFalse(rendered.contains("request-id"))
    }
}
