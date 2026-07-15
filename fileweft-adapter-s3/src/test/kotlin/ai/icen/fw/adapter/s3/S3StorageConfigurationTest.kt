package ai.icen.fw.adapter.s3

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.ApiCallTimeoutException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.net.UnknownHostException
import java.io.PrintWriter
import java.io.StringWriter
import java.io.ByteArrayInputStream
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
    fun `rejects endpoint authority credentials query fragment and missing host`() {
        listOf(
            "http://user:password@storage.example.test",
            "https://storage.example.test?secret=value",
            "https://storage.example.test#fragment",
            "http:/missing-host",
        ).forEach { endpoint ->
            assertThrows(
                IllegalArgumentException::class.java,
                { configuration(endpoint) },
                endpoint,
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
    fun `classifies only definitive S3 multipart completion rejections as safe to reopen`() {
        listOf("EntityTooSmall", "InvalidPart", "InvalidPartOrder").forEach { code ->
            assertTrue(isDefinitiveMultipartCompletionRejection(code), code)
        }
        assertFalse(isDefinitiveMultipartCompletionRejection("NoSuchUpload"))
        assertFalse(isDefinitiveMultipartCompletionRejection("InternalError"))
        assertFalse(isDefinitiveMultipartCompletionRejection(null))
    }

    @Test
    fun `preserves released nonblank region and storage type acceptance domain`() {
        assertDoesNotThrow {
            configuration().copy(
                region = "Private_Region.V2" + "R".repeat(512),
                storageType = "Vendor Storage/V2" + "S".repeat(512),
            )
        }
    }

    @Test
    fun `rejects control characters in signing and diagnostic fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            configuration(accessKey = "access\r\nAuthorization: injected")
        }
        assertThrows(IllegalArgumentException::class.java) {
            configuration(secretKey = "secret\r\nx-amz-security-token: injected")
        }
        assertThrows(IllegalArgumentException::class.java) {
            configuration().copy(region = "us-east-1\r\ninjected")
        }
        assertThrows(IllegalArgumentException::class.java) {
            configuration().copy(storageType = "s3\r\ninjected")
        }
    }

    @Test
    fun `configuration rendering redacts credentials bucket and endpoint authority`() {
        val configuration = configuration(
            endpoint = "https://private-storage.example.test/tenant-capability",
            accessKey = "private-access-key",
            secretKey = "private-secret-and-session-token",
            bucket = "private-bucket",
        )

        val rendered = configuration.toString()

        listOf(
            "private-storage.example.test",
            "tenant-capability",
            "private-access-key",
            "private-secret",
            "session-token",
            "private-bucket",
        ).forEach { sensitive -> assertFalse(rendered.contains(sensitive), sensitive) }
        assertTrue(rendered.contains("credentials=<redacted>"))
        assertTrue(rendered.contains("bucket=<redacted>"))
    }

    @Test
    fun `enforces bounded client timeout and retry policy`() {
        assertDoesNotThrow { S3StorageClientPolicy() }
        assertThrows(IllegalArgumentException::class.java) {
            S3StorageClientPolicy(connectionTimeout = Duration.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S3StorageClientPolicy(
                apiCallAttemptTimeout = Duration.ofSeconds(2),
                apiCallTimeout = Duration.ofSeconds(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S3StorageClientPolicy(maxAttempts = 11)
        }
    }

    @Test
    fun `uses retries only for side effect free reads and probes`() {
        val retrying = setOf(
            S3StorageOperation.DOWNLOAD,
            S3StorageOperation.DOWNLOAD_RANGE,
            S3StorageOperation.READ_METADATA,
            S3StorageOperation.EXISTS,
            S3StorageOperation.LIST_MULTIPART_PARTS,
            S3StorageOperation.VERIFY_COMPLETED_OBJECT,
            S3StorageOperation.CHECKSUM_COMPLETED_OBJECT,
            S3StorageOperation.CHECK_BUCKET,
        )
        retrying.forEach { operation ->
            assertEquals(S3StorageClientMode.RETRYING, s3ClientMode(operation), operation.name)
        }
        val singleAttempt = setOf(
            S3StorageOperation.UPLOAD,
            S3StorageOperation.DELETE,
            S3StorageOperation.BEGIN_MULTIPART_UPLOAD,
            S3StorageOperation.UPLOAD_PART,
            S3StorageOperation.COMPLETE_MULTIPART_UPLOAD,
            S3StorageOperation.ABORT_MULTIPART_UPLOAD,
            S3StorageOperation.CREATE_BUCKET,
        )
        singleAttempt.forEach { operation ->
            assertEquals(S3StorageClientMode.SINGLE_ATTEMPT, s3ClientMode(operation), operation.name)
        }
        val noClient = setOf(S3StorageOperation.PRESIGN_DOWNLOAD, S3StorageOperation.CLOSE)
        noClient.forEach { operation ->
            assertEquals(S3StorageClientMode.NONE, s3ClientMode(operation), operation.name)
        }
        assertEquals(S3StorageOperation.values().toSet(), retrying + singleAttempt + noClient)
    }

    @Test
    fun `distinguishes object bucket upload and ambiguous 404 failures`() {
        val missingKey = s3Failure("NoSuchKey", 404)
        val missingBucket = s3Failure("NoSuchBucket", 404)
        val missingUpload = s3Failure("NoSuchUpload", 404)
        val genericNotFound = s3Failure("NotFound", 404)
        val bareNotFound = S3Exception.builder().statusCode(404).build() as S3Exception

        assertEquals(S3StorageMissingResource.OBJECT, classifyS3MissingResource(404, "NoSuchKey"))
        assertEquals(S3StorageMissingResource.BUCKET, classifyS3MissingResource(404, "NoSuchBucket"))
        assertEquals(S3StorageMissingResource.MULTIPART_UPLOAD, classifyS3MissingResource(404, "NoSuchUpload"))
        assertEquals(S3StorageMissingResource.AMBIGUOUS, classifyS3MissingResource(404, "NotFound"))
        assertEquals(S3StorageMissingResource.AMBIGUOUS, classifyS3MissingResource(404, null))
        assertEquals(S3StorageMissingResource.OBJECT, s3StorageFailure(S3StorageOperation.EXISTS, missingKey).missingResource)
        assertEquals(S3StorageMissingResource.BUCKET, s3StorageFailure(S3StorageOperation.EXISTS, missingBucket).missingResource)
        assertEquals(
            S3StorageMissingResource.MULTIPART_UPLOAD,
            s3StorageFailure(S3StorageOperation.LIST_MULTIPART_PARTS, missingUpload).missingResource,
        )
        assertEquals(
            S3StorageMissingResource.AMBIGUOUS,
            s3StorageFailure(S3StorageOperation.EXISTS, genericNotFound).missingResource,
        )
        assertEquals(
            S3StorageMissingResource.AMBIGUOUS,
            s3StorageFailure(S3StorageOperation.EXISTS, bareNotFound).missingResource,
        )
    }

    @Test
    fun `classifies provider and network failures without copying sensitive messages`() {
        assertEquals(
            S3StorageFailureCategory.AUTHENTICATION,
            classifyS3StorageFailure(s3Failure("SignatureDoesNotMatch", 403)),
        )
        assertEquals(
            S3StorageFailureCategory.THROTTLED,
            classifyS3StorageFailure(s3Failure("SlowDown", 503)),
        )
        assertEquals(
            S3StorageFailureCategory.TIMEOUT,
            classifyS3StorageFailure(ApiCallTimeoutException.create(100)),
        )
        assertEquals(
            S3StorageFailureCategory.UNAVAILABLE,
            classifyS3StorageFailure(SdkClientException.create("internal endpoint", UnknownHostException("secret-host"))),
        )

        val sensitiveValues = listOf(
            "private-access-key",
            "private-secret-key",
            "private-session-token",
            "private.example.test",
            "signed-query-value",
            "Authorization: Bearer",
        )
        val raw = S3Exception.builder()
            .statusCode(403)
            .message(
                "private-access-key private-secret-key private-session-token " +
                    "https://private.example.test/signed?token=signed-query-value " +
                    "Authorization: Bearer private-session-token",
            )
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
            .build() as S3Exception
        val translated = s3StorageFailure(S3StorageOperation.DOWNLOAD, raw)

        assertEquals(S3StorageFailureCategory.AUTHORIZATION, translated.category)
        assertFalse(translated.retryable)
        assertNull(translated.cause)
        val stackTrace = StringWriter().also { writer ->
            translated.printStackTrace(PrintWriter(writer))
        }.toString()
        sensitiveValues.forEach { sensitive ->
            assertFalse(translated.toString().contains(sensitive), sensitive)
            assertFalse(stackTrace.contains(sensitive), sensitive)
        }
    }

    @Test
    fun `validates range and presign bounds before network access`() {
        S3StorageAdapter(configuration()).use { adapter ->
            val location = StorageObjectLocation(
                S3StorageAdapter.STORAGE_TYPE,
                "objects/${"a".repeat(64)}/${"b".repeat(32)}",
            )
            assertThrows(IllegalArgumentException::class.java) { adapter.downloadRange(location, -1, 1) }
            assertThrows(IllegalArgumentException::class.java) { adapter.downloadRange(location, 0, 0) }
            assertThrows(ArithmeticException::class.java) { adapter.downloadRange(location, Long.MAX_VALUE, 2) }
            assertThrows(IllegalArgumentException::class.java) { adapter.accessUrl(location, Duration.ZERO) }
            assertThrows(IllegalArgumentException::class.java) { adapter.accessUrl(location, Duration.ofDays(8)) }
            assertThrows(IllegalArgumentException::class.java) {
                adapter.upload(
                    StorageUploadRequest(
                        tenantId = Identifier("tenant"),
                        objectName = "unsafe-content-type",
                        contentLength = 0,
                        contentType = "text/plain\r\nAuthorization: injected",
                    ),
                    ByteArrayInputStream(ByteArray(0)),
                )
            }
        }
    }

    @Test
    fun `doctor evidence is bounded and excludes endpoint bucket and credentials`() {
        val configuration = configuration(
            endpoint = "https://private-storage.example.test",
            accessKey = "private-access-key",
            secretKey = "private-secret-key",
            bucket = "private-bucket",
        )
        val adapter = S3StorageAdapter(configuration)
        adapter.close()

        val result = S3StorageDoctorChecker(adapter).check(DoctorCheckContext(Identifier("tenant-doctor")))

        assertEquals(DoctorStatus.ERROR, result.status)
        assertTrue(
            result.evidence["failureCategory"] in setOf(
                S3StorageFailureCategory.UNAVAILABLE.name,
                S3StorageFailureCategory.UNKNOWN.name,
            ),
        )
        val rendered = (result.reason + result.repairSuggestion + result.evidence).lowercase()
        assertFalse(rendered.contains("private-storage"))
        assertFalse(rendered.contains("private-bucket"))
        assertFalse(rendered.contains("private-access-key"))
        assertFalse(rendered.contains("private-secret-key"))
        assertFalse(result.evidence.containsKey("bucketFingerprint"))
    }

    private fun configuration(
        endpoint: String = "http://127.0.0.1:9000",
        accessKey: String = "access",
        secretKey: String = "secret",
        bucket: String = "fileweft-integration",
    ): S3StorageConfiguration = S3StorageConfiguration(
        endpoint = URI(endpoint),
        region = "us-east-1",
        accessKey = accessKey,
        secretKey = secretKey,
        bucket = bucket,
    )

    private fun s3Failure(code: String, statusCode: Int = 400): S3Exception = S3Exception.builder()
        .statusCode(statusCode)
        .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
        .build() as S3Exception
}
