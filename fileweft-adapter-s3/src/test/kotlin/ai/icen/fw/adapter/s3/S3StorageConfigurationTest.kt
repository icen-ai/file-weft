package ai.icen.fw.adapter.s3

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.time.Duration

class S3StorageConfigurationTest {
    @Test
    fun `accepts a valid path style compatible endpoint`() {
        assertDoesNotThrow {
            S3StorageConfiguration(
                endpoint = URI("http://127.0.0.1:9000"),
                region = "us-east-1",
                accessKey = "rustfsadmin",
                secretKey = "ChangeMe123!",
                bucket = "fileweft-integration",
            )
        }
    }

    @Test
    fun `rejects a non HTTP endpoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            S3StorageConfiguration(
                endpoint = URI("file:///tmp/storage"),
                region = "us-east-1",
                accessKey = "access",
                secretKey = "secret",
                bucket = "fileweft-integration",
            )
        }
    }

    @Test
    fun `rejects an invalid bucket name`() {
        assertThrows(IllegalArgumentException::class.java) {
            S3StorageConfiguration(
                endpoint = URI("https://storage.example.test"),
                region = "us-east-1",
                accessKey = "access",
                secretKey = "secret",
                bucket = "Invalid_Bucket",
            )
        }
    }

    @Test
    fun `applies the documented default API call budgets`() {
        val configuration = S3StorageConfiguration(
            endpoint = URI("http://127.0.0.1:9000"),
            region = "us-east-1",
            accessKey = "access",
            secretKey = "secret",
            bucket = "fileweft-integration",
        )

        assertEquals(S3StorageConfiguration.DEFAULT_API_CALL_TIMEOUT, configuration.apiCallTimeout)
        assertEquals(S3StorageConfiguration.DEFAULT_API_CALL_ATTEMPT_TIMEOUT, configuration.apiCallAttemptTimeout)
        assertEquals(Duration.ofMinutes(2), configuration.apiCallTimeout)
        assertEquals(Duration.ofSeconds(30), configuration.apiCallAttemptTimeout)
    }

    @Test
    fun `rejects a non positive API call budget`() {
        assertThrows(IllegalArgumentException::class.java) {
            S3StorageConfiguration(
                endpoint = URI("http://127.0.0.1:9000"),
                region = "us-east-1",
                accessKey = "access",
                secretKey = "secret",
                bucket = "fileweft-integration",
                apiCallTimeout = Duration.ZERO,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S3StorageConfiguration(
                endpoint = URI("http://127.0.0.1:9000"),
                region = "us-east-1",
                accessKey = "access",
                secretKey = "secret",
                bucket = "fileweft-integration",
                apiCallAttemptTimeout = Duration.ofMillis(-1),
            )
        }
    }

    @Test
    fun `rejects an attempt budget above the overall call budget`() {
        assertThrows(IllegalArgumentException::class.java) {
            S3StorageConfiguration(
                endpoint = URI("http://127.0.0.1:9000"),
                region = "us-east-1",
                accessKey = "access",
                secretKey = "secret",
                bucket = "fileweft-integration",
                apiCallTimeout = Duration.ofSeconds(10),
                apiCallAttemptTimeout = Duration.ofSeconds(11),
            )
        }
    }

    @Test
    fun `classifies only definitive S3 multipart completion rejections as safe to reopen`() {
        listOf("EntityTooSmall", "InvalidPart", "InvalidPartOrder").forEach { code ->
            assertTrue(isDefinitiveMultipartCompletionRejection(s3Failure(code)), code)
        }
        assertFalse(isDefinitiveMultipartCompletionRejection(s3Failure("NoSuchUpload")))
        assertFalse(isDefinitiveMultipartCompletionRejection(s3Failure("InternalError")))
    }

    private fun s3Failure(code: String): S3Exception = S3Exception.builder()
        .statusCode(400)
        .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
        .build() as S3Exception
}
