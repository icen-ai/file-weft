package ai.icen.fw.adapter.oss

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.storage.PresignedUploadFinalizeRequest
import ai.icen.fw.spi.storage.PresignedUploadGrantRequest
import ai.icen.fw.spi.storage.StorageContentChecksum
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.testkit.storage.ConditionalRangedStorageAdapterContractTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Opt-in real Alibaba Cloud OSS lane.
 *
 * It is disabled in ordinary local and matrix tests. Use only a dedicated
 * private versioning-enabled bucket and short-lived RAM Role/STS credentials.
 * The inherited range contract's clipped-end case is also provider-semantic
 * evidence that x-oss-range-behavior=standard reached OSS: without that
 * header OSS returns the whole object with HTTP 200 and the contract fails.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "FLOWWEFT_RUN_OSS_TESTS", matches = "true")
class OssStorageAdapterIntegrationTest : ConditionalRangedStorageAdapterContractTest() {
    override val storageAdapter: OssStorageAdapter by lazy {
        OssStorageAdapter(
            OssStorageConfiguration(
                endpoint = URI.create(requiredEnvironment("FLOWWEFT_OSS_ENDPOINT")),
                region = requiredEnvironment("FLOWWEFT_OSS_REGION"),
                bucket = requiredEnvironment("FLOWWEFT_OSS_BUCKET"),
                credentialsProvider = StaticOssCredentialsProvider(
                    requiredEnvironment("FLOWWEFT_OSS_ACCESS_KEY_ID"),
                    requiredEnvironment("FLOWWEFT_OSS_ACCESS_KEY_SECRET"),
                    requiredEnvironment("FLOWWEFT_OSS_SECURITY_TOKEN"),
                    requiredCredentialExpiration(),
                ),
            ),
        )
    }

    override fun uploadRequest(): StorageUploadRequest {
        val bytes = content()
        return StorageUploadRequest(
            tenantId = Identifier("oss-contract-${System.nanoTime()}"),
            objectName = "oss-contract.txt",
            contentLength = bytes.size.toLong(),
            contentType = "text/plain",
            metadata = mapOf("contract-suite" to "flowweft"),
        )
    }

    @Test
    fun `recovers server authoritative parts across adapter calls`() {
        val bytes = content()
        val upload = storageAdapter.beginMultipartUpload(
            uploadRequest().copy(contentLength = bytes.size.toLong()),
        )
        try {
            val acknowledgement = storageAdapter.uploadPart(
                upload,
                1,
                ByteArrayInputStream(bytes),
                bytes.size.toLong(),
            )

            assertEquals(listOf(acknowledgement), storageAdapter.listUploadedParts(upload))
        } finally {
            storageAdapter.abortMultipartUpload(upload)
            storageAdapter.delete(upload.location)
        }
    }

    @Test
    fun `uploads through constrained presigned PUT and finalizes exact provider evidence`() {
        val bytes = "flowweft-real-oss-presigned-${UUID.randomUUID()}".toByteArray()
        val tenantId = Identifier("oss-direct-${UUID.randomUUID()}")
        val bindingId = Identifier("binding-${UUID.randomUUID()}")
        val contentHash = "sha256:${sha256(bytes)}"
        val checksum = StorageContentChecksum("md5", md5(bytes))
        val request = PresignedUploadGrantRequest(
            bindingId = bindingId,
            tenantId = tenantId,
            objectName = "direct-contract.txt",
            contentLength = bytes.size.toLong(),
            contentType = "text/plain",
            contentHash = contentHash,
            checksum = checksum,
            metadata = mapOf("contract-suite" to "flowweft-direct"),
            expiresIn = Duration.ofMinutes(5),
        )
        val grant = storageAdapter.createUploadGrant(request)
        var boundLocation: StorageObjectLocation? = null
        try {
            putThroughGrant(grant, bytes)

            val finalization = storageAdapter.finalizeUpload(
                PresignedUploadFinalizeRequest(
                    bindingId,
                    tenantId,
                    grant.location,
                    request.contentLength,
                    request.contentType,
                    request.contentHash,
                    request.checksum,
                    request.metadata,
                ),
            )
            boundLocation = finalization.storedObject.location
            assertEquals(contentHash, finalization.storedObject.contentHash)
            assertEquals(checksum, finalization.checksum)
            assertEquals(request.metadata, finalization.metadata)
            assertEquals(tenantId, finalization.tenantId)
            assertEquals(bindingId, finalization.bindingId)
            assertEquals(grant.location, finalization.sourceLocation)
            assertNotEquals(grant.location, finalization.storedObject.location)
            assertTrue(
                finalization.revision.startsWith("oss-v1-version:"),
                "The dedicated OSS integration bucket must have versioning enabled.",
            )

            // OSS ignores forbid-overwrite in a versioned bucket. Reusing the
            // still-valid URL must create a newer version without moving the
            // already completed location away from its attested version.
            putThroughGrant(grant, bytes)
            val boundMetadata = storageAdapter.metadata(finalization.storedObject.location)
            val latestMetadata = storageAdapter.metadata(grant.location)
            assertEquals(finalization.revision, boundMetadata.revision)
            assertNotEquals(finalization.revision, latestMetadata.revision)
            storageAdapter.download(finalization.storedObject.location).content.use {
                assertArrayEquals(bytes, it.readBytes())
            }
            storageAdapter.downloadRange(finalization.storedObject.location, 0, 4).content.use {
                assertArrayEquals(bytes.copyOfRange(0, 4), it.readBytes())
            }
            val accessUrl = storageAdapter.accessUrl(finalization.storedObject.location, Duration.ofMinutes(1))
            assertTrue(
                accessUrl.rawQuery.split('&').any { parameter ->
                    parameter.substringBefore('=').equals("versionId", ignoreCase = true)
                },
                "A completed OSS access URL must bind the exact version id.",
            )

            storageAdapter.delete(finalization.storedObject.location)
            storageAdapter.delete(finalization.storedObject.location)
            assertEquals(false, storageAdapter.exists(finalization.storedObject.location))
            assertTrue(storageAdapter.exists(grant.location))
        } finally {
            boundLocation?.let { location -> runCatching { storageAdapter.delete(location) } }
            repeat(3) {
                runCatching {
                    if (storageAdapter.exists(grant.location)) storageAdapter.delete(grant.location)
                }
            }
        }
    }

    @Test
    fun `reports real bucket Doctor evidence as healthy`() {
        val result = OssStorageDoctorChecker(storageAdapter).check(
            DoctorCheckContext(Identifier("oss-doctor-${UUID.randomUUID()}")),
        )

        assertEquals(DoctorStatus.HEALTHY, result.status)
        assertEquals("oss-storage", result.checkerName)
        assertEquals("v4", result.evidence["signatureVersion"])
    }

    @AfterAll
    fun closeAdapter() {
        storageAdapter.close()
    }

    private fun requiredEnvironment(name: String): String =
        requireNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) {
            "$name is required for the opt-in OSS integration lane."
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

    private fun putThroughGrant(
        grant: ai.icen.fw.spi.storage.PresignedUploadGrant,
        bytes: ByteArray,
    ) {
        val connection = grant.uploadUri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = grant.httpMethod
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setFixedLengthStreamingMode(bytes.size)
            grant.requiredHeaders.forEach(connection::setRequestProperty)
            val responseCode = try {
                connection.outputStream.use { it.write(bytes) }
                connection.responseCode
            } catch (_: IOException) {
                // HttpURLConnection exception messages can contain the
                // complete presigned URL. Never let that capability reach a
                // CI log or test report.
                throw AssertionError("Presigned OSS PUT failed before a sanitized HTTP status was available.")
            }
            assertEquals(200, responseCode)
        } finally {
            connection.disconnect()
        }
    }
}
