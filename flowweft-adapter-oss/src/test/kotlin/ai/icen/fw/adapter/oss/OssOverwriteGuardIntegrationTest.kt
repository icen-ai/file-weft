package ai.icen.fw.adapter.oss

import com.aliyun.oss.OSSException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Opt-in provider-semantic proof for x-oss-forbid-overwrite.
 *
 * This lane intentionally uses a second private bucket with versioning
 * disabled. OSS documents that overwrite guards are ignored when versioning
 * is enabled or suspended, while the primary real integration lane requires
 * versioning to verify exact-version deletion. Never point this test at a
 * bucket containing non-test data.
 */
@EnabledIfEnvironmentVariable(named = "FLOWWEFT_RUN_OSS_OVERWRITE_TESTS", matches = "true")
class OssOverwriteGuardIntegrationTest {
    @Test
    fun `multipart completion cannot replace an object created after initiation`() {
        val client = AlibabaOssV1Client(configuration(), OssStorageClientPolicy())
        val key = "objects/${"a".repeat(64)}/${UUID.randomUUID().toString().replace("-", "")}"
        val multipartBytes = "multipart-candidate".toByteArray()
        val existingBytes = "existing-authority".toByteArray()
        var uploadId: String? = null
        try {
            val activeUploadId = client.initiateMultipartUpload(key, "text/plain", emptyMap())
            uploadId = activeUploadId
            val partETag = client.uploadPart(
                key,
                activeUploadId,
                1,
                multipartBytes.size.toLong(),
                ByteArrayInputStream(multipartBytes),
            )
            client.putObject(
                key,
                existingBytes.size.toLong(),
                "text/plain",
                emptyMap(),
                ByteArrayInputStream(existingBytes),
            )

            val failure = assertThrows(OSSException::class.java) {
                client.completeMultipartUpload(key, activeUploadId, listOf(OssCompletedPart(1, partETag)))
            }

            assertEquals("FileAlreadyExists", failure.errorCode)
            val response = client.getObject(key)
            try {
                assertArrayEquals(existingBytes, response.body.readBytes())
            } finally {
                response.close()
            }
        } finally {
            uploadId?.let { id -> runCatching { client.abortMultipartUpload(key, id) } }
            cleanupCurrentTestVersions(client, key)
            client.close()
        }
    }

    private fun cleanupCurrentTestVersions(client: AlibabaOssV1Client, key: String) {
        // One version is expected. The extra bounded attempts also clean both
        // test-only versions if an accidentally versioned bucket allowed the
        // completion that this lane is designed to reject.
        var continueCleanup = true
        repeat(3) {
            if (continueCleanup) {
                try {
                    val versionId = client.headObject(key).versionId
                    client.deleteObject(key, versionId)
                } catch (_: Exception) {
                    continueCleanup = false
                }
            }
        }
    }

    private fun configuration(): OssStorageConfiguration = OssStorageConfiguration(
        endpoint = URI.create(requiredEnvironment("FLOWWEFT_OSS_ENDPOINT")),
        region = requiredEnvironment("FLOWWEFT_OSS_REGION"),
        bucket = requiredEnvironment("FLOWWEFT_OSS_OVERWRITE_BUCKET"),
        credentialsProvider = StaticOssCredentialsProvider(
            requiredEnvironment("FLOWWEFT_OSS_ACCESS_KEY_ID"),
            requiredEnvironment("FLOWWEFT_OSS_ACCESS_KEY_SECRET"),
            requiredEnvironment("FLOWWEFT_OSS_SECURITY_TOKEN"),
            requiredCredentialExpiration(),
        ),
    )

    private fun requiredEnvironment(name: String): String =
        requireNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) {
            "$name is required for the opt-in OSS overwrite-guard lane."
        }

    private fun requiredCredentialExpiration(): Long {
        val raw = requiredEnvironment("FLOWWEFT_OSS_CREDENTIAL_EXPIRES_AT")
        val expiresAt = try {
            Instant.parse(raw).toEpochMilli()
        } catch (failure: RuntimeException) {
            throw IllegalArgumentException(
                "FLOWWEFT_OSS_CREDENTIAL_EXPIRES_AT must be an ISO-8601 instant.",
                failure,
            )
        }
        require(expiresAt > System.currentTimeMillis()) {
            "FLOWWEFT_OSS_CREDENTIAL_EXPIRES_AT must be in the future."
        }
        return expiresAt
    }
}
