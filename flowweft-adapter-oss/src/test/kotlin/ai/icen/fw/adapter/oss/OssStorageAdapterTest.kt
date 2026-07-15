package ai.icen.fw.adapter.oss

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.MultipartCompletionRejectedException
import ai.icen.fw.spi.storage.PresignedUploadFinalizeRequest
import ai.icen.fw.spi.storage.PresignedUploadCleanupRequest
import ai.icen.fw.spi.storage.PresignedUploadGrantRequest
import ai.icen.fw.spi.storage.PresignedUploadReissueRequest
import ai.icen.fw.spi.storage.StorageContentChecksum
import ai.icen.fw.spi.storage.StorageRangeRequest
import ai.icen.fw.spi.storage.StorageUploadRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class OssStorageAdapterTest {
    @Test
    fun `signs exact direct PUT authority and finalizes only provider-attested bytes`() {
        val fixture = fixture()
        val content = "direct contract".toByteArray()
        val request = directRequest("tenant-a", "binding-a", content)

        val grant = fixture.adapter.createUploadGrant(request)

        assertEquals("PUT", grant.httpMethod)
        assertEquals("text/plain", grant.requiredHeaders["Content-Type"])
        assertEquals(md5(content), grant.requiredHeaders["Content-MD5"])
        assertEquals("true", grant.requiredHeaders["x-oss-forbid-overwrite"])
        assertEquals(
            "sha256:${sha256(content)}",
            grant.requiredHeaders["x-oss-meta-flowweft-content-sha256"],
        )
        assertFalse(grant.requiredHeaders.values.contains("tenant-a"))
        assertFalse(grant.requiredHeaders.values.contains("binding-a"))
        assertEquals(grant.location.path, fixture.client.lastPresignedPut?.key)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.adapter.finalizeUpload(
                PresignedUploadFinalizeRequest(
                    request.bindingId,
                    Identifier("tenant-b"),
                    grant.location,
                    request.contentLength,
                    request.contentType,
                    request.contentHash,
                    request.checksum,
                    request.metadata,
                ),
            )
        }

        fixture.client.uploadUsingGrant(grant, content)
        val finalization = fixture.adapter.finalizeUpload(
            PresignedUploadFinalizeRequest(
                request.bindingId,
                request.tenantId,
                grant.location,
                request.contentLength,
                request.contentType,
                request.contentHash,
                request.checksum,
                request.metadata,
            ),
        )

        assertEquals(request.tenantId, finalization.tenantId)
        assertEquals(request.bindingId, finalization.bindingId)
        assertEquals(grant.location, finalization.sourceLocation)
        assertNotEquals(grant.location, finalization.storedObject.location)
        assertTrue(finalization.storedObject.location.path.startsWith("oss-bound-v1/"))
        assertEquals(request.contentHash, finalization.storedObject.contentHash)
        assertEquals(request.checksum, finalization.checksum)
        assertTrue(finalization.revision.startsWith("oss-v1-version:"))
        assertEquals(request.metadata, finalization.metadata)
        assertEquals(OssRevisionKind.VERSION_ID, fixture.client.lastRevisionCondition?.kind)
    }

    @Test
    fun `reissues the same staging authority with the original absolute deadline and headers`() {
        val fixture = fixture()
        val content = "direct contract".toByteArray()
        val request = directRequest("tenant-a", "binding-a", content)
        val original = fixture.adapter.createUploadGrant(request)

        val reissued = fixture.adapter.reissueUploadGrant(
            PresignedUploadReissueRequest(
                request.bindingId,
                request.tenantId,
                original.location,
                request.contentLength,
                request.contentType,
                request.contentHash,
                request.checksum,
                request.metadata,
                original.requiredHeaders,
                original.expiresAt,
            ),
        )

        assertEquals(original.location, reissued.location)
        assertEquals(original.expiresAt, reissued.expiresAt)
        assertEquals(original.requiredHeaders, reissued.requiredHeaders)
        assertEquals(original.location.path, fixture.client.lastPresignedPut?.key)
        assertEquals(original.expiresAt, fixture.client.lastPresignedPut?.expiresAt)

        assertThrows(IllegalArgumentException::class.java) {
            fixture.adapter.reissueUploadGrant(
                PresignedUploadReissueRequest(
                    request.bindingId,
                    request.tenantId,
                    original.location,
                    request.contentLength,
                    request.contentType,
                    request.contentHash,
                    request.checksum,
                    request.metadata,
                    original.requiredHeaders + ("Content-Type" to "application/json"),
                    original.expiresAt,
                ),
            )
        }
    }

    @Test
    fun `finalization returns the exact declared metadata after provider key canonicalization`() {
        val fixture = fixture()
        val content = "direct contract".toByteArray()
        val request = directRequest(
            "tenant-a",
            "binding-a",
            content,
            metadata = mapOf("Business-Type" to "contract"),
        )
        val grant = fixture.adapter.createUploadGrant(request)
        fixture.client.uploadUsingGrant(grant, content)

        val finalization = fixture.adapter.finalizeUpload(finalizeRequest(request, grant.location))

        assertEquals(request.metadata, finalization.metadata)
        assertTrue(grant.requiredHeaders.containsKey("x-oss-meta-business-type"))
    }

    @Test
    fun `cleanup is idempotent for staging and rejects a revision bound final location`() {
        val fixture = fixture()
        val content = "direct contract".toByteArray()
        val request = directRequest("tenant-a", "binding-a", content)
        val grant = fixture.adapter.createUploadGrant(request)
        fixture.client.uploadUsingGrant(grant, content)
        fixture.client.uploadUsingGrant(grant, content)
        fixture.client.uploadUsingGrant(grant, content)
        assertEquals(3, fixture.client.versionCount(grant.location.path))
        val cleanup = PresignedUploadCleanupRequest(request.bindingId, request.tenantId, grant.location)

        fixture.adapter.cleanupUpload(cleanup)
        fixture.adapter.cleanupUpload(cleanup)

        assertEquals(3, fixture.client.deleteCount)
        assertFalse(fixture.adapter.exists(grant.location))

        val protectedFixture = fixture()
        val protectedGrant = protectedFixture.adapter.createUploadGrant(request)
        protectedFixture.client.uploadUsingGrant(protectedGrant, content)
        val finalization = protectedFixture.adapter.finalizeUpload(finalizeRequest(request, protectedGrant.location))
        assertThrows(IllegalArgumentException::class.java) {
            protectedFixture.adapter.cleanupUpload(
                PresignedUploadCleanupRequest(
                    request.bindingId,
                    request.tenantId,
                    finalization.storedObject.location,
                ),
            )
        }
        assertEquals(0, protectedFixture.client.deleteCount)
    }

    @Test
    fun `completed location remains immutable after signed URL replay creates a newer version`() {
        val fixture = fixture()
        val original = "aaaaaaaaaaaa".toByteArray()
        val replayed = "bbbbbbbbbbbb".toByteArray()
        val request = directRequest("tenant", "replay-binding", original)
        val grant = fixture.adapter.createUploadGrant(request)
        fixture.client.uploadUsingGrant(grant, original)
        fixture.client.replaceBeforeConditionalGet = true
        val finalization = fixture.adapter.finalizeUpload(finalizeRequest(request, grant.location))
        val boundLocation = finalization.storedObject.location
        val boundMetadata = fixture.adapter.metadata(boundLocation)
        assertEquals(2, fixture.client.versionCount(grant.location.path))

        // A versioned OSS bucket ignores forbid-overwrite. This fixture models
        // a second provider-accepted upload with the same signed MD5 but a
        // different SHA-256 (the security boundary must not depend on MD5
        // collision resistance).
        fixture.client.uploadUsingGrant(
            grant,
            replayed,
            simulateProviderAcceptedMd5Collision = true,
        )

        assertEquals(3, fixture.client.versionCount(grant.location.path))
        assertNotEquals(finalization.revision, fixture.adapter.metadata(grant.location).revision)
        assertEquals(finalization.revision, fixture.adapter.metadata(boundLocation).revision)
        fixture.adapter.download(boundLocation).content.use {
            assertArrayEquals(original, it.readBytes())
        }
        fixture.adapter.download(grant.location).content.use {
            assertArrayEquals(replayed, it.readBytes())
        }
        fixture.adapter.downloadRange(
            StorageRangeRequest(boundLocation, 2, 4, requireNotNull(boundMetadata.revision)),
        ).content.use {
            assertArrayEquals(original.copyOfRange(2, 6), it.readBytes())
        }
        val latestRevision = requireNotNull(fixture.adapter.metadata(grant.location).revision)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.adapter.downloadRange(StorageRangeRequest(boundLocation, 0, 1, latestRevision))
        }

        fixture.adapter.accessUrl(boundLocation, Duration.ofMinutes(1))
        val signedVersion = requireNotNull(fixture.client.lastPresignedGet?.versionId)
        assertEquals(OssRevisionKind.VERSION_ID, fixture.client.lastRevisionCondition?.kind)
        assertTrue(fixture.adapter.exists(boundLocation))
        fixture.client.overridePresignedGetVersion = true
        fixture.client.presignedGetVersionOverride = "$signedVersion&x-oss-security-token=attacker"
        assertThrows(IllegalArgumentException::class.java) {
            fixture.adapter.accessUrl(boundLocation, Duration.ofMinutes(1))
        }
        fixture.client.overridePresignedGetVersion = false
        assertThrows(IllegalArgumentException::class.java) {
            fixture.adapter.metadata(
                ai.icen.fw.spi.storage.StorageObjectLocation(
                    boundLocation.storageType,
                    boundLocation.path + "/ambiguous",
                ),
            )
        }

        fixture.adapter.delete(boundLocation)

        assertEquals(signedVersion, fixture.client.lastDeletedVersionId)
        assertFalse(fixture.adapter.exists(boundLocation))
        assertTrue(fixture.adapter.exists(grant.location))
        fixture.adapter.download(grant.location).content.use {
            assertArrayEquals(replayed, it.readBytes())
        }
    }

    @Test
    fun `fails closed on direct upload host HEAD or actual hash drift`() {
        val unexpectedHost = fixture().also { it.client.presignedHost = "attacker.example" }
        val content = "direct contract".toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            unexpectedHost.adapter.createUploadGrant(directRequest("tenant", "binding", content))
        }

        val excessiveSignedTtl = fixture().also { it.client.presignedExpiresSeconds = 301 }
        assertThrows(IllegalArgumentException::class.java) {
            excessiveSignedTtl.adapter.createUploadGrant(directRequest("tenant", "ttl-binding", content))
        }

        val headDrift = fixture()
        val headRequest = directRequest("tenant", "binding", content)
        val headGrant = headDrift.adapter.createUploadGrant(headRequest)
        headDrift.client.uploadUsingGrant(headGrant, content)
        val capture = requireNotNull(headDrift.client.lastPresignedPut)
        headDrift.client.replaceObjectForTest(
            headGrant.location.path,
            content,
            "application/json",
            capture.metadata,
            capture.contentMd5,
        )
        assertThrows(OssStorageOperationException::class.java) {
            headDrift.adapter.finalizeUpload(finalizeRequest(headRequest, headGrant.location))
        }

        val hashDrift = fixture()
        val hashRequest = directRequest("tenant", "binding", content)
        val hashGrant = hashDrift.adapter.createUploadGrant(hashRequest)
        val hashCapture = requireNotNull(hashDrift.client.lastPresignedPut)
        hashDrift.client.replaceObjectForTest(
            hashGrant.location.path,
            "evil payload!!!".toByteArray(),
            hashCapture.contentType,
            hashCapture.metadata,
            hashCapture.contentMd5,
        )
        val failure = assertThrows(OssStorageOperationException::class.java) {
            hashDrift.adapter.finalizeUpload(finalizeRequest(hashRequest, hashGrant.location))
        }
        assertEquals(OssStorageFailureCategory.INTEGRITY, failure.category)
    }

    @Test
    fun `fails closed when direct upload attestation changes size or omits checksum metadata and revision`() {
        val mutations: List<(FakeOssClientFacade) -> Unit> = listOf(
            { it.headContentLengthDelta = 1 },
            { it.omitHeadContentMd5 = true },
            { it.omitHeadCrc64 = true },
            { it.omitHeadMetadata = true },
            { it.omitHeadVersionId = true },
            { it.omitHeadStrongRevision = true },
        )
        mutations.forEachIndexed { index, mutate ->
            val fixture = fixture()
            val content = "attested-content-$index".toByteArray()
            val request = directRequest("tenant", "binding-$index", content)
            val grant = fixture.adapter.createUploadGrant(request)
            fixture.client.uploadUsingGrant(grant, content)
            mutate(fixture.client)

            assertThrows(OssStorageOperationException::class.java) {
                fixture.adapter.finalizeUpload(finalizeRequest(request, grant.location))
            }
        }
    }

    @Test
    fun `rejects provider revisions that cannot round trip through the opaque location codec`() {
        listOf(
            "版".repeat(1_024),
            "v".repeat(1_025),
        ).forEachIndexed { index, invalidVersionId ->
            val fixture = fixture()
            val content = "canonical-revision-$index".toByteArray()
            val request = directRequest("tenant", "canonical-binding-$index", content)
            val grant = fixture.adapter.createUploadGrant(request)
            fixture.client.uploadUsingGrant(grant, content)
            fixture.client.headVersionIdOverride = invalidVersionId

            assertThrows(IllegalArgumentException::class.java) {
                fixture.adapter.finalizeUpload(finalizeRequest(request, grant.location))
            }
        }
    }

    @Test
    fun `bounds direct grant by temporary credential lifetime and request safety`() {
        val now = 1_000_000L
        val configuration = configuration(
            StaticOssCredentialsProvider("access", "secret", "token", now + 20_000),
        )
        val policy = OssStorageClientPolicy(
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            1,
            Duration.ofSeconds(5),
        )
        val client = FakeOssClientFacade()
        val adapter = OssStorageAdapter.testInstance(
            configuration,
            policy,
            client,
            Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        )
        val content = "content".toByteArray()

        adapter.createUploadGrant(
            directRequest("tenant", "binding-1", content, Duration.ofSeconds(10)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            adapter.createUploadGrant(
                directRequest("tenant", "binding-2", content, Duration.ofSeconds(16)),
            )
        }

        val unknownExpiry = fixture(
            configuration(StaticOssCredentialsProvider("access", "secret", "token")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            unknownExpiry.adapter.createUploadGrant(directRequest("tenant", "binding-3", content))
        }

        val oversized = directRequest("tenant", "binding-4", content).let { request ->
            PresignedUploadGrantRequest(
                request.bindingId,
                request.tenantId,
                request.objectName,
                5L * 1024L * 1024L * 1024L + 1L,
                request.contentType,
                request.contentHash,
                request.checksum,
                request.metadata,
                request.expiresIn,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture().adapter.createUploadGrant(oversized)
        }
    }

    @Test
    fun `validates virtual host path style and CNAME signed URL shapes exactly`() {
        val content = "content".toByteArray()

        val pathStyle = fixture(
            OssStorageConfiguration(
                URI.create("https://oss-cn-hangzhou.aliyuncs.com"),
                "cn-hangzhou",
                "flowweft-oss-test",
                StaticOssCredentialsProvider("access", "secret"),
                usePathStyle = true,
            ),
        )
        pathStyle.client.presignedHost = "oss-cn-hangzhou.aliyuncs.com"
        pathStyle.client.presignedPathPrefix = "/flowweft-oss-test"
        pathStyle.adapter.createUploadGrant(directRequest("tenant", "path-style", content))

        val cname = fixture(
            OssStorageConfiguration(
                URI.create("https://uploads.example.com"),
                "cn-hangzhou",
                "flowweft-oss-test",
                StaticOssCredentialsProvider("access", "secret"),
                useCName = true,
            ),
        )
        cname.client.presignedHost = "uploads.example.com"
        cname.adapter.createUploadGrant(directRequest("tenant", "cname", content))

        pathStyle.client.presignedPathPrefix = "/another-bucket"
        assertThrows(IllegalArgumentException::class.java) {
            pathStyle.adapter.createUploadGrant(directRequest("tenant", "wrong-path", content))
        }
    }

    @Test
    fun `creates opaque independent keys for tenant and object retries`() {
        val fixture = fixture()
        val content = "same bytes".toByteArray()
        val first = fixture.adapter.upload(request("tenant-a", content), ByteArrayInputStream(content))
        val second = fixture.adapter.upload(request("tenant-a", content), ByteArrayInputStream(content))
        val otherTenant = fixture.adapter.upload(request("tenant-b", content), ByteArrayInputStream(content))

        assertNotEquals(first.location, second.location)
        assertNotEquals(first.location, otherTenant.location)
        assertTrue(first.location.path.startsWith("oss-bound-v1/"))
        assertEquals(3, fixture.client.objectKeys.size)
        assertTrue(
            fixture.client.objectKeys.all { key ->
                key.matches(Regex("objects/[0-9a-f]{64}/[0-9a-f]{32}"))
            },
        )
        assertEquals(2, fixture.client.objectKeys.map { it.substringBeforeLast('/') }.distinct().size)
    }

    @Test
    fun `binds each range to HEAD revision and accepts only matching 206 Content-Range`() {
        val fixture = fixture()
        val content = "0123456789".toByteArray()
        val stored = fixture.adapter.upload(request("tenant", content), ByteArrayInputStream(content))
        val metadata = fixture.adapter.metadata(stored.location)

        val download = fixture.adapter.downloadRange(
            StorageRangeRequest(stored.location, 2, 4, requireNotNull(metadata.revision)),
        )

        assertEquals(4L, download.contentLength)
        download.content.use { assertArrayEquals("2345".toByteArray(), it.readBytes()) }
        assertEquals(OssRevisionKind.VERSION_ID, fixture.client.lastRevisionCondition?.kind)
        assertEquals("version-1", fixture.client.lastRevisionCondition?.value)
    }

    @Test
    fun `rejects a full response or malformed Content-Range and aborts its stream`() {
        val content = "0123456789".toByteArray()
        listOf<Pair<Int?, String?>>(
            200 to null,
            null to "bytes 1-9/10",
        ).forEach { (status, header) ->
            val fixture = fixture()
            val stored = fixture.adapter.upload(request("tenant", content), ByteArrayInputStream(content))
            val revision = requireNotNull(fixture.adapter.metadata(stored.location).revision)
            fixture.client.rangeStatusOverride = status
            fixture.client.rangeHeaderOverride = header

            val failure = assertThrows(OssStorageOperationException::class.java) {
                fixture.adapter.downloadRange(StorageRangeRequest(stored.location, 2, 4, revision))
            }

            assertEquals(OssStorageFailureCategory.INTEGRITY, failure.category)
            assertEquals(1, fixture.client.abortedResponseCount)
        }
    }

    @Test
    fun `paginates authoritative parts and rejects stale ETag before complete`() {
        val fixture = fixture()
        val expected = "new-second".toByteArray()
        val upload = fixture.adapter.beginMultipartUpload(request("tenant", expected))
        val old = fixture.adapter.uploadPart(upload, 1, ByteArrayInputStream("old".toByteArray()), 3)
        val latest = fixture.adapter.uploadPart(upload, 1, ByteArrayInputStream("new".toByteArray()), 3)
        val secondBytes = "-second".toByteArray()
        val second = fixture.adapter.uploadPart(upload, 2, ByteArrayInputStream(secondBytes), secondBytes.size.toLong())
        fixture.client.forcePartPagination = true

        assertEquals(listOf(latest, second), fixture.adapter.listUploadedParts(upload))
        assertThrows(MultipartCompletionRejectedException::class.java) {
            fixture.adapter.completeMultipartUpload(upload, listOf(old, second))
        }
        assertEquals(0, fixture.client.completedUploadCount)
        assertFalse(fixture.adapter.exists(upload.location))

        val stored = fixture.adapter.completeMultipartUpload(upload, listOf(latest, second))
        assertEquals(1, fixture.client.completedUploadCount)
        fixture.adapter.download(stored.location).content.use {
            assertArrayEquals(expected, it.readBytes())
        }
        assertTrue(stored.contentHash?.startsWith("sha256:") == true)
        assertTrue(stored.location.path.startsWith("oss-bound-v1/"))
    }

    @Test
    fun `binds PUT and multipart results to the exact provider response version`() {
        val putFixture = fixture()
        val original = "original".toByteArray()
        val putStored = putFixture.adapter.upload(
            request("tenant", original),
            ByteArrayInputStream(original),
        )
        val putKey = putFixture.client.objectKeys.single()
        putFixture.client.replaceObjectForTest(
            putKey,
            "new-latest".toByteArray(),
            "text/plain",
            mapOf("business-type" to "contract"),
        )
        putFixture.adapter.download(putStored.location).content.use {
            assertArrayEquals(original, it.readBytes())
        }

        val multipartFixture = fixture()
        val multipartBytes = "multipart".toByteArray()
        val upload = multipartFixture.adapter.beginMultipartUpload(request("tenant", multipartBytes))
        val part = multipartFixture.adapter.uploadPart(
            upload,
            1,
            ByteArrayInputStream(multipartBytes),
            multipartBytes.size.toLong(),
        )
        multipartFixture.client.advanceCurrentVersionAfterComplete = true

        val multipartStored = multipartFixture.adapter.completeMultipartUpload(upload, listOf(part))

        assertTrue(multipartStored.location.path.startsWith("oss-bound-v1/"))
        assertEquals(2, multipartFixture.client.versionCount(upload.location.path))
        multipartFixture.adapter.download(multipartStored.location).content.use {
            assertArrayEquals(multipartBytes, it.readBytes())
        }
        assertEquals("version-1", multipartFixture.client.lastRevisionCondition?.value)
    }

    @Test
    fun `fails closed and cleans an acknowledged write without immutable response evidence`() {
        listOf<(FakeOssClientFacade) -> Unit>(
            { it.omitWriteVersionId = true },
            { it.omitWriteETag = true },
        ).forEach { mutate ->
            val fixture = fixture()
            mutate(fixture.client)
            val bytes = "content".toByteArray()

            val failure = assertThrows(OssStorageOperationException::class.java) {
                fixture.adapter.upload(request("tenant", bytes), ByteArrayInputStream(bytes))
            }

            assertEquals(OssStorageFailureCategory.INTEGRITY, failure.category)
            assertEquals(1, fixture.client.deleteCount)
            assertTrue(fixture.client.objectKeys.isEmpty())
        }
    }

    @Test
    fun `reconciles multipart completion whose response was lost and remains retry safe`() {
        val fixture = fixture()
        val bytes = "completion-unknown".toByteArray()
        val upload = fixture.adapter.beginMultipartUpload(request("tenant", bytes))
        val part = fixture.adapter.uploadPart(
            upload,
            1,
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        fixture.client.completeFailureAfterStore = SocketTimeoutException("signed URL must stay private")

        val first = fixture.adapter.completeMultipartUpload(upload, listOf(part))
        val retried = fixture.adapter.completeMultipartUpload(upload, listOf(part))

        assertEquals(first.location, retried.location)
        assertTrue(first.location.path.startsWith("oss-bound-v1/"))
        assertEquals(1, fixture.client.completedUploadCount)
        fixture.adapter.download(first.location).content.use {
            assertArrayEquals(bytes, it.readBytes())
        }

        val unknown = fixture()
        val unknownUpload = unknown.adapter.beginMultipartUpload(request("tenant", bytes))
        val unknownPart = unknown.adapter.uploadPart(
            unknownUpload,
            1,
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        unknown.client.completeFailureBeforeStore = SocketTimeoutException("private request")
        assertThrows(OssStorageOperationException::class.java) {
            unknown.adapter.completeMultipartUpload(unknownUpload, listOf(unknownPart))
        }
        assertFalse(unknown.adapter.exists(unknownUpload.location))
    }

    @Test
    fun `makes delete and abort idempotent only for the matching missing resource`() {
        val fixture = fixture()
        val content = "content".toByteArray()
        val stored = fixture.adapter.upload(request("tenant", content), ByteArrayInputStream(content))

        fixture.adapter.delete(stored.location)
        assertEquals("version-1", fixture.client.lastDeletedVersionId)
        fixture.adapter.delete(stored.location)
        assertFalse(fixture.adapter.exists(stored.location))

        val upload = fixture.adapter.beginMultipartUpload(request("tenant", content))
        fixture.adapter.abortMultipartUpload(upload)
        fixture.adapter.abortMultipartUpload(upload)
        assertFalse(fixture.adapter.exists(upload.location))
    }

    @Test
    fun `does not delete on PUT conflict or timeout outcome unknown and accepts missing exact version`() {
        listOf(
            serviceFailure("FileAlreadyExists") to false,
            SocketTimeoutException("timeout") to true,
        ).forEach { (failure, storeFirst) ->
            val fixture = fixture()
            if (storeFirst) fixture.client.putFailureAfterStore = failure else fixture.client.putFailureBeforeStore = failure
            assertThrows(OssStorageOperationException::class.java) {
                val bytes = "content".toByteArray()
                fixture.adapter.upload(request("tenant", bytes), ByteArrayInputStream(bytes))
            }
            assertEquals(0, fixture.client.deleteCount)
            assertEquals(if (storeFirst) 1 else 0, fixture.client.objectKeys.size)
        }

        val missingVersion = fixture()
        val bytes = "content".toByteArray()
        val stored = missingVersion.adapter.upload(request("tenant", bytes), ByteArrayInputStream(bytes))
        missingVersion.client.deleteFailure = serviceFailure("NoSuchVersion")
        missingVersion.adapter.delete(stored.location)
        assertTrue(missingVersion.adapter.exists(stored.location))
    }

    private fun fixture(configuration: OssStorageConfiguration = configuration()): Fixture {
        val client = FakeOssClientFacade()
        return Fixture(
            OssStorageAdapter.testInstance(configuration, OssStorageClientPolicy(), client),
            client,
        )
    }

    private fun configuration(
        credentialsProvider: OssCredentialsProvider = StaticOssCredentialsProvider("access", "secret"),
    ): OssStorageConfiguration = OssStorageConfiguration(
            URI.create("https://oss-cn-hangzhou.aliyuncs.com"),
            "cn-hangzhou",
            "flowweft-oss-test",
            credentialsProvider,
        )

    private fun request(tenant: String, content: ByteArray): StorageUploadRequest = StorageUploadRequest(
        tenantId = Identifier(tenant),
        objectName = "same-name.txt",
        contentLength = content.size.toLong(),
        contentType = "text/plain",
        metadata = mapOf("business-type" to "contract"),
    )

    private fun directRequest(
        tenant: String,
        binding: String,
        content: ByteArray,
        expiresIn: Duration = Duration.ofMinutes(5),
        metadata: Map<String, String> = mapOf("business-type" to "contract"),
    ): PresignedUploadGrantRequest = PresignedUploadGrantRequest(
        bindingId = Identifier(binding),
        tenantId = Identifier(tenant),
        objectName = "contract.txt",
        contentLength = content.size.toLong(),
        contentType = "text/plain",
        contentHash = "sha256:${sha256(content)}",
        checksum = StorageContentChecksum("md5", md5(content)),
        metadata = metadata,
        expiresIn = expiresIn,
    )

    private fun finalizeRequest(
        request: PresignedUploadGrantRequest,
        location: ai.icen.fw.spi.storage.StorageObjectLocation,
    ): PresignedUploadFinalizeRequest = PresignedUploadFinalizeRequest(
        request.bindingId,
        request.tenantId,
        location,
        request.contentLength,
        request.contentType,
        request.contentHash,
        request.checksum,
        request.metadata,
    )

    private data class Fixture(
        val adapter: OssStorageAdapter,
        val client: FakeOssClientFacade,
    )
}
