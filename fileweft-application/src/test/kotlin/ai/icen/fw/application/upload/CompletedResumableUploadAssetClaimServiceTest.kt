package ai.icen.fw.application.upload

import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.IdempotencyKeyConflictException
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyClaim
import ai.icen.fw.application.idempotency.RequestIdempotencyRecord
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyStatus
import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionState
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletedResumableUploadAssetClaimServiceTest {
    @Test
    fun `commands reject control characters before authorization or persistence`() {
        assertFailsWith<IllegalArgumentException> {
            CreateDocumentFromCompletedUploadCommand(
                UPLOAD_ID,
                "DOC-1\nforged",
                "Title",
                "claim-key",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CreateDocumentFromCompletedUploadCommand(
                UPLOAD_ID,
                "DOC-1",
                "Title\u0000forged",
                "claim-key",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AddDocumentVersionFromCompletedUploadCommand(
                UPLOAD_ID,
                EXISTING_DOCUMENT_ID,
                "2.0\u007fforged",
                "version-key",
            )
        }
    }

    @Test
    fun `creates one document and returns stable authorized replay while rejecting a different key`() {
        val fixture = Fixture()
        val command = CreateDocumentFromCompletedUploadCommand(
            fixture.uploadId,
            "DOC-1",
            "供应合同",
            "claim-key-1",
        )

        val first = fixture.service.createDocument(command)
        val replay = fixture.service.createDocument(command)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.documentId, replay.documentId)
        assertEquals(first.versionId, replay.versionId)
        assertEquals(fixture.fileObject.id, replay.fileObjectId)
        assertEquals(fixture.fileAsset.id, replay.fileAssetId)
        assertEquals(1, fixture.state.documents.size)
        assertEquals(first.documentId, fixture.state.claims.getValue(fixture.uploadId).resourceId)
        assertEquals(4, fixture.authorization.requests.size)
        assertEquals(2, fixture.identityReads.get())
        assertEquals(
            listOf(first.documentId, first.documentId),
            fixture.authorization.requests
                .filter { request -> request.resource.type == "DOCUMENT" }
                .map { request -> request.resource.id },
        )
        assertTrue(
            fixture.authorization.requests.none { request ->
                request.resource.type == "DOCUMENT" && request.resource.id == fixture.uploadId
            },
        )

        assertFailsWith<CompletedResumableUploadAssetClaimConflictException> {
            fixture.service.createDocument(
                CreateDocumentFromCompletedUploadCommand(fixture.uploadId, "DOC-2", "另一个文档", "claim-key-2"),
            )
        }
        assertEquals(1, fixture.state.documents.size)
    }

    @Test
    fun `same idempotency key with a different fingerprint conflicts before touching the claimed asset`() {
        val fixture = Fixture()
        val original = CreateDocumentFromCompletedUploadCommand(
            fixture.uploadId,
            "DOC-1",
            "供应合同",
            "same-key",
        )
        val first = fixture.service.createDocument(original)
        val readsBefore = fixture.claimRepository.readCount

        assertFailsWith<IdempotencyKeyConflictException> {
            fixture.service.createDocument(
                CreateDocumentFromCompletedUploadCommand(
                    fixture.uploadId,
                    "DOC-2",
                    "不同请求",
                    "same-key",
                ),
            )
        }

        assertEquals(readsBefore, fixture.claimRepository.readCount)
        assertEquals(first.documentId, fixture.state.claims.getValue(fixture.uploadId).resourceId)
        assertEquals(1, fixture.state.documents.size)
        assertEquals(1, fixture.state.idempotency.size)
    }

    @Test
    fun `adds a version using the completed file binding and reauthorizes replay`() {
        val fixture = Fixture(existingDocument = true)
        val command = AddDocumentVersionFromCompletedUploadCommand(
            fixture.uploadId,
            EXISTING_DOCUMENT_ID,
            "2.0",
            "version-key-1",
        )

        val first = fixture.service.addDocumentVersion(command)
        val replay = fixture.service.addDocumentVersion(command)
        val document = fixture.documents.findById(fixture.tenantId, EXISTING_DOCUMENT_ID)!!

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.versionId, replay.versionId)
        assertEquals(fixture.fileObject.id, document.versions.single { it.id == first.versionId }.fileObjectId)
        assertEquals(4, fixture.authorization.requests.size)
        assertTrue(fixture.authorization.requests.any { it.action.name == "document:edit" })
    }

    @Test
    fun `fails closed for owner purpose expiry status binding and repository capability`() {
        listOf(
            Fixture(session = completedSession(ownerId = "someone-else")),
            Fixture(session = completedSession(assetType = "ATTACHMENT")),
            Fixture(session = completedSession(expiresAt = NOW)),
            Fixture(session = completedSession(status = ResumableUploadSessionStatus.ACTIVE, completedAt = null)),
            Fixture(fileAssetOverride = FileAsset(ASSET_ID, TENANT_ID, Identifier("different-file"), "DOCUMENT")),
        ).forEachIndexed { index, fixture ->
            assertFailsWith<RuntimeException>("fixture $index must fail closed") {
                fixture.service.createDocument(
                    CreateDocumentFromCompletedUploadCommand(fixture.uploadId, "DOC-$index", "标题", "key-$index"),
                )
            }
            assertTrue(fixture.state.documents.isEmpty())
        }

        val fixture = Fixture()
        fixture.uploadSessions = LegacySessionRepository(fixture.session)
        assertFailsWith<CompletedResumableUploadAssetClaimUnavailableException> {
            fixture.rebuildService().createDocument(
                CreateDocumentFromCompletedUploadCommand(fixture.uploadId, "DOC-X", "标题", "missing-capability"),
            )
        }
    }

    @Test
    fun `concurrent different keys produce exactly one durable consumer`() {
        val fixture = Fixture()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(
                listOf(
                    Callable { runCatching { fixture.service.createDocument(command("race-a", "DOC-A")) } },
                    Callable { runCatching { fixture.service.createDocument(command("race-b", "DOC-B")) } },
                ),
            ).map { it.get() }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is CompletedResumableUploadAssetClaimConflictException })
            assertEquals(1, fixture.state.documents.size)
            assertEquals(1, fixture.state.claims.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `revoked document authorization blocks replay before claimed asset lookup`() {
        val fixture = Fixture()
        val command = command("authorized-replay", "DOC-AUTH")
        fixture.service.createDocument(command)
        val recordsBefore = fixture.state.idempotency.size
        val readsBefore = fixture.claimRepository.readCount
        fixture.authorization.deniedAction = "document:create"

        assertFailsWith<ApplicationForbiddenException> { fixture.service.createDocument(command) }
        assertEquals(recordsBefore, fixture.state.idempotency.size)
        assertEquals(readsBefore, fixture.claimRepository.readCount)
    }

    @Test
    fun `failure after the durable marker write rolls back document claim and idempotency together`() {
        val fixture = Fixture()
        val command = command("rollback-key", "DOC-ROLLBACK")
        fixture.claimRepository.failAfterMark = true

        assertFailsWith<IllegalStateException> { fixture.service.createDocument(command) }

        assertTrue(fixture.state.documents.isEmpty())
        assertTrue(fixture.state.claims.isEmpty())
        assertTrue(fixture.state.idempotency.isEmpty())
        assertEquals(100L, fixture.state.sessions.getValue(fixture.uploadId).updatedTime)

        fixture.claimRepository.failAfterMark = false
        val recovered = fixture.service.createDocument(command)
        assertFalse(recovered.replayed)
        assertEquals(1, fixture.state.documents.size)
        assertEquals(1, fixture.state.claims.size)
        assertEquals(1, fixture.state.idempotency.size)
    }

    @Test
    fun `authorized replay revalidates the exact persisted file and asset binding`() {
        val fixture = Fixture()
        val command = command("binding-replay", "DOC-BINDING")
        fixture.service.createDocument(command)
        fixture.state.fileAssets[fixture.fileAsset.id] = FileAsset(
            fixture.fileAsset.id,
            fixture.fileAsset.tenantId,
            fixture.fileAsset.fileObjectId,
            fixture.fileAsset.assetType,
            mapOf("source" to "tampered"),
        )

        assertFailsWith<CompletedResumableUploadAssetClaimStateException> {
            fixture.service.createDocument(command)
        }
    }

    @Test
    fun `wrong tenant owner and unknown upload share the opaque not found boundary`() {
        val wrongTenant = Fixture(requestTenantId = Identifier("tenant-2"))
        val wrongOwner = Fixture(currentUserId = "user-2")
        val unknown = Fixture()

        listOf(
            runCatching { wrongTenant.service.createDocument(command("wrong-tenant", "DOC-TENANT")) },
            runCatching { wrongOwner.service.createDocument(command("wrong-owner", "DOC-OWNER")) },
            runCatching {
                unknown.service.createDocument(
                    CreateDocumentFromCompletedUploadCommand(
                        Identifier("unknown-upload"),
                        "DOC-UNKNOWN",
                        "未知上传",
                        "unknown-key",
                    ),
                )
            },
        ).forEach { result ->
            assertTrue(result.exceptionOrNull() is CompletedResumableUploadAssetNotFoundException)
        }
        assertTrue(wrongTenant.state.idempotency.isEmpty())
        assertTrue(wrongOwner.state.idempotency.isEmpty())
        assertTrue(unknown.state.idempotency.isEmpty())
    }

    private fun command(key: String, number: String) = CreateDocumentFromCompletedUploadCommand(
        UPLOAD_ID,
        number,
        "并发文档",
        key,
    )

    private class Fixture(
        val session: ResumableUploadSession = completedSession(),
        existingDocument: Boolean = false,
        fileAssetOverride: FileAsset? = null,
        requestTenantId: Identifier = TENANT_ID,
        private val currentUserId: String = OWNER_ID,
    ) {
        val tenantId = requestTenantId
        val uploadId = session.id
        val fileObject = completedFileObject(session)
        val fileAsset = fileAssetOverride ?: completedFileAsset(session)
        val state = MemoryState()
        val transaction = MemoryTransaction(state)
        val identifiers = SequenceIdentifiers()
        val authorization = RecordingAuthorization()
        val identityReads = AtomicInteger()
        val documents = MemoryDocumentRepository(state)
        val claimRepository = MemoryUploadSessions(state)
        var uploadSessions: ResumableUploadSessionRepository = claimRepository
        lateinit var service: CompletedResumableUploadAssetClaimService

        init {
            state.sessions[session.id] = session
            state.fileObjects[fileObject.id] = fileObject
            state.fileAssets[fileAsset.id] = fileAsset
            if (existingDocument) {
                state.documents[EXISTING_DOCUMENT_ID] = Document(
                    id = EXISTING_DOCUMENT_ID,
                    tenantId = tenantId,
                    assetId = Identifier("existing-asset"),
                    documentNumber = "EXISTING",
                    title = "Existing",
                    versions = listOf(
                        DocumentVersion(
                            Identifier("existing-version"),
                            tenantId,
                            EXISTING_DOCUMENT_ID,
                            "1.0",
                            Identifier("existing-file"),
                        ),
                    ),
                    currentVersionId = Identifier("existing-version"),
                )
            }
            service = rebuildService()
        }

        fun rebuildService(): CompletedResumableUploadAssetClaimService {
            return CompletedResumableUploadAssetClaimService(
                tenantProvider = object : TenantProvider {
                    override fun currentTenant(): TenantContext = TenantContext(tenantId)
                },
                userRealmProvider = object : UserRealmProvider {
                    override fun currentUser(): UserIdentity {
                        identityReads.incrementAndGet()
                        return UserIdentity(Identifier(currentUserId), "Uploader")
                    }
                    override fun findUser(userId: Identifier): UserIdentity? = null
                },
                authorizationProvider = authorization,
                uploadSessions = uploadSessions,
                documents = documents,
                fileObjects = MemoryFileObjectRepository(state),
                fileAssets = MemoryFileAssetRepository(state),
                idempotencyRepository = MemoryIdempotencyRepository(state),
                transaction = transaction,
                identifiers = identifiers,
                clock = CLOCK,
            ).also { service = it }
        }
    }

    private class MemoryState {
        val sessions = LinkedHashMap<Identifier, ResumableUploadSession>()
        val claims = LinkedHashMap<Identifier, CompletedResumableUploadAssetClaim>()
        val fileObjects = LinkedHashMap<Identifier, FileObject>()
        val fileAssets = LinkedHashMap<Identifier, FileAsset>()
        val documents = LinkedHashMap<Identifier, Document>()
        val idempotency = LinkedHashMap<String, RequestIdempotencyRecord>()

        fun snapshot() = Snapshot(
            sessions = LinkedHashMap(sessions),
            claims = LinkedHashMap(claims),
            documents = documents.mapValuesTo(LinkedHashMap()) { (_, value) -> value.copyDocument() },
            idempotency = LinkedHashMap(idempotency),
        )

        fun restore(snapshot: Snapshot) {
            sessions.clear(); sessions.putAll(snapshot.sessions)
            claims.clear(); claims.putAll(snapshot.claims)
            documents.clear(); documents.putAll(snapshot.documents)
            idempotency.clear(); idempotency.putAll(snapshot.idempotency)
        }
    }

    private class Snapshot(
        val sessions: LinkedHashMap<Identifier, ResumableUploadSession>,
        val claims: LinkedHashMap<Identifier, CompletedResumableUploadAssetClaim>,
        val documents: LinkedHashMap<Identifier, Document>,
        val idempotency: LinkedHashMap<String, RequestIdempotencyRecord>,
    )

    private class MemoryTransaction(private val state: MemoryState) : ApplicationTransaction, ApplicationTransactionState {
        private val active = ThreadLocal<Boolean>()
        override fun <T> execute(action: () -> T): T = synchronized(state) {
            if (active.get() == true) return@synchronized action()
            val before = state.snapshot()
            active.set(true)
            try {
                action()
            } catch (failure: Throwable) {
                state.restore(before)
                throw failure
            } finally {
                active.remove()
            }
        }
        override fun isTransactionActive(): Boolean = active.get() == true
    }

    private class MemoryUploadSessions(private val state: MemoryState) : CompletedResumableUploadAssetClaimRepository {
        @Volatile var failAfterMark: Boolean = false
        var readCount: Int = 0

        override fun lockCompletedAssetClaim(
            tenantId: Identifier,
            ownerId: String,
            uploadId: Identifier,
        ): CompletedResumableUploadAssetClaimState? {
            readCount++
            return state.sessions[uploadId]
                ?.takeIf { it.tenantId == tenantId && it.ownerId == ownerId }
                ?.let { CompletedResumableUploadAssetClaimState(it, state.claims[uploadId]) }
        }

        override fun findCompletedAssetClaim(
            tenantId: Identifier,
            ownerId: String,
            uploadId: Identifier,
        ): CompletedResumableUploadAssetClaimState? {
            readCount++
            return state.sessions[uploadId]
                ?.takeIf { it.tenantId == tenantId && it.ownerId == ownerId }
                ?.let { CompletedResumableUploadAssetClaimState(it, state.claims[uploadId]) }
        }

        override fun markCompletedAssetClaimed(
            expected: ResumableUploadSession,
            claim: CompletedResumableUploadAssetClaim,
        ): CompletedResumableUploadAssetClaimState? {
            val current = state.sessions[expected.id] ?: return null
            val completedAt = current.completedAt ?: return null
            if (
                !sameUploadSession(current, expected) ||
                current.status != ResumableUploadSessionStatus.COMPLETED ||
                current.assetType != "DOCUMENT" ||
                current.lastError != null ||
                current.updatedTime != completedAt ||
                current.ownerId != claim.claimedBy ||
                current.tenantId != claim.tenantId ||
                current.fileObjectId != claim.fileObjectId ||
                current.fileAssetId != claim.fileAssetId ||
                claim.claimedTime < completedAt ||
                claim.claimedTime < current.updatedTime ||
                claim.claimedTime >= current.expiresAt
            ) return null
            if (state.claims.putIfAbsent(expected.id, claim) != null) return null
            val marked = current.copyWithUpdatedTime(claim.claimedTime)
            state.sessions[expected.id] = marked
            if (failAfterMark) throw IllegalStateException("simulated failure after completed asset marker write")
            return CompletedResumableUploadAssetClaimState(marked, claim)
        }

        override fun save(session: ResumableUploadSession) { state.sessions[session.id] = session }
        override fun findById(tenantId: Identifier, sessionId: Identifier) = state.sessions[sessionId]?.takeIf { it.tenantId == tenantId }
        override fun findByIdempotencyKey(tenantId: Identifier, idempotencyKey: String) =
            state.sessions.values.singleOrNull { it.tenantId == tenantId && it.idempotencyKey == idempotencyKey }
        override fun findParts(tenantId: Identifier, sessionId: Identifier): List<ResumableUploadPart> = emptyList()
        override fun savePart(part: ResumableUploadPart) = unsupported<Unit>()
        override fun claimForCompletion(tenantId: Identifier, sessionId: Identifier, now: Long): ResumableUploadSession? = unsupported()
        override fun reactivateAfterCompletionFailure(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long): Boolean = unsupported()
        override fun markFailed(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long): Boolean = unsupported()
        override fun markCompleted(tenantId: Identifier, sessionId: Identifier, completedAt: Long): Boolean = unsupported()
        override fun claimForAbort(tenantId: Identifier, sessionId: Identifier, updatedAt: Long): ResumableUploadSession? = unsupported()
        override fun markAborted(tenantId: Identifier, sessionId: Identifier, expired: Boolean, updatedAt: Long): Boolean = unsupported()
        override fun findExpired(now: Long, limit: Int): List<ResumableUploadSession> = emptyList()
        override fun findExpiredCompleting(now: Long, limit: Int): List<ResumableUploadSession> = emptyList()
        override fun findExpiredCompleting(tenantId: Identifier, now: Long, limit: Int): List<ResumableUploadSession> = emptyList()
        private fun <T> unsupported(): T = throw UnsupportedOperationException()
    }

    private class LegacySessionRepository(private val session: ResumableUploadSession) : ResumableUploadSessionRepository {
        override fun save(session: ResumableUploadSession) = Unit
        override fun findById(tenantId: Identifier, sessionId: Identifier) = session
        override fun findByIdempotencyKey(tenantId: Identifier, idempotencyKey: String) = session
        override fun findParts(tenantId: Identifier, sessionId: Identifier): List<ResumableUploadPart> = emptyList()
        override fun savePart(part: ResumableUploadPart) = Unit
        override fun claimForCompletion(tenantId: Identifier, sessionId: Identifier, now: Long): ResumableUploadSession? = null
        override fun reactivateAfterCompletionFailure(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long) = false
        override fun markFailed(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long) = false
        override fun markCompleted(tenantId: Identifier, sessionId: Identifier, completedAt: Long) = false
        override fun claimForAbort(tenantId: Identifier, sessionId: Identifier, updatedAt: Long): ResumableUploadSession? = null
        override fun markAborted(tenantId: Identifier, sessionId: Identifier, expired: Boolean, updatedAt: Long) = false
        override fun findExpired(now: Long, limit: Int): List<ResumableUploadSession> = emptyList()
        override fun findExpiredCompleting(now: Long, limit: Int): List<ResumableUploadSession> = emptyList()
        override fun findExpiredCompleting(tenantId: Identifier, now: Long, limit: Int): List<ResumableUploadSession> = emptyList()
    }

    private class MemoryIdempotencyRepository(private val state: MemoryState) : RequestIdempotencyRepository {
        override fun findByKeyDigest(tenantId: Identifier, keyDigest: String) = state.idempotency[keyDigest]

        override fun claim(request: RequestIdempotency, newRecordId: Identifier, now: Long): RequestIdempotencyClaim {
            state.idempotency[request.keyDigest]?.let { return RequestIdempotencyClaim(it, false) }
            val record = RequestIdempotencyRecord(
                newRecordId, request.tenantId, request.keyDigest, request.operatorId, request.action,
                request.resourceType, request.resourceId, request.subresourceId, request.requestFingerprint,
                RequestIdempotencyStatus.IN_PROGRESS, null, null, now, now,
            )
            state.idempotency[request.keyDigest] = record
            return RequestIdempotencyClaim(record, true)
        }

        override fun complete(
            recordId: Identifier,
            tenantId: Identifier,
            keyDigest: String,
            result: IdempotencyResult,
            completedAt: Long,
        ): RequestIdempotencyRecord {
            val before = state.idempotency.getValue(keyDigest)
            return RequestIdempotencyRecord(
                before.id, before.tenantId, before.keyDigest, before.operatorId, before.action,
                before.resourceType, before.resourceId, before.subresourceId, before.requestFingerprint,
                RequestIdempotencyStatus.COMPLETED, result, completedAt, before.createdTime, completedAt,
            ).also { state.idempotency[keyDigest] = it }
        }
    }

    private class MemoryDocumentRepository(private val state: MemoryState) : DocumentRepository {
        override fun findById(tenantId: Identifier, documentId: Identifier) =
            state.documents[documentId]?.takeIf { it.tenantId == tenantId }?.copyDocument()
        override fun findForMutation(tenantId: Identifier, documentId: Identifier) = findById(tenantId, documentId)
        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String) =
            state.documents.values.singleOrNull { it.tenantId == tenantId && it.documentNumber == documentNumber }?.copyDocument()
        override fun save(document: Document) { state.documents[document.id] = document.copyDocument() }
    }

    private class MemoryFileObjectRepository(private val state: MemoryState) : FileObjectRepository {
        override fun findById(tenantId: Identifier, fileObjectId: Identifier) =
            state.fileObjects[fileObjectId]?.takeIf { it.tenantId == tenantId }
        override fun save(fileObject: FileObject) { state.fileObjects[fileObject.id] = fileObject }
    }

    private class MemoryFileAssetRepository(private val state: MemoryState) : FileAssetRepository {
        override fun findById(tenantId: Identifier, fileAssetId: Identifier) =
            state.fileAssets[fileAssetId]?.takeIf { it.tenantId == tenantId }
        override fun save(fileAsset: FileAsset) { state.fileAssets[fileAsset.id] = fileAsset }
    }

    private class SequenceIdentifiers : IdentifierGenerator {
        private val sequence = AtomicInteger()
        override fun nextId() = Identifier("generated-${sequence.incrementAndGet()}")
    }

    private class RecordingAuthorization : AuthorizationProvider {
        @Volatile var deniedAction: String? = null
        val requests = java.util.Collections.synchronizedList(ArrayList<AuthorizationRequest>())
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            val permitted = request.action.name != deniedAction
            return AuthorizationDecision(permitted, if (permitted) null else "revoked")
        }
    }

    companion object {
        val TENANT_ID = Identifier("tenant-1")
        val UPLOAD_ID = Identifier("upload-1")
        val FILE_ID = Identifier("file-1")
        val ASSET_ID = Identifier("asset-1")
        val EXISTING_DOCUMENT_ID = Identifier("document-existing")
        const val OWNER_ID = "user-1"
        const val NOW = 200L
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)

        fun completedSession(
            ownerId: String = OWNER_ID,
            assetType: String = "DOCUMENT",
            expiresAt: Long = 1_000,
            status: ResumableUploadSessionStatus = ResumableUploadSessionStatus.COMPLETED,
            completedAt: Long? = 100,
        ) = ResumableUploadSession(
            id = UPLOAD_ID,
            tenantId = TENANT_ID,
            idempotencyKey = "upload-key",
            storageUploadId = Identifier("storage-upload-1"),
            storageLocation = StorageObjectLocation("S3", "tenant-1/object"),
            fileObjectId = FILE_ID,
            fileAssetId = ASSET_ID,
            fileName = "contract.pdf",
            contentLength = 7,
            assetType = assetType,
            contentType = "application/pdf",
            expectedContentHash = "sha256:content",
            metadata = mapOf("source" to "resumable"),
            status = status,
            expiresAt = expiresAt,
            completedAt = completedAt,
            createdTime = 10,
            updatedTime = 100,
            ownerId = ownerId,
        )

        fun completedFileObject(session: ResumableUploadSession) = FileObject(
            session.fileObjectId,
            session.tenantId,
            session.fileName,
            session.contentLength,
            session.storageLocation.storageType,
            session.storageLocation.path,
            session.contentType,
            session.expectedContentHash,
        )

        fun completedFileAsset(session: ResumableUploadSession) = FileAsset(
            session.fileAssetId,
            session.tenantId,
            session.fileObjectId,
            session.assetType,
            session.metadata,
        )
    }
}

private fun Document.copyDocument() = Document(
    id = id,
    tenantId = tenantId,
    assetId = assetId,
    documentNumber = documentNumber,
    title = title,
    lifecycleState = lifecycleState,
    versions = versions.map { version ->
        DocumentVersion(version.id, version.tenantId, version.documentId, version.versionNumber, version.fileObjectId)
    },
    currentVersionId = currentVersionId,
    deliveryGeneration = deliveryGeneration,
)

private fun ResumableUploadSession.copyWithUpdatedTime(updatedTime: Long) = ResumableUploadSession(
    id = id,
    tenantId = tenantId,
    idempotencyKey = idempotencyKey,
    storageUploadId = storageUploadId,
    storageLocation = storageLocation,
    fileObjectId = fileObjectId,
    fileAssetId = fileAssetId,
    fileName = fileName,
    contentLength = contentLength,
    assetType = assetType,
    contentType = contentType,
    expectedContentHash = expectedContentHash,
    metadata = metadata,
    status = status,
    expiresAt = expiresAt,
    lastError = lastError,
    completedAt = completedAt,
    createdTime = createdTime,
    updatedTime = updatedTime,
    ownerId = ownerId,
)

private fun sameUploadSession(first: ResumableUploadSession, second: ResumableUploadSession): Boolean =
    first.id == second.id &&
        first.tenantId == second.tenantId &&
        first.ownerId == second.ownerId &&
        first.idempotencyKey == second.idempotencyKey &&
        first.storageUploadId == second.storageUploadId &&
        first.storageLocation == second.storageLocation &&
        first.fileObjectId == second.fileObjectId &&
        first.fileAssetId == second.fileAssetId &&
        first.fileName == second.fileName &&
        first.contentLength == second.contentLength &&
        first.assetType == second.assetType &&
        first.contentType == second.contentType &&
        first.expectedContentHash == second.expectedContentHash &&
        first.metadata == second.metadata &&
        first.status == second.status &&
        first.expiresAt == second.expiresAt &&
        first.lastError == second.lastError &&
        first.completedAt == second.completedAt &&
        first.createdTime == second.createdTime &&
        first.updatedTime == second.updatedTime
