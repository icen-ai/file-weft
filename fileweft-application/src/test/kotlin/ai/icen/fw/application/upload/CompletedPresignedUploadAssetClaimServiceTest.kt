package ai.icen.fw.application.upload

import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyClaim
import ai.icen.fw.application.idempotency.RequestIdempotencyRecord
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyStatus
import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.storage.PresignedUploadCleanupRequest
import ai.icen.fw.spi.storage.PresignedUploadFinalization
import ai.icen.fw.spi.storage.PresignedUploadFinalizeRequest
import ai.icen.fw.spi.storage.PresignedUploadGrant
import ai.icen.fw.spi.storage.PresignedUploadGrantRequest
import ai.icen.fw.spi.storage.PresignedUploadReissueRequest
import ai.icen.fw.spi.storage.PresignedUploadStorageAdapter
import ai.icen.fw.spi.storage.StorageContentChecksum
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletedPresignedUploadAssetClaimServiceTest {
    @Test
    fun `provider completion and local asset claim produce one stable authorized replay`() {
        val fixture = Fixture()
        val command = CompletePresignedUploadAssetCommand(UPLOAD_ID, "finalize-1")

        val first = fixture.claims.finalizeUpload(command)
        assertEquals(
            2,
            fixture.authorization.requests.count { it.action.name == CLAIM_ACTION },
        )
        assertEquals(
            2,
            fixture.authorization.requests.count { it.action.name == FINALIZE_ACTION },
        )
        val replay = fixture.claims.finalizeUpload(command)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.fileObjectId, replay.fileObjectId)
        assertEquals(first.fileAssetId, replay.fileAssetId)
        assertEquals(1, fixture.storage.finalizeCalls)
        assertEquals(first.fileObjectId, fixture.repository.claim?.fileObjectId)
        assertEquals("bound/upload-1/version-1", fixture.fileObjects.getValue(first.fileObjectId).storagePath)
        assertEquals(first.fileObjectId, fixture.fileAssets.getValue(first.fileAssetId).fileObjectId)
        assertEquals("DOCUMENT", fixture.fileAssets.getValue(first.fileAssetId).assetType)

        assertFailsWith<CompletedPresignedUploadAssetClaimConflictException> {
            fixture.claims.finalizeUpload(CompletePresignedUploadAssetCommand(UPLOAD_ID, "finalize-2"))
        }
    }

    @Test
    fun `consume authorization is checked before invoking the provider`() {
        val fixture = Fixture()
        fixture.authorization.deniedAction = CLAIM_ACTION

        assertFailsWith<ApplicationForbiddenException> {
            fixture.claims.finalizeUpload(CompletePresignedUploadAssetCommand(UPLOAD_ID, "denied-1"))
        }

        assertEquals(0, fixture.storage.finalizeCalls)
        assertTrue(fixture.fileObjects.isEmpty())
        assertTrue(fixture.fileAssets.isEmpty())
    }

    @Test
    fun `invalid idempotency key is rejected before provider completion`() {
        val fixture = Fixture()

        assertFailsWith<IllegalArgumentException> {
            fixture.claims.finalizeUpload(
                CompletePresignedUploadAssetCommand(UPLOAD_ID, "contains a space"),
            )
        }

        assertEquals(0, fixture.storage.finalizeCalls)
    }

    private class Fixture {
        val identifiers = SequenceIdentifiers()
        val repository = MemoryPresignedRepository(readySession())
        val storage = FinalizingStorage()
        val authorization = RecordingAuthorization()
        val fileObjects = linkedMapOf<Identifier, FileObject>()
        val fileAssets = linkedMapOf<Identifier, FileAsset>()
        private val transaction = DirectTransaction
        private val uploads = PresignedUploadService(
            FixedTenant,
            FixedUser,
            authorization,
            storage,
            repository,
            identifiers,
            CLOCK,
            transaction = transaction,
        )
        val claims = CompletedPresignedUploadAssetClaimService(
            FixedTenant,
            FixedUser,
            authorization,
            uploads,
            repository,
            MemoryFileObjectRepository(fileObjects),
            MemoryFileAssetRepository(fileAssets),
            MemoryIdempotencyRepository(),
            transaction,
            identifiers,
            CLOCK,
        )
    }

    private class MemoryPresignedRepository(
        private var session: PresignedUploadSession,
    ) : CompletedPresignedUploadAssetClaimRepository {
        var claim: CompletedPresignedUploadAssetClaim? = null

        override fun create(session: PresignedUploadSession): Boolean = false

        override fun findById(tenantId: Identifier, sessionId: Identifier): PresignedUploadSession? =
            session.takeIf { it.tenantId == tenantId && it.id == sessionId }

        override fun findById(tenantId: Identifier, ownerId: String, sessionId: Identifier): PresignedUploadSession? =
            findById(tenantId, sessionId)?.takeIf { it.ownerId == ownerId }

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKeyDigest: String,
        ): PresignedUploadSession? = findById(tenantId, ownerId, session.id)
            ?.takeIf { it.idempotencyKeyDigest == idempotencyKeyDigest }

        override fun findRecoveryCandidates(now: Long, limit: Int): List<PresignedUploadSession> = emptyList()
        override fun findCleanupCandidates(now: Long, limit: Int): List<PresignedUploadSession> = emptyList()

        @Synchronized
        override fun compareAndSet(
            tenantId: Identifier,
            sessionId: Identifier,
            expectedVersion: Long,
            replacement: PresignedUploadSession,
        ): Boolean = compareAndSet(tenantId, sessionId, expectedVersion, null, replacement)

        @Synchronized
        override fun compareAndSet(
            tenantId: Identifier,
            sessionId: Identifier,
            expectedVersion: Long,
            expectedClaimToken: String?,
            replacement: PresignedUploadSession,
        ): Boolean {
            if (
                session.tenantId != tenantId || session.id != sessionId ||
                session.version != expectedVersion || session.claimToken != expectedClaimToken
            ) return false
            session = replacement
            return true
        }

        override fun lockCompletedAssetClaim(
            tenantId: Identifier,
            ownerId: String,
            uploadId: Identifier,
        ): CompletedPresignedUploadAssetClaimState? = findCompletedAssetClaim(tenantId, ownerId, uploadId)

        override fun findCompletedAssetClaim(
            tenantId: Identifier,
            ownerId: String,
            uploadId: Identifier,
        ): CompletedPresignedUploadAssetClaimState? = findById(tenantId, ownerId, uploadId)?.let {
            CompletedPresignedUploadAssetClaimState(it, claim)
        }

        @Synchronized
        override fun markCompletedAssetClaimed(
            expected: PresignedUploadSession,
            claim: CompletedPresignedUploadAssetClaim,
        ): CompletedPresignedUploadAssetClaimState? {
            if (this.claim != null || session.version != expected.version || claim.claimedTime >= session.sessionExpiresAt) {
                return null
            }
            session = copySession(session, Math.addExact(session.version, 1))
            this.claim = claim
            return CompletedPresignedUploadAssetClaimState(session, claim)
        }
    }

    private class FinalizingStorage : PresignedUploadStorageAdapter {
        var finalizeCalls = 0

        override fun createUploadGrant(request: PresignedUploadGrantRequest): PresignedUploadGrant =
            PresignedUploadGrant(
                requestLocation(),
                URI.create("https://uploads.example/object?signature=opaque"),
                mapOf("Content-Type" to request.contentType),
                1_000,
            )

        override fun reissueUploadGrant(request: PresignedUploadReissueRequest): PresignedUploadGrant =
            throw UnsupportedOperationException()

        override fun finalizeUpload(request: PresignedUploadFinalizeRequest): PresignedUploadFinalization {
            finalizeCalls++
            return PresignedUploadFinalization(
                request.tenantId,
                request.bindingId,
                request.location,
                StoredObject(
                    StorageObjectLocation("test", "bound/${request.bindingId.value}/version-1"),
                    request.contentLength,
                    request.contentType,
                    request.contentHash,
                ),
                "version-1",
                request.checksum,
                request.metadata,
            )
        }

        override fun cleanupUpload(request: PresignedUploadCleanupRequest) = Unit
    }

    private class MemoryFileObjectRepository(
        private val values: MutableMap<Identifier, FileObject>,
    ) : FileObjectRepository {
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? =
            values[fileObjectId]?.takeIf { it.tenantId == tenantId }
        override fun save(fileObject: FileObject) { values[fileObject.id] = fileObject }
    }

    private class MemoryFileAssetRepository(
        private val values: MutableMap<Identifier, FileAsset>,
    ) : FileAssetRepository {
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
            values[fileAssetId]?.takeIf { it.tenantId == tenantId }
        override fun save(fileAsset: FileAsset) { values[fileAsset.id] = fileAsset }
    }

    private class MemoryIdempotencyRepository : RequestIdempotencyRepository {
        private val records = linkedMapOf<String, RequestIdempotencyRecord>()

        override fun findByKeyDigest(tenantId: Identifier, keyDigest: String): RequestIdempotencyRecord? =
            records[keyDigest]?.takeIf { it.tenantId == tenantId }

        override fun claim(request: RequestIdempotency, newRecordId: Identifier, now: Long): RequestIdempotencyClaim {
            records[request.keyDigest]?.let { return RequestIdempotencyClaim(it, false) }
            val record = RequestIdempotencyRecord(
                newRecordId, request.tenantId, request.keyDigest, request.operatorId, request.action,
                request.resourceType, request.resourceId, request.subresourceId, request.requestFingerprint,
                RequestIdempotencyStatus.IN_PROGRESS, null, null, now, now,
            )
            records[request.keyDigest] = record
            return RequestIdempotencyClaim(record, true)
        }

        override fun complete(
            recordId: Identifier,
            tenantId: Identifier,
            keyDigest: String,
            result: IdempotencyResult,
            completedAt: Long,
        ): RequestIdempotencyRecord {
            val before = records.getValue(keyDigest)
            return RequestIdempotencyRecord(
                before.id, before.tenantId, before.keyDigest, before.operatorId, before.action,
                before.resourceType, before.resourceId, before.subresourceId, before.requestFingerprint,
                RequestIdempotencyStatus.COMPLETED, result, completedAt, before.createdTime, completedAt,
            ).also { records[keyDigest] = it }
        }
    }

    private class SequenceIdentifiers : IdentifierGenerator {
        private val sequence = AtomicInteger()
        override fun nextId(): Identifier = Identifier("generated-${sequence.incrementAndGet()}")
    }

    private class RecordingAuthorization : AuthorizationProvider {
        var deniedAction: String? = null
        val requests = mutableListOf<AuthorizationRequest>()
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            return AuthorizationDecision(request.action.name != deniedAction)
        }
    }

    private object FixedTenant : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
    }

    private object FixedUser : UserRealmProvider {
        private val user = UserIdentity(Identifier(OWNER_ID))
        override fun currentUser(): UserIdentity = user
        override fun findUser(userId: Identifier): UserIdentity? = user.takeIf { it.id == userId }
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val UPLOAD_ID = Identifier("upload-1")
        const val OWNER_ID = "owner-1"
        const val CLAIM_ACTION = "file:upload:consume"
        const val FINALIZE_ACTION = "file:upload:complete"
        const val CONTENT_HASH = "sha256:239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5"
        val CHECKSUM = StorageContentChecksum("md5", "CY9rzUYh03PK3k6DJie09g==")
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(500), ZoneOffset.UTC)

        fun requestLocation() = StorageObjectLocation("test", "objects/tenant/upload-1")

        fun readySession() = PresignedUploadSession(
            id = UPLOAD_ID,
            tenantId = TENANT_ID,
            ownerId = OWNER_ID,
            fileName = "contract.txt",
            contentLength = 7,
            contentType = "text/plain",
            contentHash = CONTENT_HASH,
            checksum = CHECKSUM,
            metadata = mapOf("business" to "legal"),
            storageLocation = requestLocation(),
            grantExpiresAt = 1_000,
            sessionExpiresAt = 2_000,
            createdTime = 100,
            updatedTime = 100,
        )

        fun copySession(source: PresignedUploadSession, version: Long) = PresignedUploadSession(
            id = source.id,
            tenantId = source.tenantId,
            ownerId = source.ownerId,
            fileName = source.fileName,
            contentLength = source.contentLength,
            contentType = source.contentType,
            contentHash = source.contentHash,
            checksum = source.checksum,
            metadata = source.metadata,
            storageLocation = source.stagingLocation,
            grantExpiresAt = source.grantExpiresAt,
            sessionExpiresAt = source.sessionExpiresAt,
            status = source.status,
            version = version,
            claimTime = source.claimTime,
            finalization = source.finalization,
            lastError = source.lastError,
            createdTime = source.createdTime,
            updatedTime = source.updatedTime,
            idempotencyKeyDigest = source.idempotencyKeyDigest,
            declarationDigest = source.declarationDigest,
            grantDurationMillis = source.grantDurationMillis,
            requiredHeaders = source.requiredHeaders,
            claimToken = source.claimToken,
            claimExpiresAt = source.claimExpiresAt,
            completedTime = source.completedTime,
            cancelledTime = source.cancelledTime,
            cleanupTime = source.cleanupTime,
        )
    }
}
