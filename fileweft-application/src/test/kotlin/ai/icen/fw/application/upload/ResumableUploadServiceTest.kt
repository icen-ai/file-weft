package ai.icen.fw.application.upload

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.event.OutboxEvent
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
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResumableUploadServiceTest {
    @Test
    fun `persists resumable parts completes once and returns the same result for idempotent completion`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage()
        val state = State()
        val service = service(storage, state, clock)

        val session = service.start(command())
        val repeatedStart = service.start(command())
        assertEquals(session.id, repeatedStart.id)
        assertEquals(1, storage.beginCalls.get())

        val first = service.uploadPart(session.id, 1, 3, ByteArrayInputStream("abc".toByteArray()))
        val second = service.uploadPart(session.id, 2, 4, ByteArrayInputStream("defg".toByteArray()))
        assertEquals(listOf(first.id, second.id), service.inspect(session.id).parts.map { it.id })

        val completed = service.complete(session.id)
        val repeatedCompletion = service.complete(session.id)

        assertEquals(completed.fileObject.id, repeatedCompletion.fileObject.id)
        assertEquals("file-2", completed.fileObject.id.value)
        assertEquals("asset-3", completed.fileAsset.id.value)
        assertEquals(1, storage.completeCalls.get())
        assertEquals(ResumableUploadSessionStatus.COMPLETED, service.inspect(session.id).session.status)
        assertEquals("file.uploaded", state.events.single().type)
        assertThrows<ResumableUploadStateException> {
            service.uploadPart(session.id, 3, 1, ByteArrayInputStream(byteArrayOf(1)))
        }
    }

    @Test
    fun `returns a failed completion to active when storage has no completed object`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage().apply { failComplete = true }
        val state = State()
        val service = service(storage, state, clock)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        assertThrows<IllegalStateException> { service.complete(session.id) }

        val inspected = service.inspect(session.id)
        assertEquals(ResumableUploadSessionStatus.ACTIVE, inspected.session.status)
        assertTrue(inspected.session.lastError!!.contains("storage completion failed"))
        assertEquals(0, state.events.size)
    }

    @Test
    fun `rejects a completion whose persisted part lengths do not match the declared file length`() {
        val storage = FakeMultipartStorage()
        val service = service(storage, State(), MutableClock(100))
        val session = service.start(command(contentLength = 7))
        service.uploadPart(session.id, 1, 3, ByteArrayInputStream("abc".toByteArray()))

        assertThrows<IllegalArgumentException> { service.complete(session.id) }
        assertEquals(0, storage.completeCalls.get())
        assertEquals(ResumableUploadSessionStatus.ACTIVE, service.inspect(session.id).session.status)
    }

    @Test
    fun `aborts expired sessions and removes their remote multipart state`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage()
        val service = service(storage, State(), clock, ttl = Duration.ofMillis(5))
        val session = service.start(command())
        clock.advance(5)

        val cleanup = service.cleanupExpired()

        assertEquals(1, cleanup.inspected)
        assertEquals(1, cleanup.expired)
        assertEquals(0, cleanup.failed)
        assertEquals(ResumableUploadSessionStatus.EXPIRED, service.inspect(session.id).session.status)
        assertEquals(listOf(session.storageUploadId), storage.aborted)
        assertTrue(storage.deleted.contains(session.storageLocation))
    }

    @Test
    fun `does not delete a session whose multipart completion is already in flight`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage()
        val state = State()
        val service = service(storage, state, clock)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))
        assertEquals(
            ResumableUploadSessionStatus.COMPLETING,
            state.sessions.claimForCompletion(session.tenantId, session.id, clock.millis())?.status,
        )

        assertThrows<ResumableUploadStateException> { service.abort(session.id) }

        assertEquals(ResumableUploadSessionStatus.COMPLETING, service.inspect(session.id).session.status)
        assertTrue(storage.aborted.isEmpty())
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun `reports expired completion work for manual maintenance without deleting its object`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage()
        val state = State()
        val service = service(storage, state, clock, ttl = Duration.ofMillis(5))
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))
        state.sessions.claimForCompletion(session.tenantId, session.id, clock.millis())
        state.tenant = Identifier("tenant-2")
        val otherTenantSession = service.start(command())
        service.uploadPart(otherTenantSession.id, 1, 7, ByteArrayInputStream("content".toByteArray()))
        state.sessions.claimForCompletion(otherTenantSession.tenantId, otherTenantSession.id, clock.millis())
        state.tenant = Identifier("tenant-1")
        clock.advance(5)

        val stalled = service.inspectStalledCompletionsAsSystem()
        val tenantStalled = service.inspectStalledCompletions()
        val cleanup = service.cleanupExpired()

        assertEquals(setOf(session.id, otherTenantSession.id), stalled.map { it.id }.toSet())
        assertEquals(setOf(session.tenantId, otherTenantSession.tenantId), stalled.map { it.tenantId }.toSet())
        assertEquals(listOf(session.id), tenantStalled.map { it.id })
        assertEquals(0, cleanup.inspected)
        assertTrue(storage.aborted.isEmpty())
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun `does not allow a tenant scoped session lookup to cross tenants`() {
        val state = State()
        val service = service(FakeMultipartStorage(), state, MutableClock(100))
        val session = service.start(command())
        state.tenant = Identifier("tenant-2")

        assertThrows<ResumableUploadNotFoundException> { service.inspect(session.id) }
    }

    private fun service(
        storage: FakeMultipartStorage,
        state: State,
        clock: Clock,
        ttl: Duration = Duration.ofHours(1),
    ) = ResumableUploadService(
        tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(state.tenant) },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser() = UserIdentity(Identifier("user-1"))
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest) = AuthorizationDecision(true)
        },
        storageAdapter = storage,
        sessions = state.sessions,
        fileObjects = state.fileObjects,
        fileAssets = state.fileAssets,
        outbox = state,
        identifiers = object : IdentifierGenerator {
            override fun nextId(): Identifier {
                val kind = when (state.ids.get()) {
                    0 -> "session"
                    1 -> "file"
                    2 -> "asset"
                    in 3..5 -> "part"
                    else -> "event"
                }
                return Identifier("$kind-${state.ids.incrementAndGet()}")
            }
        },
        transaction = DirectTransaction,
        clock = clock,
        sessionTtl = ttl,
    )

    private fun command(contentLength: Long = 7) = StartResumableUploadCommand(
        fileName = "contract.pdf",
        contentLength = contentLength,
        assetType = "DOCUMENT",
        idempotencyKey = "client-upload-1",
        contentType = "application/pdf",
    )

    private class State : OutboxEventRepository {
        var tenant = Identifier("tenant-1")
        val ids = AtomicInteger(0)
        val sessions = InMemorySessions()
        val fileObjects = InMemoryFileObjects()
        val fileAssets = InMemoryFileAssets()
        val events = mutableListOf<OutboxEvent>()

        override fun append(event: OutboxEvent) {
            events += event
        }
    }

    private class InMemorySessions : ResumableUploadSessionRepository {
        private val sessions = linkedMapOf<Pair<String, String>, ResumableUploadSession>()
        private val parts = linkedMapOf<Pair<String, String>, MutableList<ResumableUploadPart>>()

        override fun save(session: ResumableUploadSession) {
            sessions[session.tenantId.value to session.id.value] = session
        }

        override fun findById(tenantId: Identifier, sessionId: Identifier): ResumableUploadSession? =
            sessions[tenantId.value to sessionId.value]

        override fun findByIdempotencyKey(tenantId: Identifier, idempotencyKey: String): ResumableUploadSession? =
            sessions.values.firstOrNull { it.tenantId == tenantId && it.idempotencyKey == idempotencyKey }

        override fun findParts(tenantId: Identifier, sessionId: Identifier): List<ResumableUploadPart> =
            parts[tenantId.value to sessionId.value]?.sortedBy { it.partNumber } ?: emptyList()

        override fun savePart(part: ResumableUploadPart) {
            val values = parts.getOrPut(part.tenantId.value to part.sessionId.value) { mutableListOf() }
            val index = values.indexOfFirst { it.partNumber == part.partNumber }
            if (index < 0) values += part else values[index] = part
        }

        override fun claimForCompletion(tenantId: Identifier, sessionId: Identifier, now: Long): ResumableUploadSession? {
            val current = findById(tenantId, sessionId) ?: return null
            if (current.expiresAt <= now) return null
            return transition(tenantId, sessionId, now, setOf(ResumableUploadSessionStatus.ACTIVE), ResumableUploadSessionStatus.COMPLETING)
        }

        override fun reactivateAfterCompletionFailure(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long): Boolean =
            transition(tenantId, sessionId, updatedAt, setOf(ResumableUploadSessionStatus.COMPLETING), ResumableUploadSessionStatus.ACTIVE, message) != null

        override fun markFailed(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long): Boolean =
            transition(
                tenantId, sessionId, updatedAt,
                setOf(ResumableUploadSessionStatus.COMPLETING, ResumableUploadSessionStatus.ABORTING),
                ResumableUploadSessionStatus.FAILED,
                message,
            ) != null

        override fun markCompleted(tenantId: Identifier, sessionId: Identifier, completedAt: Long): Boolean =
            transition(
                tenantId, sessionId, completedAt, setOf(ResumableUploadSessionStatus.COMPLETING),
                ResumableUploadSessionStatus.COMPLETED,
                completedAt = completedAt,
            ) != null

        override fun claimForAbort(tenantId: Identifier, sessionId: Identifier, updatedAt: Long): ResumableUploadSession? =
            transition(
                tenantId, sessionId, updatedAt,
                setOf(ResumableUploadSessionStatus.ACTIVE, ResumableUploadSessionStatus.ABORTING, ResumableUploadSessionStatus.FAILED),
                ResumableUploadSessionStatus.ABORTING,
            )

        override fun markAborted(tenantId: Identifier, sessionId: Identifier, expired: Boolean, updatedAt: Long): Boolean =
            transition(
                tenantId, sessionId, updatedAt, setOf(ResumableUploadSessionStatus.ABORTING),
                if (expired) ResumableUploadSessionStatus.EXPIRED else ResumableUploadSessionStatus.ABORTED,
            ) != null

        override fun findExpired(now: Long, limit: Int): List<ResumableUploadSession> = sessions.values
            .filter { it.expiresAt <= now && it.status in setOf(ResumableUploadSessionStatus.ACTIVE, ResumableUploadSessionStatus.ABORTING, ResumableUploadSessionStatus.FAILED) }
            .take(limit)

        override fun findExpiredCompleting(now: Long, limit: Int): List<ResumableUploadSession> = sessions.values
            .filter { it.expiresAt <= now && it.status == ResumableUploadSessionStatus.COMPLETING }
            .take(limit)

        override fun findExpiredCompleting(tenantId: Identifier, now: Long, limit: Int): List<ResumableUploadSession> = sessions.values
            .filter { it.tenantId == tenantId && it.expiresAt <= now && it.status == ResumableUploadSessionStatus.COMPLETING }
            .take(limit)

        private fun transition(
            tenantId: Identifier,
            sessionId: Identifier,
            updatedAt: Long,
            expected: Set<ResumableUploadSessionStatus>,
            status: ResumableUploadSessionStatus,
            error: String? = null,
            completedAt: Long? = null,
        ): ResumableUploadSession? {
            val current = findById(tenantId, sessionId) ?: return null
            if (current.status !in expected) return null
            return copy(current, status, updatedAt, error, completedAt).also(::save)
        }

        private fun copy(
            value: ResumableUploadSession,
            status: ResumableUploadSessionStatus,
            updatedAt: Long,
            error: String? = null,
            completedAt: Long? = null,
        ) = ResumableUploadSession(
            value.id, value.tenantId, value.idempotencyKey, value.storageUploadId, value.storageLocation,
            value.fileObjectId, value.fileAssetId, value.fileName, value.contentLength, value.assetType,
            value.contentType, value.expectedContentHash, value.metadata, status, value.expiresAt, error,
            completedAt, value.createdTime, updatedAt,
        )
    }

    private class InMemoryFileObjects : FileObjectRepository {
        private val values = linkedMapOf<Pair<String, String>, FileObject>()
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? = values[tenantId.value to fileObjectId.value]
        override fun save(fileObject: FileObject) { values[fileObject.tenantId.value to fileObject.id.value] = fileObject }
    }

    private class InMemoryFileAssets : FileAssetRepository {
        private val values = linkedMapOf<Pair<String, String>, FileAsset>()
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? = values[tenantId.value to fileAssetId.value]
        override fun save(fileAsset: FileAsset) { values[fileAsset.tenantId.value to fileAsset.id.value] = fileAsset }
    }

    private class FakeMultipartStorage : StorageAdapter {
        private val uploadSequence = AtomicInteger()
        private val uploads = linkedMapOf<String, UploadState>()
        private val objects = linkedMapOf<StorageObjectLocation, StoredObject>()
        val beginCalls = AtomicInteger()
        val completeCalls = AtomicInteger()
        val aborted = mutableListOf<Identifier>()
        val deleted = mutableListOf<StorageObjectLocation>()
        var failComplete = false

        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload {
            beginCalls.incrementAndGet()
            val uploadId = Identifier("storage-${uploadSequence.incrementAndGet()}")
            val location = StorageObjectLocation("test", "uploads/${uploadId.value}")
            uploads[uploadId.value] = UploadState(location, request.contentType)
            return MultipartUpload(uploadId, location)
        }

        override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart {
            val bytes = content.readBytes()
            require(bytes.size.toLong() == contentLength)
            uploads.getValue(upload.uploadId.value).parts[partNumber] = bytes
            return MultipartPart(partNumber, "etag-$partNumber-${bytes.size}")
        }

        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject {
            completeCalls.incrementAndGet()
            if (failComplete) throw IllegalStateException("storage completion failed")
            val state = uploads.getValue(upload.uploadId.value)
            val bytes = parts.sortedBy { it.partNumber }.flatMap { state.parts.getValue(it.partNumber).asIterable() }.toByteArray()
            return StoredObject(upload.location, bytes.size.toLong(), state.contentType, "sha256:test-${bytes.size}").also {
                objects[upload.location] = it
                uploads.remove(upload.uploadId.value)
            }
        }

        override fun abortMultipartUpload(upload: MultipartUpload) {
            aborted += upload.uploadId
            uploads.remove(upload.uploadId.value)
        }

        override fun delete(location: StorageObjectLocation) {
            deleted += location
            objects.remove(location)
        }

        override fun exists(location: StorageObjectLocation): Boolean = location in objects

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject = error("Not used by resumable tests")
        override fun download(location: StorageObjectLocation): StorageDownload = error("Not used by resumable tests")
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = URI.create("https://storage.test/$location")

        private class UploadState(val location: StorageObjectLocation, val contentType: String?) {
            val parts = linkedMapOf<Int, ByteArray>()
        }
    }

    private class MutableClock(private var current: Long) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(current)
        fun advance(millis: Long) { current += millis }
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
