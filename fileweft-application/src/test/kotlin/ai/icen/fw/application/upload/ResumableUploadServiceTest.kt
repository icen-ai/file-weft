package ai.icen.fw.application.upload

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionNestingException
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.application.transaction.ApplicationTransactionState
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
import ai.icen.fw.spi.storage.MultipartCompletionRejectedException
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResumableUploadServiceTest {
    @Test
    fun `rejects every storage-backed command inside an active transaction before side effects`() {
        val state = State()
        val storage = FakeMultipartStorage()
        val service = service(storage, state, MutableClock(100), transaction = ActiveTransaction)
        val sessionId = Identifier("session-never-read")
        val commands = listOf<() -> Unit>(
            { service.start(command()) },
            { service.uploadPart(sessionId, 1, 1, ByteArrayInputStream(byteArrayOf(1))) },
            { service.complete(sessionId) },
            { service.abort(sessionId) },
            { service.cleanupExpired() },
        )

        commands.forEach { command ->
            val failure = assertThrows<ApplicationTransactionNestingException> { command() }
            assertEquals(ApplicationTransactionNestingException.DEFAULT_MESSAGE, failure.message)
        }
        assertEquals(0, state.currentUserCalls.get())
        assertEquals(0, state.authorizationCalls.get())
        assertEquals(0, state.ids.get())
        assertEquals(0, state.sessions.queryCalls.get())
        assertEquals(0, state.sessions.mutationCalls.get())
        assertEquals(0, storage.operationCalls())
        assertTrue(state.events.isEmpty())
    }

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
    fun `start and inspect uses one trusted identity snapshot and returns replayed acknowledgements`() {
        val storage = FakeMultipartStorage()
        val state = State()
        val service = service(storage, state, MutableClock(100))
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))
        state.currentUserCalls.set(0)

        val replayed = service.startAndInspect(command())

        assertEquals(session.id, replayed.session.id)
        assertEquals(listOf(1), replayed.parts.map { it.partNumber })
        assertEquals(1, state.currentUserCalls.get())
        assertEquals(1, storage.beginCalls.get())
    }

    @Test
    fun `reusing an owned idempotency key with a different command is a state conflict`() {
        val storage = FakeMultipartStorage()
        val state = State()
        val service = service(storage, state, MutableClock(100))
        service.start(command())

        assertThrows<ResumableUploadStateException> {
            service.start(command(contentHash = "sha256:different"))
        }

        assertEquals(1, storage.beginCalls.get())
    }

    @Test
    fun `formal start hashes a validated caller key with one trusted tenant snapshot before application work`() {
        val storage = FakeMultipartStorage()
        val state = State()
        val service = service(storage, state, MutableClock(100))
        val callerKey = "browser-retry-key"

        val first = service.startAndInspectWithCallerKey(command(idempotencyKey = callerKey))
        val firstStoredKey = first.session.idempotencyKey
        state.tenant = Identifier("tenant-2")
        val second = service.startAndInspectWithCallerKey(command(idempotencyKey = callerKey))

        assertTrue(firstStoredKey.startsWith("v1:sha256:"))
        assertEquals(74, firstStoredKey.length)
        assertTrue(!firstStoredKey.contains(callerKey))
        assertNotEquals(firstStoredKey, second.session.idempotencyKey)
        assertNotEquals(first.session.id, second.session.id)
        assertEquals(2, storage.beginCalls.get())

        val operationsBeforeInvalidKey = storage.operationCalls()
        assertThrows<IllegalArgumentException> {
            service.startAndInspectWithCallerKey(command(idempotencyKey = "secret key"))
        }
        assertEquals(operationsBeforeInvalidKey, storage.operationCalls())
    }

    @Test
    fun `formal start fails before storage when checkpoint reset capability is unavailable`() {
        val storage = FakeMultipartStorage()
        val state = State()
        val service = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = FormalRepositoryWithoutCompletionReset(state.sessions),
        )

        assertThrows<ResumableUploadUnavailableException> {
            service.startAndInspectWithCallerKey(command(idempotencyKey = "formal-reset-required"))
        }

        assertEquals(1, state.currentUserCalls.get())
        assertEquals(0, state.sessions.queryCalls.get())
        assertEquals(0, state.sessions.mutationCalls.get())
        assertEquals(0, storage.operationCalls())
        assertEquals(0, state.ids.get())
    }

    @Test
    fun `formal start classifies missing staging capability as unavailable before application work`() {
        val storage = FakeMultipartStorage()
        val state = State()
        val service = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = FormalRepositoryWithoutStaging(state.sessions),
        )

        assertThrows<ResumableUploadUnavailableException> {
            service.startAndInspectWithCallerKey(command(idempotencyKey = "formal-staging-required"))
        }

        assertEquals(1, state.currentUserCalls.get())
        assertEquals(0, state.authorizationCalls.get())
        assertEquals(0, state.sessions.queryCalls.get())
        assertEquals(0, state.sessions.mutationCalls.get())
        assertEquals(0, storage.operationCalls())
        assertEquals(0, state.ids.get())
    }

    @Test
    fun `formal start rejects an unroutable generated session id before authorization storage or persistence mutations`() {
        val storage = FakeMultipartStorage()
        val state = State()
        val identifiers = object : IdentifierGenerator {
            override fun nextId(): Identifier {
                state.ids.incrementAndGet()
                return Identifier("unsafe/id")
            }
        }
        val service = service(storage, state, MutableClock(100), identifierGenerator = identifiers)

        val failure = assertThrows<IllegalStateException> {
            service.startAndInspectWithCallerKey(command(idempotencyKey = "formal-route-safe"))
        }

        assertFalse(failure.message.orEmpty().contains("unsafe/id"))
        assertEquals(1, state.ids.get())
        assertEquals(0, state.authorizationCalls.get())
        assertEquals(0, storage.operationCalls())
        assertEquals(0, state.sessions.mutationCalls.get())
        assertEquals(0, state.businessMutationCalls())
    }

    @Test
    fun `formal replay rejects an unroutable legacy session without mutating it`() {
        val callerKey = "formal-legacy-route"
        val probeState = State()
        val internalSession = service(FakeMultipartStorage(), probeState, MutableClock(100))
            .startAndInspectWithCallerKey(command(idempotencyKey = callerKey))
            .session
        val storage = FakeMultipartStorage()
        val state = State()
        state.sessions.save(copySessionForTest(internalSession, id = Identifier("unsafe/id")))
        state.sessions.mutationCalls.set(0)
        val service = service(storage, state, MutableClock(100))

        val failure = assertThrows<IllegalStateException> {
            service.startAndInspectWithCallerKey(command(idempotencyKey = callerKey))
        }

        assertFalse(failure.message.orEmpty().contains("unsafe/id"))
        assertEquals(0, state.ids.get())
        assertEquals(0, state.authorizationCalls.get())
        assertEquals(0, storage.operationCalls())
        assertEquals(0, state.sessions.mutationCalls.get())
        assertEquals(ResumableUploadSessionStatus.ACTIVE, state.sessions.findById(state.tenant, Identifier("unsafe/id"))?.status)
    }

    @Test
    fun `formal completion fails before claiming a legacy active session when checkpoint reset is unavailable`() {
        val storage = FakeMultipartStorage()
        val state = State()
        val service = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = FormalRepositoryWithoutCompletionReset(state.sessions),
        )
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        assertThrows<ResumableUploadUnavailableException> {
            service.completeAndInspect(session.id)
        }

        val checkpoint = service.inspect(session.id)
        assertEquals(ResumableUploadSessionStatus.ACTIVE, checkpoint.session.status)
        assertEquals(listOf(1), checkpoint.parts.map { part -> part.partNumber })
        assertEquals(0, storage.completeCalls.get())
    }

    @Test
    fun `complete and inspect uses one trusted identity snapshot for the completion receipt`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage()
        val state = State()
        val service = service(storage, state, clock)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))
        state.currentUserCalls.set(0)

        val completion = service.completeAndInspect(session.id)

        assertEquals(session.fileObjectId, completion.result.fileObject.id)
        assertEquals(session.fileAssetId, completion.result.fileAsset.id)
        assertEquals(100L, completion.completedAt)
        assertEquals(1, state.currentUserCalls.get())
    }

    @Test
    fun `abort and inspect uses one trusted identity snapshot and preserves the final checkpoint`() {
        val storage = FakeMultipartStorage()
        val state = State()
        val service = service(storage, state, MutableClock(100))
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))
        state.currentUserCalls.set(0)

        val aborted = service.abortAndInspect(session.id)

        assertEquals(ResumableUploadSessionStatus.ABORTED, aborted.session.status)
        assertEquals(listOf(1), aborted.parts.map { part -> part.partNumber })
        assertEquals(1, state.currentUserCalls.get())
    }

    @Test
    fun `rejects a truncated or oversized part body before persisting its acknowledgement`() {
        val storage = FakeMultipartStorage().apply { trustDeclaredPartLength = true }
        val state = State()
        val service = service(storage, state, MutableClock(100))
        val session = service.start(command())

        assertThrows<IllegalArgumentException> {
            service.uploadPart(session.id, 1, 3, ByteArrayInputStream("four".toByteArray()))
        }
        assertTrue(service.inspect(session.id).parts.isEmpty())

        assertThrows<IllegalArgumentException> {
            service.uploadPart(session.id, 1, 8, ByteArrayInputStream("short".toByteArray()))
        }
        assertTrue(service.inspect(session.id).parts.isEmpty())
    }

    @Test
    fun `completion rejects a gapped part sequence even when declared lengths add up`() {
        val storage = FakeMultipartStorage()
        val state = State()
        val service = service(storage, state, MutableClock(100))
        val session = service.start(command())
        service.uploadPart(session.id, 2, 7, ByteArrayInputStream("content".toByteArray()))

        assertThrows<ResumableUploadStateException> { service.complete(session.id) }

        assertEquals(0, storage.completeCalls.get())
        assertEquals(ResumableUploadSessionStatus.ACTIVE, service.inspect(session.id).session.status)
    }

    @Test
    fun `commit acknowledgement lost after durable completion reconciles and returns the committed result`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage()
        val state = State()
        val transaction = CommitAcknowledgementLostTransaction()
        val service = service(storage, state, clock, transaction = transaction)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        val completed = service.complete(session.id)

        assertEquals(session.fileObjectId, completed.fileObject.id)
        assertEquals(session.fileAssetId, completed.fileAsset.id)
        assertEquals(ResumableUploadSessionStatus.COMPLETED, state.sessions.findById(session.tenantId, session.id)?.status)
        assertEquals(session.storageLocation.path, completed.fileObject.storagePath)
        assertEquals(0, storage.deleteCalls.get())
        assertEquals(1, state.events.size)
        assertSame(transaction.outcomeUnknown, transaction.observedFailure)
    }

    @Test
    fun `unknown uncommitted completion preserves the final object and completing session`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage()
        val state = State()
        val outcomeUnknown = ApplicationTransactionOutcomeUnknownException(
            IllegalStateException("simulated commit acknowledgement failure"),
        )
        val failingObjects = CompletionFailingFileObjectRepository(state.fileObjects, outcomeUnknown)
        val service = service(storage, state, clock, fileObjectRepository = failingObjects)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> { service.complete(session.id) }

        assertSame(outcomeUnknown, failure)
        assertEquals(ResumableUploadSessionStatus.COMPLETING, state.sessions.findById(session.tenantId, session.id)?.status)
        assertEquals(0, storage.deleteCalls.get())
        assertTrue(storage.exists(session.storageLocation))
        assertEquals(null, state.fileObjects.findById(session.tenantId, session.fileObjectId))
        assertEquals(null, state.fileAssets.findById(session.tenantId, session.fileAssetId))
    }

    @Test
    fun `completion reconciliation read failure never deletes or marks failed`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage()
        val state = State()
        val failingObjects = CompletionFailingFileObjectRepository(
            delegate = state.fileObjects,
            saveFailure = IllegalStateException("simulated known transaction rejection"),
            failReconciliationReads = true,
        )
        val service = service(storage, state, clock, fileObjectRepository = failingObjects)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> { service.complete(session.id) }

        assertSame(failingObjects.saveFailure, failure.cause)
        assertTrue(failure.suppressed.contains(failingObjects.readFailure))
        assertEquals(ResumableUploadSessionStatus.COMPLETING, state.sessions.findById(session.tenantId, session.id)?.status)
        assertEquals(0, storage.deleteCalls.get())
        assertTrue(storage.exists(session.storageLocation))
    }

    @Test
    fun `known uncommitted completion without references deletes final object and marks session failed`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage()
        val state = State()
        val failingObjects = CompletionFailingFileObjectRepository(
            state.fileObjects,
            IllegalStateException("simulated pre-commit rejection"),
        )
        val service = service(storage, state, clock, fileObjectRepository = failingObjects)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        val failure = assertThrows<IllegalStateException> { service.complete(session.id) }

        assertSame(failingObjects.saveFailure, failure)
        assertEquals(ResumableUploadSessionStatus.FAILED, state.sessions.findById(session.tenantId, session.id)?.status)
        assertEquals(1, storage.deleteCalls.get())
        assertTrue(!storage.exists(session.storageLocation))
        assertEquals(null, state.fileObjects.findById(session.tenantId, session.fileObjectId))
        assertEquals(null, state.fileAssets.findById(session.tenantId, session.fileAssetId))
        assertEquals(0, state.events.size)
    }

    @Test
    fun `completed location returned outside the claimed upload is never deleted or trusted`() {
        val clock = MutableClock(100)
        val untrustedLocation = StorageObjectLocation("test", "uploads/unrelated-existing-object")
        val storage = FakeMultipartStorage().apply { completedLocationOverride = untrustedLocation }
        val state = State()
        val service = service(storage, state, clock)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> {
            service.complete(session.id)
        }

        assertTrue(failure.suppressed.any { it.message?.contains("outside the requested upload location") == true })
        assertEquals(ResumableUploadSessionStatus.COMPLETING, state.sessions.findById(session.tenantId, session.id)?.status)
        assertEquals(0, storage.deleteCalls.get())
        assertTrue(storage.deleted.isEmpty())
        assertTrue(storage.exists(untrustedLocation))
        assertEquals(null, state.fileObjects.findById(session.tenantId, session.fileObjectId))
        assertEquals(null, state.fileAssets.findById(session.tenantId, session.fileAssetId))
    }

    @Test
    fun `same location length or hash mismatch compensates only the session location`() {
        listOf(
            FakeMultipartStorage().apply { completedLengthOverride = 8L } to command(),
            FakeMultipartStorage().apply { completedHashOverride = "sha256:wrong" } to
                command(contentHash = "sha256:expected"),
        ).forEach { (storage, uploadCommand) ->
            val state = State()
            val service = service(storage, state, MutableClock(100))
            val session = service.start(uploadCommand)
            service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

            assertThrows<IllegalArgumentException> { service.complete(session.id) }

            assertEquals(listOf(session.storageLocation), storage.deleted)
            assertEquals(ResumableUploadSessionStatus.FAILED, state.sessions.findById(session.tenantId, session.id)?.status)
        }
    }

    @Test
    fun `partial durable completion reference is preserved for manual reconciliation`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage()
        val state = State()
        val failingAssets = CompletionFailingFileAssetRepository(
            state.fileAssets,
            IllegalStateException("simulated asset persistence failure"),
        )
        val service = service(storage, state, clock, fileAssetRepository = failingAssets)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> { service.complete(session.id) }

        assertSame(failingAssets.saveFailure, failure.cause)
        assertEquals(ResumableUploadSessionStatus.COMPLETING, state.sessions.findById(session.tenantId, session.id)?.status)
        assertEquals(session.fileObjectId, state.fileObjects.findById(session.tenantId, session.fileObjectId)?.id)
        assertEquals(null, state.fileAssets.findById(session.tenantId, session.fileAssetId))
        assertEquals(0, storage.deleteCalls.get())
        assertTrue(storage.exists(session.storageLocation))
    }

    @Test
    fun `inconsistent durable completion reference is preserved for manual reconciliation`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage()
        val state = State()
        val failingObjects = CompletionFailingFileObjectRepository(
            delegate = state.fileObjects,
            saveFailure = IllegalStateException("simulated inconsistent persistence failure"),
            persistBeforeFailure = true,
            persistInconsistentObject = true,
        )
        val service = service(storage, state, clock, fileObjectRepository = failingObjects)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> { service.complete(session.id) }

        assertSame(failingObjects.saveFailure, failure.cause)
        assertEquals(ResumableUploadSessionStatus.COMPLETING, state.sessions.findById(session.tenantId, session.id)?.status)
        assertNotEquals(session.storageLocation.path, state.fileObjects.findById(session.tenantId, session.fileObjectId)?.storagePath)
        assertEquals(0, storage.deleteCalls.get())
        assertTrue(storage.exists(session.storageLocation))
    }

    @Test
    fun `storage completion failure remains fenced when the final object is not yet visible`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage().apply { failComplete = true }
        val state = State()
        val service = service(storage, state, clock)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        assertThrows<ApplicationTransactionOutcomeUnknownException> { service.complete(session.id) }

        val inspected = service.inspect(session.id)
        assertEquals(ResumableUploadSessionStatus.COMPLETING, inspected.session.status)
        assertEquals(0, state.events.size)
    }

    @Test
    fun `definitive storage rejection clears stale acknowledgements and reopens the upload`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage().apply {
            rejectComplete = true
            rejectCompleteMessage = "r".repeat(4_096)
        }
        val state = State()
        val service = service(storage, state, clock)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        val rejection = assertThrows<ResumableUploadStateException> { service.complete(session.id) }

        assertTrue(rejection.cause is MultipartCompletionRejectedException)
        val reopened = service.inspect(session.id)
        assertEquals(ResumableUploadSessionStatus.ACTIVE, reopened.session.status)
        assertEquals(2_048, reopened.session.lastError?.length)
        assertTrue(reopened.parts.isEmpty())
        assertEquals(0, state.events.size)

        storage.rejectComplete = false
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))
        val completed = service.complete(session.id)

        assertEquals(session.fileObjectId, completed.fileObject.id)
        assertEquals(2, storage.uploadPartCalls.get())
        assertEquals(2, storage.completeCalls.get())
    }

    @Test
    fun `definitive rejection renews a full retry window when completion crosses the original expiry`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage().apply {
            rejectComplete = true
            beforeComplete = { clock.advance(5) }
        }
        val state = State()
        val service = service(storage, state, clock, ttl = Duration.ofMillis(5))
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        assertThrows<ResumableUploadStateException> { service.complete(session.id) }

        val reopened = service.inspect(session.id)
        assertEquals(ResumableUploadSessionStatus.ACTIVE, reopened.session.status)
        assertEquals(110, reopened.session.expiresAt)
        assertTrue(reopened.parts.isEmpty())

        storage.beforeComplete = null
        storage.rejectComplete = false
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))
        assertEquals(session.fileObjectId, service.complete(session.id).fileObject.id)
    }

    @Test
    fun `completion acknowledgement loss is non destructive and a stale retry reconciles the final object`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage().apply { completeThenFail = true }
        val state = State()
        val service = service(storage, state, clock, ttl = Duration.ofMillis(5))
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        val initialFailure = assertThrows<ApplicationTransactionOutcomeUnknownException> { service.complete(session.id) }
        assertEquals("storage completion acknowledgement lost", initialFailure.cause?.message)
        assertEquals(
            ResumableUploadSessionStatus.COMPLETING,
            state.sessions.findById(session.tenantId, session.id)?.status,
        )
        assertTrue(storage.exists(session.storageLocation))

        clock.advance(5)
        val cleanup = service.cleanupExpired()
        val stalled = service.inspectStalledCompletionsAsSystem()

        assertEquals(0, cleanup.inspected)
        assertEquals(listOf(session.id), stalled.map { it.id })
        assertEquals(0, storage.abortCalls.get())
        assertEquals(0, storage.deleteCalls.get())
        assertTrue(storage.exists(session.storageLocation))

        assertThrows<ResumableUploadStateException> { service.complete(session.id) }
        clock.advance(30_000)
        val recovered = service.complete(session.id)

        assertEquals(session.fileObjectId, recovered.fileObject.id)
        assertEquals(session.fileAssetId, recovered.fileAsset.id)
        assertEquals(ResumableUploadSessionStatus.COMPLETED, service.inspect(session.id).session.status)
        assertEquals(1, storage.completeCalls.get())
        assertEquals(0, storage.deleteCalls.get())
    }

    @Test
    fun `stale completion without a visible final object remains fenced from a slow original caller`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage().apply {
            failComplete = true
            failExists = true
        }
        val state = State()
        val service = service(storage, state, clock)
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        assertThrows<ApplicationTransactionOutcomeUnknownException> { service.complete(session.id) }
        assertEquals(ResumableUploadSessionStatus.COMPLETING, state.sessions.findById(session.tenantId, session.id)?.status)

        storage.failExists = false
        clock.advance(30_000)
        assertThrows<ApplicationTransactionOutcomeUnknownException> { service.complete(session.id) }
        assertEquals(ResumableUploadSessionStatus.COMPLETING, service.inspect(session.id).session.status)
        assertEquals(1, storage.completeCalls.get())
        assertEquals(0, storage.deleteCalls.get())
    }

    @Test
    fun `completion existence check failure keeps the session completing for reconciliation`() {
        val clock = MutableClock(100)
        val storage = FakeMultipartStorage().apply {
            failComplete = true
            failExists = true
        }
        val state = State()
        val service = service(storage, state, clock, ttl = Duration.ofMillis(5))
        val session = service.start(command())
        service.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> { service.complete(session.id) }

        assertTrue(failure.suppressed.contains(storage.existsFailure))
        assertEquals(
            ResumableUploadSessionStatus.COMPLETING,
            state.sessions.findById(session.tenantId, session.id)?.status,
        )
        clock.advance(5)
        assertEquals(0, service.cleanupExpired().inspected)
        assertEquals(listOf(session.id), service.inspectStalledCompletionsAsSystem().map { it.id })
        assertEquals(0, storage.abortCalls.get())
        assertEquals(0, storage.deleteCalls.get())
    }

    @Test
    fun `rejects a completion whose persisted part lengths do not match the declared file length`() {
        val storage = FakeMultipartStorage()
        val service = service(storage, State(), MutableClock(100))
        val session = service.start(command(contentLength = 7))
        service.uploadPart(session.id, 1, 3, ByteArrayInputStream("abc".toByteArray()))

        assertThrows<ResumableUploadStateException> { service.complete(session.id) }
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

    @Test
    fun `captures one trusted user snapshot and persists its opaque id as owner`() {
        val state = State().apply {
            currentUser = UserIdentity(Identifier("directory/user:Alpha"), attributes = linkedMapOf("role" to "editor"))
        }
        val storage = FakeMultipartStorage()
        val session = service(storage, state, MutableClock(100)).start(command())

        assertEquals("directory/user:Alpha", session.ownerId)
        assertEquals(1, state.currentUserCalls.get())
        assertEquals(1, state.authorizationCalls.get())
        assertEquals("directory/user:Alpha", state.sessions.findById(state.tenant, session.id)?.ownerId)
    }

    @Test
    fun `same tenant non owner receives not found with zero authorization storage or mutation and owner can continue`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val service = service(storage, state, MutableClock(100))
        val session = service.start(command())
        service.uploadPart(session.id, 1, 3, ByteArrayInputStream("abc".toByteArray()))
        val authorizationBefore = state.authorizationCalls.get()
        val storageBefore = storage.operationCalls()
        val mutationsBefore = state.sessions.mutationCalls.get()
        val businessMutationsBefore = state.businessMutationCalls()

        state.currentUser = UserIdentity(Identifier("owner-B"))
        val failures = listOf(
            assertThrows<ResumableUploadNotFoundException> { service.inspect(session.id) },
            assertThrows<ResumableUploadNotFoundException> {
                service.uploadPart(session.id, 2, 4, ByteArrayInputStream("defg".toByteArray()))
            },
            assertThrows<ResumableUploadNotFoundException> { service.complete(session.id) },
            assertThrows<ResumableUploadNotFoundException> { service.abort(session.id) },
        )

        assertEquals(1, failures.map { it.message }.distinct().size)
        val missingState = State().apply { currentUser = UserIdentity(Identifier("owner-B")) }
        val missing = assertThrows<ResumableUploadNotFoundException> {
            service(FakeMultipartStorage(), missingState, MutableClock(100)).inspect(session.id)
        }
        assertEquals(missing.message, failures.first().message)
        assertEquals(authorizationBefore, state.authorizationCalls.get())
        assertEquals(storageBefore, storage.operationCalls())
        assertEquals(mutationsBefore, state.sessions.mutationCalls.get())
        assertEquals(businessMutationsBefore, state.businessMutationCalls())

        state.currentUser = UserIdentity(Identifier("owner-A"))
        service.uploadPart(session.id, 2, 4, ByteArrayInputStream("defg".toByteArray()))
        service.complete(session.id)
        assertEquals("owner-A", service.inspect(session.id).session.ownerId)
        assertEquals(ResumableUploadSessionStatus.COMPLETED, service.inspect(session.id).session.status)
    }

    @Test
    fun `legacy unowned sessions fail closed for every user path but remain system cleanable`() {
        val state = State()
        val storage = FakeMultipartStorage()
        val clock = MutableClock(100)
        val service = service(storage, state, clock)
        val legacy = legacySession()
        state.sessions.save(legacy)
        val authorizationBefore = state.authorizationCalls.get()
        val storageBefore = storage.operationCalls()
        val mutationsBefore = state.sessions.mutationCalls.get()

        val failures = listOf(
            assertThrows<ResumableUploadNotFoundException> { service.inspect(legacy.id) },
            assertThrows<ResumableUploadNotFoundException> {
                service.uploadPart(legacy.id, 1, 1, ByteArrayInputStream(byteArrayOf(1)))
            },
            assertThrows<ResumableUploadNotFoundException> { service.complete(legacy.id) },
            assertThrows<ResumableUploadNotFoundException> { service.abort(legacy.id) },
        )
        assertEquals(1, failures.map { it.message }.distinct().size)
        val missing = assertThrows<ResumableUploadNotFoundException> {
            service(FakeMultipartStorage(), State(), clock).inspect(legacy.id)
        }
        assertEquals(missing.message, failures.first().message)
        assertEquals(authorizationBefore, state.authorizationCalls.get())
        assertEquals(storageBefore, storage.operationCalls())
        assertEquals(mutationsBefore, state.sessions.mutationCalls.get())

        state.currentUser = null
        val cleanup = service.cleanupExpired()

        assertEquals(1, cleanup.inspected)
        assertEquals(1, cleanup.expired)
        val cleaned = state.sessions.findById(legacy.tenantId, legacy.id)!!
        assertEquals(ResumableUploadSessionStatus.EXPIRED, cleaned.status)
        assertEquals(null, cleaned.ownerId)
        assertTrue(storage.aborted.contains(legacy.storageUploadId))
    }

    @Test
    fun `idempotency keys remain tenant global without revealing another owner`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val service = service(storage, state, MutableClock(100))
        val first = service.start(command())
        val replayAuthorizationOffset = state.authorizationRequests.size
        val repeated = service.start(command())
        assertEquals(first.id, repeated.id)
        assertEquals(1, storage.beginCalls.get())
        val replayAuthorizations = state.authorizationRequests.drop(replayAuthorizationOffset)
        assertEquals(1, replayAuthorizations.size)
        assertEquals(first.fileObjectId, replayAuthorizations.single().resource.id)

        state.currentUser = UserIdentity(Identifier("owner-B"))
        val mutationsBefore = state.sessions.mutationCalls.get()
        val crossOwnerAuthorizationOffset = state.authorizationRequests.size
        val conflict = assertThrows<ResumableUploadStateException> { service.start(command()) }
        assertEquals("Resumable upload idempotency key is unavailable.", conflict.message)
        assertTrue(conflict.message!!.contains(first.id.value).not())
        assertTrue(conflict.message!!.contains(first.ownerId!!).not())
        assertTrue(conflict.message!!.contains(command().idempotencyKey).not())
        assertEquals(1, storage.beginCalls.get())
        assertEquals(mutationsBefore, state.sessions.mutationCalls.get())
        val crossOwnerAuthorizations = state.authorizationRequests.drop(crossOwnerAuthorizationOffset)
        assertEquals(1, crossOwnerAuthorizations.size)
        assertNotEquals(first.fileObjectId, crossOwnerAuthorizations.single().resource.id)

        state.authorizationDecision = { AuthorizationDecision(false, "upload denied") }
        val globalKeyQueriesBeforeDeniedStarts = state.sessions.tenantGlobalIdempotencyQueryCalls.get()
        val deniedAuthorizationOffset = state.authorizationRequests.size
        assertThrows<ApplicationForbiddenException> { service.start(command()) }
        assertThrows<ApplicationForbiddenException> {
            service.start(command(idempotencyKey = "unoccupied-client-key"))
        }
        val deniedAuthorizations = state.authorizationRequests.drop(deniedAuthorizationOffset)
        assertEquals(2, deniedAuthorizations.size)
        assertTrue(deniedAuthorizations.none { it.resource.id == first.fileObjectId })
        assertNotEquals(deniedAuthorizations[0].resource.id, deniedAuthorizations[1].resource.id)
        assertEquals(
            globalKeyQueriesBeforeDeniedStarts,
            state.sessions.tenantGlobalIdempotencyQueryCalls.get(),
        )
        assertEquals(1, storage.beginCalls.get())
        assertEquals(mutationsBefore, state.sessions.mutationCalls.get())
    }

    @Test
    fun `rejects malformed trusted owner ids before repository authorization or storage`() {
        val supplementaryFormat = String(Character.toChars(0xE0001))
        val invalidOwnerIds = listOf(
            " owner",
            "owner ",
            "owner\u0000",
            "owner\u200D",
            "owner$supplementaryFormat",
            "owner\uD800",
            "owner\uDC00",
            "x".repeat(MAX_RESUMABLE_UPLOAD_OWNER_ID_LENGTH + 1),
        )

        invalidOwnerIds.forEach { invalidOwnerId ->
            val state = State().apply { currentUser = UserIdentity(Identifier(invalidOwnerId)) }
            val storage = FakeMultipartStorage()

            assertThrows<ApplicationUnauthenticatedException> {
                service(storage, state, MutableClock(100)).start(command())
            }
            assertEquals(0, state.authorizationCalls.get(), "authorization calls for ${invalidOwnerId.length} code units")
            assertEquals(0, state.sessions.mutationCalls.get(), "repository mutations for ${invalidOwnerId.length} code units")
            assertEquals(0, storage.operationCalls(), "storage calls for ${invalidOwnerId.length} code units")
        }
    }

    @Test
    fun `owner validation uses the fixed V024 code point contract on every supported JDK`() {
        fun codePoint(value: Int): String = String(Character.toChars(value))
        val forbidden = ArrayList<Int>()
        forbidden += (0x0000..0x001F)
        forbidden += (0x007F..0x009F)
        forbidden += listOf(0x00AD)
        forbidden += (0x0600..0x0605)
        forbidden += listOf(0x061C, 0x06DD, 0x070F)
        forbidden += (0x0890..0x0891)
        forbidden += listOf(0x08E2, 0x180E)
        forbidden += (0x200B..0x200F)
        forbidden += (0x202A..0x202E)
        forbidden += (0x2060..0x2064)
        forbidden += (0x2066..0x206F)
        forbidden += listOf(0xFEFF)
        forbidden += (0xFFF9..0xFFFB)
        forbidden += listOf(0x110BD, 0x110CD)
        forbidden += (0x13430..0x1343F)
        forbidden += (0x1BCA0..0x1BCA3)
        forbidden += (0x1D173..0x1D17A)
        forbidden += listOf(0xE0001)
        forbidden += (0xE0020..0xE007F)
        forbidden.distinct().forEach { value ->
            assertThrows<IllegalArgumentException>("U+${value.toString(16)}") {
                validatedResumableUploadOwnerId("owner${codePoint(value)}id")
            }
        }

        val boundaryWhitespace = listOf(0x0020, 0x00A0, 0x1680) +
            (0x2000..0x200A) + (0x2028..0x2029) + listOf(0x202F, 0x205F, 0x3000)
        boundaryWhitespace.forEach { value ->
            val whitespace = codePoint(value)
            assertThrows<IllegalArgumentException>("leading U+${value.toString(16)}") {
                validatedResumableUploadOwnerId("${whitespace}owner")
            }
            assertThrows<IllegalArgumentException>("trailing U+${value.toString(16)}") {
                validatedResumableUploadOwnerId("owner$whitespace")
            }
        }

        val supplementary = codePoint(0x1F600)
        assertEquals(supplementary.repeat(128), validatedResumableUploadOwnerId(supplementary.repeat(128)))
        assertEquals("x".repeat(256), validatedResumableUploadOwnerId("x".repeat(256)))
        assertThrows<IllegalArgumentException> { validatedResumableUploadOwnerId(supplementary.repeat(129)) }
        assertThrows<IllegalArgumentException> { validatedResumableUploadOwnerId("x".repeat(257)) }
        assertThrows<IllegalArgumentException> { validatedResumableUploadOwnerId("owner\uD800") }
        assertThrows<IllegalArgumentException> { validatedResumableUploadOwnerId("owner\uDC00") }
    }

    @Test
    fun `legacy repositories can read exact owned sessions but cannot create without quarantine support`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val session = service(storage, state, MutableClock(100)).start(command())
        val legacyService = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = LegacyRepositoryView(state.sessions),
        )
        assertEquals(session.id, legacyService.inspect(session.id).session.id)
        val authorizationBefore = state.authorizationCalls.get()

        state.currentUser = UserIdentity(Identifier("owner-B"))

        assertThrows<ResumableUploadNotFoundException> { legacyService.inspect(session.id) }
        assertEquals(authorizationBefore, state.authorizationCalls.get())
        assertEquals("owner-A", state.sessions.findById(state.tenant, session.id)?.ownerId)

        val emptyState = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val emptyStorage = FakeMultipartStorage()
        val failure = assertThrows<ResumableUploadStateException> {
            service(
                emptyStorage,
                emptyState,
                MutableClock(100),
                sessionRepository = LegacyRepositoryView(emptyState.sessions),
            ).start(command())
        }
        assertEquals(QUARANTINE_CAPABILITY_REQUIRED_MESSAGE, failure.message)
        assertEquals(0, emptyStorage.operationCalls())
    }

    @Test
    fun `does not trust a broken owner scoped repository to enforce ownership`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val ownerService = service(storage, state, MutableClock(100))
        val session = ownerService.start(command())
        state.currentUser = UserIdentity(Identifier("owner-B"))
        val brokenService = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = BrokenOwnerScopedRepository(state.sessions, session),
        )
        val authorizationBefore = state.authorizationCalls.get()
        val storageBefore = storage.operationCalls()
        val mutationsBefore = state.sessions.mutationCalls.get()

        assertThrows<ResumableUploadNotFoundException> { brokenService.inspect(session.id) }
        assertEquals(authorizationBefore, state.authorizationCalls.get())
        assertEquals(storageBefore, storage.operationCalls())
        assertEquals(mutationsBefore, state.sessions.mutationCalls.get())

        val conflict = assertThrows<ResumableUploadStateException> { brokenService.start(command()) }
        assertEquals("Resumable upload idempotency key is unavailable.", conflict.message)
        assertEquals(storageBefore, storage.operationCalls())
        assertEquals(mutationsBefore, state.sessions.mutationCalls.get())
    }

    @Test
    fun `forged owner capability cannot claim an existing session or bypass the global idempotency conflict`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val ownerService = service(storage, state, MutableClock(100))
        val session = ownerService.start(command())
        state.currentUser = UserIdentity(Identifier("owner-B"))
        val forgedService = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = WrongOwnerCapabilityRepository(state.sessions),
        )
        val authorizationBefore = state.authorizationCalls.get()
        val storageBefore = storage.operationCalls()
        val mutationsBefore = state.sessions.mutationCalls.get()

        listOf<() -> Unit>(
            { forgedService.inspect(session.id) },
            { forgedService.uploadPart(session.id, 1, 1, ByteArrayInputStream(byteArrayOf(1))) },
            { forgedService.complete(session.id) },
            { forgedService.abort(session.id) },
        ).forEach { action -> assertThrows<ResumableUploadNotFoundException> { action() } }

        assertEquals(authorizationBefore, state.authorizationCalls.get())
        assertEquals(storageBefore, storage.operationCalls())
        assertEquals(mutationsBefore, state.sessions.mutationCalls.get())
        val conflict = assertThrows<ResumableUploadStateException> { forgedService.start(command()) }
        assertEquals("Resumable upload idempotency key is unavailable.", conflict.message)
        assertEquals(storageBefore, storage.operationCalls())
        assertEquals(mutationsBefore, state.sessions.mutationCalls.get())
        assertEquals("owner-A", state.sessions.findById(state.tenant, session.id)?.ownerId)
    }

    @Test
    fun `owner authorization denial is forbidden while a denied non owner still receives not found first`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val service = service(storage, state, MutableClock(100))
        val session = service.start(command())
        state.authorizationDecision = { AuthorizationDecision(false, "permission revoked") }
        val mutationsBefore = state.sessions.mutationCalls.get()
        val storageBefore = storage.operationCalls()

        assertThrows<ApplicationForbiddenException> { service.inspect(session.id) }
        val authorizationAfterOwner = state.authorizationCalls.get()

        state.currentUser = UserIdentity(Identifier("owner-B"))
        assertThrows<ResumableUploadNotFoundException> { service.inspect(session.id) }
        assertEquals(authorizationAfterOwner, state.authorizationCalls.get())
        assertEquals(mutationsBefore, state.sessions.mutationCalls.get())
        assertEquals(storageBefore, storage.operationCalls())
    }

    @Test
    fun `save race with another owner aborts remote upload and returns a fixed conflict`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val racingRepository = UniqueKeyRaceRepository(state.sessions)
        val service = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = racingRepository,
        )

        val conflict = assertThrows<ResumableUploadStateException> { service.start(command()) }

        assertEquals("Resumable upload idempotency key is unavailable.", conflict.message)
        assertTrue(conflict.message!!.contains("owner-A").not())
        assertTrue(conflict.message!!.contains("owner-B").not())
        assertEquals(1, storage.beginCalls.get())
        assertEquals(1, storage.abortCalls.get())
        assertEquals(1, storage.deleteCalls.get())
        assertEquals(null, state.sessions.findById(state.tenant, Identifier("session-1")))
        val competing = state.sessions.findByIdempotencyKey(state.tenant, command().idempotencyKey)!!
        assertEquals(racingRepository.competingSession?.id, competing.id)
        assertEquals("owner-B", competing.ownerId)
        assertTrue(state.authorizationRequests.none { it.resource.id == competing.fileObjectId })
        assertTrue(conflict.suppressed.contains(racingRepository.saveFailure))
    }

    @Test
    fun `save race with an equivalent same owner upload replays the winner after cleaning only the losing multipart`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val racingRepository = UniqueKeyRaceRepository(state.sessions, competingOwnerId = "owner-A")
        val service = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = racingRepository,
        )

        val replayed = service.start(command())

        assertEquals(racingRepository.competingSession?.id, replayed.id)
        assertEquals("owner-A", replayed.ownerId)
        assertEquals(1, storage.beginCalls.get())
        assertEquals(1, storage.abortCalls.get())
        assertEquals(1, storage.deleteCalls.get())
        assertTrue(state.authorizationRequests.any { it.resource.id == replayed.fileObjectId })
    }

    @Test
    fun `save that persisted before throwing is reconciled as success without remote cleanup`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val repository = CommitThenThrowRepository(state.sessions)
        val session = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = repository,
        ).start(command())

        assertEquals(repository.persistedSession?.id, session.id)
        assertEquals(ResumableUploadSessionStatus.ACTIVE, session.status)
        assertEquals("owner-A", session.ownerId)
        assertEquals(1, storage.beginCalls.get())
        assertEquals(0, storage.abortCalls.get())
        assertEquals(0, storage.deleteCalls.get())
        assertTrue(repository.saveFailure.suppressed.isEmpty())
    }

    @Test
    fun `unknown transaction outcomes never clean remote state when reconciliation is empty or unavailable`() {
        listOf(false, true).forEach { failReconciliationReads ->
            val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
            val storage = FakeMultipartStorage()
            val repository = OutcomeUnknownSaveRepository(state.sessions, failReconciliationReads)
            val service = service(
                storage,
                state,
                MutableClock(100),
                sessionRepository = repository,
            )

            val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> {
                service.start(command())
            }

            assertSame(repository.outcomeUnknown, failure)
            assertEquals(ApplicationTransactionOutcomeUnknownException.DEFAULT_MESSAGE, failure.message)
            assertEquals(repository.commitFailure, failure.cause)
            assertEquals(if (failReconciliationReads) 4 else 0, failure.suppressed.size)
            assertEquals(1, storage.beginCalls.get())
            assertEquals(0, storage.abortCalls.get())
            assertEquals(0, storage.deleteCalls.get())
        }
    }

    @Test
    fun `save exception reconciliation rejects global rows returned outside tenant id or key`() {
        MisdirectedGlobalLookup.values().forEach { misdirection ->
            val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
            val storage = FakeMultipartStorage()
            val repository = MisdirectedGlobalLookupRepository(state.sessions, misdirection)
            val service = service(
                storage,
                state,
                MutableClock(100),
                sessionRepository = repository,
            )

            val failure = assertThrows<ApplicationTransactionOutcomeUnknownException>(misdirection.name) {
                service.start(command())
            }

            assertSame(repository.outcomeUnknown, failure)
            assertTrue(failure.suppressed.any { it.message?.contains("outside its requested key") == true })
            assertEquals(1, storage.beginCalls.get(), misdirection.name)
            assertEquals(0, storage.abortCalls.get(), misdirection.name)
            assertEquals(0, storage.deleteCalls.get(), misdirection.name)
        }
    }

    @Test
    fun `commit acknowledgement loss with missing or wrong owner capability quarantines before any handle can escape`() {
        OwnerCapabilityFault.values().forEach { fault ->
            val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
            val storage = FakeMultipartStorage()
            val repository = CommitThenOwnerCapabilityFaultRepository(state.sessions, fault)
            val service = service(
                storage,
                state,
                MutableClock(100),
                sessionRepository = repository,
            )

            val failure = assertThrows<ResumableUploadStateException>(fault.name) { service.start(command()) }

            assertEquals(START_PERSISTENCE_CONTRACT_MESSAGE, failure.message)
            val persisted = state.sessions.findById(state.tenant, Identifier("session-1"))!!
            assertEquals(ResumableUploadSessionStatus.QUARANTINED, persisted.status)
            assertEquals(START_PERSISTENCE_ISOLATION_MARKER, persisted.lastError)
            assertEquals(1, storage.abortCalls.get())
            assertEquals(1, storage.deleteCalls.get())

            state.currentUser = UserIdentity(Identifier("owner-B"))
            assertThrows<ResumableUploadNotFoundException>(fault.name) { service.inspect(persisted.id) }
            assertThrows<ResumableUploadNotFoundException>(fault.name) { service.abort(persisted.id) }
            val replay = assertThrows<ResumableUploadStateException>(fault.name) { service.start(command()) }
            assertEquals("Resumable upload idempotency key is unavailable.", replay.message)
            assertEquals(1, storage.beginCalls.get())
        }
    }

    @Test
    fun `known pre commit save rejection cleans the remote multipart upload`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val repository = RejectingSaveRepository(state.sessions)
        val service = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = repository,
        )

        val failure = assertThrows<IllegalStateException> { service.start(command()) }

        assertSame(repository.saveFailure, failure)
        assertEquals(1, storage.beginCalls.get())
        assertEquals(1, storage.abortCalls.get())
        assertEquals(1, storage.deleteCalls.get())
    }

    @Test
    fun `save acknowledgement that drops owner never publishes a session and quarantines the multipart`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val repository = LegacyOwnerRewritingRepository(state.sessions, persistedOwnerId = null)
        val service = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = repository,
        )

        val failure = assertThrows<ResumableUploadStateException> { service.start(command()) }

        assertEquals(START_PERSISTENCE_CONTRACT_MESSAGE, failure.message)
        assertEquals(1, storage.beginCalls.get())
        assertEquals(1, storage.abortCalls.get())
        assertEquals(1, storage.deleteCalls.get())
        val persisted = state.sessions.findById(state.tenant, Identifier("session-1"))!!
        assertEquals(null, persisted.ownerId)
        assertEquals(ResumableUploadSessionStatus.QUARANTINED, persisted.status)
        assertEquals(START_PERSISTENCE_ISOLATION_MARKER, persisted.lastError)
        assertThrows<ResumableUploadNotFoundException> { service.inspect(persisted.id) }

        state.currentUser = UserIdentity(Identifier("owner-B"))
        assertThrows<ResumableUploadNotFoundException> { service.inspect(persisted.id) }
        state.currentUser = UserIdentity(Identifier("owner-A"))
        val replay = assertThrows<ResumableUploadStateException> { service.start(command()) }
        assertEquals("Resumable upload idempotency key is unavailable.", replay.message)
        assertEquals(1, storage.beginCalls.get())
    }

    @Test
    fun `legacy mapper rewriting owner to another user is fenced and never returned as success`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val clock = MutableClock(100)
        val repository = LegacyOwnerRewritingRepository(state.sessions, persistedOwnerId = "owner-B")
        val service = service(
            storage,
            state,
            clock,
            ttl = Duration.ofMillis(5),
            sessionRepository = repository,
        )

        val failure = assertThrows<ResumableUploadStateException> { service.start(command()) }

        assertEquals(START_PERSISTENCE_CONTRACT_MESSAGE, failure.message)
        val persisted = state.sessions.findById(state.tenant, Identifier("session-1"))!!
        assertEquals("owner-B", persisted.ownerId)
        assertEquals(ResumableUploadSessionStatus.QUARANTINED, persisted.status)
        assertEquals(START_PERSISTENCE_ISOLATION_MARKER, persisted.lastError)
        assertEquals(1, storage.abortCalls.get())
        assertEquals(1, storage.deleteCalls.get())
        assertThrows<ResumableUploadNotFoundException> { service.inspect(persisted.id) }

        state.currentUser = UserIdentity(Identifier("owner-B"))
        assertThrows<ResumableUploadNotFoundException> { service.inspect(persisted.id) }
        assertThrows<ResumableUploadNotFoundException> { service.abort(persisted.id) }
        val wrongOwnerReplay = assertThrows<ResumableUploadStateException> { service.start(command()) }
        assertEquals("Resumable upload idempotency key is unavailable.", wrongOwnerReplay.message)
        assertTrue(wrongOwnerReplay.message!!.contains(persisted.id.value).not())
        assertTrue(wrongOwnerReplay.message!!.contains(persisted.ownerId!!).not())

        clock.advance(5)
        val cleanup = service.cleanupExpired()
        val expired = state.sessions.findById(state.tenant, persisted.id)!!
        assertEquals(0, cleanup.inspected)
        assertEquals(0, cleanup.expired)
        assertEquals(ResumableUploadSessionStatus.QUARANTINED, expired.status)
        assertEquals(START_PERSISTENCE_ISOLATION_MARKER, expired.lastError)
        assertThrows<ResumableUploadNotFoundException> { service.inspect(expired.id) }
        assertThrows<ResumableUploadNotFoundException> { service.abort(expired.id) }
        val postCleanupReplay = assertThrows<ResumableUploadStateException> { service.start(command()) }
        assertEquals("Resumable upload idempotency key is unavailable.", postCleanupReplay.message)

        state.currentUser = UserIdentity(Identifier("owner-A"))
        val originalOwnerReplay = assertThrows<ResumableUploadStateException> { service.start(command()) }
        assertEquals("Resumable upload idempotency key is unavailable.", originalOwnerReplay.message)
        assertEquals(1, storage.beginCalls.get())
    }

    @Test
    fun `quarantine marker acknowledgement loss is reconciled before remote cleanup`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val repository = QuarantineMarkOutcomeRepository(state.sessions, persistMarkerBeforeFailure = true)
        val service = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = repository,
        )

        val failure = assertThrows<ResumableUploadStateException> { service.start(command()) }

        assertEquals(START_PERSISTENCE_CONTRACT_MESSAGE, failure.message)
        assertTrue(failure.suppressed.contains(repository.markOutcomeUnknown))
        val persisted = state.sessions.findById(state.tenant, Identifier("session-1"))!!
        assertEquals(ResumableUploadSessionStatus.QUARANTINED, persisted.status)
        assertEquals(START_PERSISTENCE_ISOLATION_MARKER, persisted.lastError)
        assertEquals(1, storage.abortCalls.get())
        assertEquals(1, storage.deleteCalls.get())
    }

    @Test
    fun `unconfirmed quarantine marker preserves remote state and remains hidden from the wrong owner`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val clock = MutableClock(100)
        val repository = QuarantineMarkOutcomeRepository(state.sessions, persistMarkerBeforeFailure = false)
        val service = service(
            storage,
            state,
            clock,
            ttl = Duration.ofMillis(5),
            sessionRepository = repository,
        )

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> { service.start(command()) }

        assertEquals(START_PERSISTENCE_CONTRACT_MESSAGE, failure.cause?.message)
        val persisted = state.sessions.findById(state.tenant, Identifier("session-1"))!!
        assertEquals("owner-B", persisted.ownerId)
        assertEquals(ResumableUploadSessionStatus.ABORTING, persisted.status)
        assertEquals(0, storage.abortCalls.get())
        assertEquals(0, storage.deleteCalls.get())

        state.currentUser = UserIdentity(Identifier("owner-B"))
        assertThrows<ResumableUploadNotFoundException> { service.inspect(persisted.id) }
        assertThrows<ResumableUploadNotFoundException> { service.abort(persisted.id) }
        val replay = assertThrows<ResumableUploadStateException> { service.start(command()) }
        assertEquals("Resumable upload idempotency key is unavailable.", replay.message)
        assertEquals(1, storage.beginCalls.get())

        clock.advance(5)
        val cleanup = service.cleanupExpired()
        assertEquals(1, cleanup.inspected)
        assertEquals(0, cleanup.expired)
        assertEquals(1, cleanup.failed)
        assertEquals(ResumableUploadSessionStatus.ABORTING, state.sessions.findById(state.tenant, persisted.id)?.status)
        assertEquals(0, storage.abortCalls.get())
        assertEquals(0, storage.deleteCalls.get())
        assertThrows<ResumableUploadNotFoundException> { service.inspect(persisted.id) }
    }

    @Test
    fun `quarantine remains durable and hidden when remote abort or delete cleanup fails`() {
        listOf("abort", "delete").forEach { failurePoint ->
            val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
            val storage = FakeMultipartStorage().apply {
                failAbort = failurePoint == "abort"
                failDelete = failurePoint == "delete"
            }
            val repository = LegacyOwnerRewritingRepository(state.sessions, persistedOwnerId = "owner-B")
            val service = service(storage, state, MutableClock(100), sessionRepository = repository)

            val failure = assertThrows<ResumableUploadStateException>(failurePoint) { service.start(command()) }

            assertEquals(START_PERSISTENCE_CONTRACT_MESSAGE, failure.message)
            assertTrue(failure.suppressed.isNotEmpty(), failurePoint)
            val persisted = state.sessions.findById(state.tenant, Identifier("session-1"))!!
            assertEquals(ResumableUploadSessionStatus.QUARANTINED, persisted.status, failurePoint)
            assertEquals(START_PERSISTENCE_ISOLATION_MARKER, persisted.lastError, failurePoint)
            state.currentUser = UserIdentity(Identifier("owner-B"))
            assertThrows<ResumableUploadNotFoundException>(failurePoint) { service.inspect(persisted.id) }
            assertThrows<ResumableUploadNotFoundException>(failurePoint) { service.abort(persisted.id) }
            val replay = assertThrows<ResumableUploadStateException>(failurePoint) { service.start(command()) }
            assertEquals("Resumable upload idempotency key is unavailable.", replay.message)
            assertEquals(1, storage.beginCalls.get())
        }
    }

    @Test
    fun `save acknowledgement with no persisted row aborts multipart and reports repository contract failure`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val repository = NoOpSaveRepository(state.sessions)
        val service = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = repository,
        )

        val failure = assertThrows<ResumableUploadStateException> { service.start(command()) }

        assertEquals(START_PERSISTENCE_CONTRACT_MESSAGE, failure.message)
        assertEquals(null, state.sessions.findById(state.tenant, Identifier("session-1")))
        assertEquals(1, storage.beginCalls.get())
        assertEquals(1, storage.abortCalls.get())
        assertEquals(1, storage.deleteCalls.get())
    }

    @Test
    fun `post save reconciliation read failure preserves remote and reports unknown outcome`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val repository = PostSaveReadFailureRepository(state.sessions)
        val service = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = repository,
        )

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> { service.start(command()) }

        assertEquals(ApplicationTransactionOutcomeUnknownException.DEFAULT_MESSAGE, failure.message)
        assertEquals(repository.readFailure.message, failure.cause?.message)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals("owner-A", state.sessions.findById(state.tenant, Identifier("session-1"))?.ownerId)
        assertEquals(1, storage.beginCalls.get())
        assertEquals(0, storage.abortCalls.get())
        assertEquals(0, storage.deleteCalls.get())
    }

    @Test
    fun `contradictory post save reads preserve the referenced remote for reconciliation`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val repository = ContradictoryPostSaveRepository(state.sessions)
        val service = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = repository,
        )

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> { service.start(command()) }

        assertEquals(START_PERSISTENCE_CONTRACT_MESSAGE, failure.cause?.message)
        assertEquals(1, failure.suppressed.size)
        assertEquals("owner-A", state.sessions.findById(state.tenant, Identifier("session-1"))?.ownerId)
        assertEquals(ResumableUploadSessionStatus.ABORTING, state.sessions.findById(state.tenant, Identifier("session-1"))?.status)
        assertEquals(START_CREATION_STAGING_MARKER, state.sessions.findById(state.tenant, Identifier("session-1"))?.lastError)
        assertEquals(1, storage.beginCalls.get())
        assertEquals(0, storage.abortCalls.get())
        assertEquals(0, storage.deleteCalls.get())
    }

    @Test
    fun `owner capability returning the wrong owner cannot fabricate a successful start and is quarantined`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val repository = WrongOwnerCapabilityRepository(state.sessions)
        val service = service(
            storage,
            state,
            MutableClock(100),
            sessionRepository = repository,
        )

        val failure = assertThrows<ResumableUploadStateException> { service.start(command()) }

        assertEquals(START_PERSISTENCE_CONTRACT_MESSAGE, failure.message)
        val persisted = state.sessions.findById(state.tenant, Identifier("session-1"))!!
        assertEquals("owner-A", persisted.ownerId)
        assertEquals(ResumableUploadSessionStatus.QUARANTINED, persisted.status)
        assertEquals(1, storage.beginCalls.get())
        assertEquals(1, storage.abortCalls.get())
        assertEquals(1, storage.deleteCalls.get())
        assertThrows<ResumableUploadNotFoundException> { service.inspect(persisted.id) }

        state.currentUser = UserIdentity(Identifier("owner-B"))
        assertThrows<ResumableUploadNotFoundException> { service.inspect(persisted.id) }
        assertThrows<ResumableUploadNotFoundException> { service.abort(persisted.id) }
        val wrongOwnerReplay = assertThrows<ResumableUploadStateException> { service.start(command()) }
        assertEquals("Resumable upload idempotency key is unavailable.", wrongOwnerReplay.message)

        state.currentUser = UserIdentity(Identifier("owner-A"))
        val originalOwnerReplay = assertThrows<ResumableUploadStateException> { service.start(command()) }
        assertEquals("Resumable upload idempotency key is unavailable.", originalOwnerReplay.message)
        assertEquals(1, storage.beginCalls.get())
    }

    @Test
    fun `misdirected completion abort and cleanup claims fail inside the transaction before storage`() {
        MisdirectedClaim.values().forEach { mode ->
            val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
            val storage = FakeMultipartStorage()
            val clock = MutableClock(100)
            val ownerService = service(storage, state, clock, ttl = Duration.ofMillis(5))
            val session = ownerService.start(command())
            if (mode == MisdirectedClaim.COMPLETION) {
                ownerService.uploadPart(session.id, 1, 7, ByteArrayInputStream("content".toByteArray()))
            }
            if (mode == MisdirectedClaim.CLEANUP) clock.advance(5)
            val observingTransaction = ClaimValidationObservingTransaction()
            val forgedService = service(
                storage,
                state,
                clock,
                ttl = Duration.ofMillis(5),
                sessionRepository = MisdirectedClaimRepository(state.sessions, mode),
                transaction = observingTransaction,
            )
            val storageBefore = storage.operationCalls()

            when (mode) {
                MisdirectedClaim.COMPLETION ->
                    assertThrows<ResumableUploadNotFoundException> { forgedService.complete(session.id) }
                MisdirectedClaim.ABORT ->
                    assertThrows<ResumableUploadNotFoundException> { forgedService.abort(session.id) }
                MisdirectedClaim.CLEANUP -> {
                    val cleanup = forgedService.cleanupExpired()
                    assertEquals(1, cleanup.inspected)
                    assertEquals(0, cleanup.expired)
                    assertEquals(1, cleanup.failed)
                }
            }

            assertTrue(observingTransaction.validationFailedInsideTransaction, mode.name)
            assertEquals(storageBefore, storage.operationCalls(), mode.name)
            assertEquals(ResumableUploadSessionStatus.ACTIVE, state.sessions.findById(state.tenant, session.id)?.status, mode.name)
        }
    }

    @Test
    fun `wrong owner staging remains invisible to a concurrent user until it is quarantined`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val repository = PausingWrongOwnerStagingRepository(state.sessions)
        val service = service(storage, state, MutableClock(100), sessionRepository = repository)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val ownerAttempt = executor.submit<Throwable?> {
                try {
                    service.start(command())
                    null
                } catch (failure: Throwable) {
                    failure
                }
            }
            assertTrue(repository.stagedSaved.await(5, TimeUnit.SECONDS))
            val staged = state.sessions.findById(state.tenant, Identifier("session-1"))!!
            assertEquals("owner-B", staged.ownerId)
            assertEquals(ResumableUploadSessionStatus.ABORTING, staged.status)
            assertEquals(START_CREATION_STAGING_MARKER, staged.lastError)
            val mutationsBeforeIntruder = state.sessions.mutationCalls.get()
            val storageBeforeIntruder = storage.operationCalls()
            state.currentUser = UserIdentity(Identifier("owner-B"))

            listOf<() -> Unit>(
                { service.inspect(staged.id) },
                { service.uploadPart(staged.id, 1, 1, ByteArrayInputStream(byteArrayOf(1))) },
                { service.complete(staged.id) },
                { service.abort(staged.id) },
            ).forEach { action -> assertThrows<ResumableUploadNotFoundException> { action() } }
            val conflict = assertThrows<ResumableUploadStateException> { service.start(command()) }
            assertEquals("Resumable upload idempotency key is unavailable.", conflict.message)
            assertEquals(mutationsBeforeIntruder, state.sessions.mutationCalls.get())
            assertEquals(storageBeforeIntruder, storage.operationCalls())

            repository.allowVerification.countDown()
            val ownerFailure = ownerAttempt.get(5, TimeUnit.SECONDS)
            assertTrue(ownerFailure is ResumableUploadStateException)
            val quarantined = state.sessions.findById(state.tenant, staged.id)!!
            assertEquals(ResumableUploadSessionStatus.QUARANTINED, quarantined.status)
            assertEquals(START_PERSISTENCE_ISOLATION_MARKER, quarantined.lastError)
            assertEquals(1, storage.abortCalls.get())
            assertEquals(1, storage.deleteCalls.get())
        } finally {
            repository.allowVerification.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cleanup rejects a malicious future active candidate before claim or storage`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val clock = MutableClock(100)
        val ownerService = service(storage, state, clock)
        val session = ownerService.start(command())
        val malicious = FutureCleanupCandidateRepository(state.sessions, session)
        val cleanupService = service(storage, state, clock, sessionRepository = malicious)
        val mutationsBefore = state.sessions.mutationCalls.get()
        val storageBefore = storage.operationCalls()

        val cleanup = cleanupService.cleanupExpired()

        assertEquals(1, cleanup.inspected)
        assertEquals(0, cleanup.expired)
        assertEquals(1, cleanup.failed)
        assertEquals(mutationsBefore, state.sessions.mutationCalls.get())
        assertEquals(storageBefore, storage.operationCalls())
        assertEquals(ResumableUploadSessionStatus.ACTIVE, state.sessions.findById(state.tenant, session.id)?.status)
    }

    @Test
    fun `staging that expires at activation is never published active`() {
        val state = State().apply { currentUser = UserIdentity(Identifier("owner-A")) }
        val storage = FakeMultipartStorage()
        val clock = MutableClock(100)
        val repository = ExpiringActivationRepository(state.sessions, clock)
        val service = service(
            storage,
            state,
            clock,
            ttl = Duration.ofMillis(5),
            sessionRepository = repository,
        )

        val failure = assertThrows<ResumableUploadStateException> { service.start(command()) }

        assertEquals(START_PERSISTENCE_CONTRACT_MESSAGE, failure.message)
        val persisted = state.sessions.findById(state.tenant, Identifier("session-1"))!!
        assertTrue(repository.activationTimes.isNotEmpty())
        assertTrue(repository.activationTimes.all { it == persisted.expiresAt })
        assertEquals(ResumableUploadSessionStatus.QUARANTINED, persisted.status)
        assertEquals(START_PERSISTENCE_ISOLATION_MARKER, persisted.lastError)
        assertEquals(1, storage.abortCalls.get())
        assertEquals(1, storage.deleteCalls.get())
    }

    private fun service(
        storage: FakeMultipartStorage,
        state: State,
        clock: Clock,
        ttl: Duration = Duration.ofHours(1),
        sessionRepository: ResumableUploadSessionRepository = state.sessions,
        fileObjectRepository: FileObjectRepository = state.fileObjects,
        fileAssetRepository: FileAssetRepository = state.fileAssets,
        transaction: ApplicationTransaction = DirectTransaction,
        identifierGenerator: IdentifierGenerator? = null,
    ) = ResumableUploadService(
        tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(state.tenant) },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity? {
                state.currentUserCalls.incrementAndGet()
                return state.currentUser
            }
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
                state.authorizationCalls.incrementAndGet()
                state.authorizationRequests += request
                return state.authorizationDecision(request)
            }
        },
        storageAdapter = storage,
        sessions = sessionRepository,
        fileObjects = fileObjectRepository,
        fileAssets = fileAssetRepository,
        outbox = state,
        identifiers = identifierGenerator ?: object : IdentifierGenerator {
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
        transaction = transaction,
        clock = clock,
        sessionTtl = ttl,
    )

    private fun command(
        contentLength: Long = 7,
        idempotencyKey: String = "client-upload-1",
        contentHash: String? = null,
    ) = StartResumableUploadCommand(
        fileName = "contract.pdf",
        contentLength = contentLength,
        assetType = "DOCUMENT",
        idempotencyKey = idempotencyKey,
        contentType = "application/pdf",
        contentHash = contentHash,
    )

    private companion object {
        const val START_PERSISTENCE_CONTRACT_MESSAGE =
            "Resumable upload persistence did not preserve the required owner-scoped session."
        const val START_PERSISTENCE_ISOLATION_MARKER =
            "fileweft:resumable-upload:owner-isolation:v1"
        const val START_CREATION_STAGING_MARKER =
            "fileweft:resumable-upload:creation-staging:v1"
        const val QUARANTINE_CAPABILITY_REQUIRED_MESSAGE =
            "Resumable upload creation requires a repository with durable quarantine support."
    }

    private fun legacySession() = ResumableUploadSession(
        id = Identifier("legacy-session"),
        tenantId = Identifier("tenant-1"),
        idempotencyKey = "legacy-idempotency-key",
        storageUploadId = Identifier("legacy-storage-upload"),
        storageLocation = StorageObjectLocation("test", "uploads/legacy-session"),
        fileObjectId = Identifier("legacy-file-object"),
        fileAssetId = Identifier("legacy-file-asset"),
        fileName = "legacy.pdf",
        contentLength = 1,
        assetType = "DOCUMENT",
        expiresAt = 50,
        createdTime = 0,
        updatedTime = 0,
    )

    /** Models a commit that succeeded durably while its acknowledgement was lost. */
    private object ActiveTransaction : ApplicationTransaction, ApplicationTransactionState {
        override fun <T> execute(action: () -> T): T = error("Active transaction guard was bypassed.")

        override fun isTransactionActive(): Boolean = true
    }

    private class CommitAcknowledgementLostTransaction : ApplicationTransaction {
        private val commitFailure = IllegalStateException("simulated completion commit acknowledgement failure")
        val outcomeUnknown = ApplicationTransactionOutcomeUnknownException(commitFailure)
        var observedFailure: Throwable? = null
            private set

        override fun <T> execute(action: () -> T): T {
            val result = action()
            if (observedFailure == null && result is UploadFileResult) {
                observedFailure = outcomeUnknown
                throw outcomeUnknown
            }
            return result
        }
    }

    /** Fails completion persistence before or after writing a selectable FileObject shape. */
    private class CompletionFailingFileObjectRepository(
        private val delegate: FileObjectRepository,
        val saveFailure: Throwable,
        private val failReconciliationReads: Boolean = false,
        private val persistBeforeFailure: Boolean = false,
        private val persistInconsistentObject: Boolean = false,
    ) : FileObjectRepository {
        val readFailure = IllegalStateException("simulated completion reconciliation read failure")
        private var saveAttempted = false

        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? {
            if (saveAttempted && failReconciliationReads) throw readFailure
            return delegate.findById(tenantId, fileObjectId)
        }

        override fun save(fileObject: FileObject) {
            saveAttempted = true
            if (persistBeforeFailure) {
                delegate.save(
                    if (persistInconsistentObject) {
                        FileObject(
                            id = fileObject.id,
                            tenantId = fileObject.tenantId,
                            fileName = fileObject.fileName,
                            contentLength = fileObject.contentLength,
                            storageType = fileObject.storageType,
                            storagePath = "${fileObject.storagePath}.inconsistent",
                            contentType = fileObject.contentType,
                            contentHash = fileObject.contentHash,
                        )
                    } else {
                        fileObject
                    },
                )
            }
            throw saveFailure
        }
    }

    /** Persists no FileAsset so the preceding FileObject becomes a visible partial reference. */
    private class CompletionFailingFileAssetRepository(
        private val delegate: FileAssetRepository,
        val saveFailure: Throwable,
    ) : FileAssetRepository {
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
            delegate.findById(tenantId, fileAssetId)

        override fun save(fileAsset: FileAsset) {
            throw saveFailure
        }
    }

    private class State : OutboxEventRepository {
        var tenant = Identifier("tenant-1")
        var currentUser: UserIdentity? = UserIdentity(Identifier("user-1"))
        val currentUserCalls = AtomicInteger()
        val authorizationCalls = AtomicInteger()
        val authorizationRequests = mutableListOf<AuthorizationRequest>()
        var authorizationDecision: (AuthorizationRequest) -> AuthorizationDecision = { AuthorizationDecision(true) }
        val ids = AtomicInteger(0)
        val sessions = InMemorySessions()
        val fileObjects = InMemoryFileObjects()
        val fileAssets = InMemoryFileAssets()
        val events = mutableListOf<OutboxEvent>()

        override fun append(event: OutboxEvent) {
            events += event
        }

        fun businessMutationCalls(): Int = fileObjects.saveCalls.get() + fileAssets.saveCalls.get() + events.size
    }

    /** Simulates a v0.0.1 repository implementation that knows only the original abstract port. */
    private class LegacyRepositoryView(
        delegate: ResumableUploadSessionRepository,
    ) : ResumableUploadSessionRepository by delegate

    /** Keeps released staging/owner behavior while deliberately omitting the formal completion-reset capability. */
    private class FormalRepositoryWithoutCompletionReset(
        private val delegate: InMemorySessions,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, ownerId, sessionId)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
    }

    /** Keeps reset/owner behavior while deliberately omitting staging and quarantine support. */
    private class FormalRepositoryWithoutStaging(
        private val delegate: InMemorySessions,
    ) : CompletionRejectionResettableResumableUploadSessionRepository by delegate,
        OwnerScopedResumableUploadSessionRepository {
        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, ownerId, sessionId)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
    }

    /** Deliberately violates the optional capability contract to prove the application rechecks every result. */
    private class BrokenOwnerScopedRepository(
        delegate: InMemorySessions,
        private val leaked: ResumableUploadSession,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession = leaked

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession = leaked
    }

    /** Simulates a tenant-global unique-key race after the remote multipart upload has begun. */
    private class UniqueKeyRaceRepository(
        private val delegate: InMemorySessions,
        private val competingOwnerId: String = "owner-B",
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        val saveFailure = IllegalStateException("simulated tenant idempotency uniqueness race")
        var competingSession: ResumableUploadSession? = null
            private set

        override fun save(session: ResumableUploadSession) {
            val competing = ResumableUploadSession(
                id = Identifier("competing-session"),
                tenantId = session.tenantId,
                idempotencyKey = session.idempotencyKey,
                storageUploadId = Identifier("competing-storage-upload"),
                storageLocation = StorageObjectLocation("test", "uploads/competing-session"),
                fileObjectId = Identifier("competing-file-object"),
                fileAssetId = Identifier("competing-file-asset"),
                fileName = session.fileName,
                contentLength = session.contentLength,
                assetType = session.assetType,
                contentType = session.contentType,
                expectedContentHash = session.expectedContentHash,
                metadata = session.metadata,
                expiresAt = session.expiresAt,
                createdTime = session.createdTime,
                updatedTime = session.updatedTime,
                ownerId = competingOwnerId,
            )
            competingSession = competing
            delegate.save(competing)
            throw saveFailure
        }

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, ownerId, sessionId)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
    }

    /** Models a write that became visible although the caller observed an exception. */
    private class CommitThenThrowRepository(
        private val delegate: InMemorySessions,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        private val commitFailure = IllegalStateException("simulated response failure after persistence")
        val saveFailure = ApplicationTransactionOutcomeUnknownException(commitFailure)
        var persistedSession: ResumableUploadSession? = null
            private set

        override fun save(session: ResumableUploadSession) {
            persistedSession = session
            delegate.save(session)
            throw saveFailure
        }

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, ownerId, sessionId)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
    }

    /** Models a commit acknowledgement failure with optionally unavailable reconciliation reads. */
    private class OutcomeUnknownSaveRepository(
        private val delegate: InMemorySessions,
        private val failReconciliationReads: Boolean,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        val commitFailure = IllegalStateException("simulated commit acknowledgement failure")
        val outcomeUnknown = ApplicationTransactionOutcomeUnknownException(commitFailure)
        private var saveAttempted = false

        override fun save(session: ResumableUploadSession) {
            saveAttempted = true
            throw outcomeUnknown
        }

        override fun findById(tenantId: Identifier, sessionId: Identifier): ResumableUploadSession? {
            requireReadable()
            return delegate.findById(tenantId, sessionId)
        }

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? {
            requireReadable()
            return delegate.findById(tenantId, ownerId, sessionId)
        }

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            idempotencyKey: String,
        ): ResumableUploadSession? {
            requireReadable()
            return delegate.findByIdempotencyKey(tenantId, idempotencyKey)
        }

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? {
            requireReadable()
            return delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
        }

        private fun requireReadable() {
            if (saveAttempted && failReconciliationReads) {
                throw IllegalStateException("simulated reconciliation read failure")
            }
        }
    }

    private enum class MisdirectedGlobalLookup {
        ID_WRONG_TENANT,
        ID_WRONG_IDENTIFIER,
        KEY_WRONG_TENANT,
        KEY_WRONG_VALUE,
    }

    /** A committed row is followed by a lost acknowledgement and one tenant-global lookup violates its key contract. */
    private class MisdirectedGlobalLookupRepository(
        private val delegate: InMemorySessions,
        private val misdirection: MisdirectedGlobalLookup,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        private val commitFailure = IllegalStateException("simulated save commit acknowledgement failure")
        val outcomeUnknown = ApplicationTransactionOutcomeUnknownException(commitFailure)
        private var saved = false

        override fun save(session: ResumableUploadSession) {
            delegate.save(session)
            saved = true
            throw outcomeUnknown
        }

        override fun findById(tenantId: Identifier, sessionId: Identifier): ResumableUploadSession? {
            val session = delegate.findById(tenantId, sessionId) ?: return null
            if (!saved) return session
            return when (misdirection) {
                MisdirectedGlobalLookup.ID_WRONG_TENANT ->
                    copySessionForTest(session, tenantId = Identifier("tenant-wrong"))
                MisdirectedGlobalLookup.ID_WRONG_IDENTIFIER ->
                    copySessionForTest(session, id = Identifier("session-wrong"))
                else -> session
            }
        }

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            idempotencyKey: String,
        ): ResumableUploadSession? {
            val session = delegate.findByIdempotencyKey(tenantId, idempotencyKey) ?: return null
            if (!saved) return session
            return when (misdirection) {
                MisdirectedGlobalLookup.KEY_WRONG_TENANT ->
                    copySessionForTest(session, tenantId = Identifier("tenant-wrong"))
                MisdirectedGlobalLookup.KEY_WRONG_VALUE ->
                    copySessionForTest(session, idempotencyKey = "request-wrong")
                else -> session
            }
        }

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, ownerId, sessionId)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
    }

    private enum class OwnerCapabilityFault {
        MISSING,
        WRONG_OWNER,
    }

    /** A durable save whose owner capability cannot authoritatively retrieve the committed row. */
    private class CommitThenOwnerCapabilityFaultRepository(
        private val delegate: InMemorySessions,
        private val fault: OwnerCapabilityFault,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        private val commitFailure = IllegalStateException("simulated owner-capability commit acknowledgement failure")
        private val outcomeUnknown = ApplicationTransactionOutcomeUnknownException(commitFailure)

        override fun save(session: ResumableUploadSession) {
            delegate.save(session)
            throw outcomeUnknown
        }

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = ownerCapabilityResult(delegate.findById(tenantId, sessionId))

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = ownerCapabilityResult(delegate.findByIdempotencyKey(tenantId, idempotencyKey))

        private fun ownerCapabilityResult(session: ResumableUploadSession?): ResumableUploadSession? = when (fault) {
            OwnerCapabilityFault.MISSING -> null
            OwnerCapabilityFault.WRONG_OWNER -> session?.let { copySessionForTest(it, ownerId = "owner-B") }
        }
    }

    /** Models an action/constraint failure that the transaction manager knows happened before commit. */
    private class RejectingSaveRepository(
        private val delegate: InMemorySessions,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        val saveFailure = IllegalStateException("simulated pre-commit constraint rejection")

        override fun save(session: ResumableUploadSession) {
            throw saveFailure
        }

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, ownerId, sessionId)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
    }

    /** Models a legacy repository/mapper that silently rewrites the additive owner field. */
    private class LegacyOwnerRewritingRepository(
        private val delegate: InMemorySessions,
        private val persistedOwnerId: String?,
    ) : StagedResumableUploadSessionRepository by delegate {
        override fun save(session: ResumableUploadSession) {
            delegate.save(copySession(session, persistedOwnerId))
        }

        private fun copySession(session: ResumableUploadSession, ownerId: String?) = ResumableUploadSession(
            id = session.id,
            tenantId = session.tenantId,
            idempotencyKey = session.idempotencyKey,
            storageUploadId = session.storageUploadId,
            storageLocation = session.storageLocation,
            fileObjectId = session.fileObjectId,
            fileAssetId = session.fileAssetId,
            fileName = session.fileName,
            contentLength = session.contentLength,
            assetType = session.assetType,
            contentType = session.contentType,
            expectedContentHash = session.expectedContentHash,
            metadata = session.metadata,
            status = session.status,
            expiresAt = session.expiresAt,
            lastError = session.lastError,
            completedAt = session.completedAt,
            createdTime = session.createdTime,
            updatedTime = session.updatedTime,
            ownerId = ownerId,
        )
    }

    /** Rewrites ownership and then loses the acknowledgement for the mandatory isolation-marker transition. */
    private class QuarantineMarkOutcomeRepository(
        private val delegate: InMemorySessions,
        private val persistMarkerBeforeFailure: Boolean,
    ) : StagedResumableUploadSessionRepository by delegate {
        private val markFailure = IllegalStateException("simulated quarantine marker commit acknowledgement failure")
        val markOutcomeUnknown = ApplicationTransactionOutcomeUnknownException(markFailure)

        override fun save(session: ResumableUploadSession) {
            delegate.save(copySessionForTest(session, ownerId = "owner-B"))
        }

        override fun markQuarantined(
            tenantId: Identifier,
            sessionId: Identifier,
            message: String,
            updatedAt: Long,
        ): Boolean {
            if (persistMarkerBeforeFailure) {
                delegate.markQuarantined(tenantId, sessionId, message, updatedAt)
            }
            throw markOutcomeUnknown
        }
    }

    /** Models a repository adapter whose write method returns without doing any work. */
    private class NoOpSaveRepository(
        private val delegate: InMemorySessions,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        override fun save(session: ResumableUploadSession) = Unit

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, ownerId, sessionId)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
    }

    /** Makes every authoritative read fail only after the save acknowledgement was returned. */
    private class PostSaveReadFailureRepository(
        private val delegate: InMemorySessions,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        val readFailure = IllegalStateException("simulated post-save reconciliation read failure")
        private var saved = false

        override fun save(session: ResumableUploadSession) {
            delegate.save(session)
            saved = true
        }

        override fun findById(tenantId: Identifier, sessionId: Identifier): ResumableUploadSession? {
            requireReadable()
            return delegate.findById(tenantId, sessionId)
        }

        override fun findByIdempotencyKey(tenantId: Identifier, idempotencyKey: String): ResumableUploadSession? {
            requireReadable()
            return delegate.findByIdempotencyKey(tenantId, idempotencyKey)
        }

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? {
            requireReadable()
            return delegate.findById(tenantId, ownerId, sessionId)
        }

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? {
            requireReadable()
            return delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
        }

        private fun requireReadable() {
            if (saved) throw readFailure
        }
    }

    /** Returns mutually inconsistent id and idempotency views after a successful write. */
    private class ContradictoryPostSaveRepository(
        private val delegate: InMemorySessions,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        private var saved = false

        override fun save(session: ResumableUploadSession) {
            delegate.save(session)
            saved = true
        }

        override fun findByIdempotencyKey(tenantId: Identifier, idempotencyKey: String): ResumableUploadSession? =
            if (saved) null else delegate.findByIdempotencyKey(tenantId, idempotencyKey)

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, ownerId, sessionId)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
    }

    /** Persists correctly but returns another owner's snapshot from its advertised capability. */
    private class WrongOwnerCapabilityRepository(
        private val delegate: InMemorySessions,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, sessionId)?.let(::withWrongOwner)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, idempotencyKey)?.let(::withWrongOwner)

        private fun withWrongOwner(session: ResumableUploadSession) = ResumableUploadSession(
            id = session.id,
            tenantId = session.tenantId,
            idempotencyKey = session.idempotencyKey,
            storageUploadId = session.storageUploadId,
            storageLocation = session.storageLocation,
            fileObjectId = session.fileObjectId,
            fileAssetId = session.fileAssetId,
            fileName = session.fileName,
            contentLength = session.contentLength,
            assetType = session.assetType,
            contentType = session.contentType,
            expectedContentHash = session.expectedContentHash,
            metadata = session.metadata,
            status = session.status,
            expiresAt = session.expiresAt,
            lastError = session.lastError,
            completedAt = session.completedAt,
            createdTime = session.createdTime,
            updatedTime = session.updatedTime,
            ownerId = "owner-B",
        )
    }

    private enum class MisdirectedClaim {
        COMPLETION,
        ABORT,
        CLEANUP,
    }

    /** Returns an unrelated snapshot without mutating, exposing whether validation runs inside execute(). */
    private class MisdirectedClaimRepository(
        private val delegate: InMemorySessions,
        private val mode: MisdirectedClaim,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, ownerId, sessionId)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)

        override fun claimForCompletion(
            tenantId: Identifier,
            sessionId: Identifier,
            now: Long,
        ): ResumableUploadSession? {
            if (mode != MisdirectedClaim.COMPLETION) return delegate.claimForCompletion(tenantId, sessionId, now)
            val current = delegate.findById(tenantId, sessionId) ?: return null
            return copySessionForTest(current, id = Identifier("misdirected-session")).let {
                copySessionWithStatusForTest(it, ResumableUploadSessionStatus.COMPLETING, now)
            }
        }

        override fun claimForAbort(
            tenantId: Identifier,
            sessionId: Identifier,
            updatedAt: Long,
        ): ResumableUploadSession? {
            if (mode == MisdirectedClaim.COMPLETION) return delegate.claimForAbort(tenantId, sessionId, updatedAt)
            val current = delegate.findById(tenantId, sessionId) ?: return null
            return copySessionForTest(current, id = Identifier("misdirected-session")).let {
                copySessionWithStatusForTest(it, ResumableUploadSessionStatus.ABORTING, updatedAt)
            }
        }
    }

    private class PausingWrongOwnerStagingRepository(
        private val delegate: InMemorySessions,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        val stagedSaved = CountDownLatch(1)
        val allowVerification = CountDownLatch(1)

        override fun save(session: ResumableUploadSession) {
            delegate.save(copySessionForTest(session, ownerId = "owner-B"))
            stagedSaved.countDown()
            check(allowVerification.await(5, TimeUnit.SECONDS)) { "Timed out waiting to verify the staged session." }
        }

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, ownerId, sessionId)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
    }

    private class FutureCleanupCandidateRepository(
        private val delegate: InMemorySessions,
        private val future: ResumableUploadSession,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        override fun findExpired(now: Long, limit: Int): List<ResumableUploadSession> = listOf(future)

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, ownerId, sessionId)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
    }

    private class ExpiringActivationRepository(
        private val delegate: InMemorySessions,
        private val clock: MutableClock,
    ) : OwnerScopedResumableUploadSessionRepository, StagedResumableUploadSessionRepository by delegate {
        val activationTimes = mutableListOf<Long>()
        private var advanced = false

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? = delegate.findById(tenantId, ownerId, sessionId)

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? = delegate.findByIdempotencyKey(tenantId, ownerId, idempotencyKey).also { session ->
            if (
                !advanced &&
                session?.status == ResumableUploadSessionStatus.ABORTING &&
                session.lastError == START_CREATION_STAGING_MARKER
            ) {
                advanced = true
                clock.advance(5)
            }
        }

        override fun activateStaged(
            tenantId: Identifier,
            sessionId: Identifier,
            expectedOwnerId: String,
            stagingMarker: String,
            activatedAt: Long,
        ): Boolean {
            activationTimes += activatedAt
            return delegate.activateStaged(tenantId, sessionId, expectedOwnerId, stagingMarker, activatedAt)
        }
    }

    private class InMemorySessions : OwnerScopedResumableUploadSessionRepository,
        StagedResumableUploadSessionRepository,
        CompletionRejectionResettableResumableUploadSessionRepository {
        private val sessions = linkedMapOf<Pair<String, String>, ResumableUploadSession>()
        private val parts = linkedMapOf<Pair<String, String>, MutableList<ResumableUploadPart>>()
        val mutationCalls = AtomicInteger()
        val queryCalls = AtomicInteger()
        val tenantGlobalIdempotencyQueryCalls = AtomicInteger()

        override fun save(session: ResumableUploadSession) {
            mutationCalls.incrementAndGet()
            sessions[session.tenantId.value to session.id.value] = session
        }

        override fun findById(tenantId: Identifier, sessionId: Identifier): ResumableUploadSession? {
            queryCalls.incrementAndGet()
            return sessions[tenantId.value to sessionId.value]
        }

        override fun findById(
            tenantId: Identifier,
            ownerId: String,
            sessionId: Identifier,
        ): ResumableUploadSession? {
            queryCalls.incrementAndGet()
            return sessions[tenantId.value to sessionId.value]?.takeIf { it.ownerId == ownerId }
        }

        override fun findByIdempotencyKey(tenantId: Identifier, idempotencyKey: String): ResumableUploadSession? {
            queryCalls.incrementAndGet()
            tenantGlobalIdempotencyQueryCalls.incrementAndGet()
            return sessions.values.firstOrNull { it.tenantId == tenantId && it.idempotencyKey == idempotencyKey }
        }

        override fun findByIdempotencyKey(
            tenantId: Identifier,
            ownerId: String,
            idempotencyKey: String,
        ): ResumableUploadSession? {
            queryCalls.incrementAndGet()
            return sessions.values.firstOrNull {
                it.tenantId == tenantId && it.ownerId == ownerId && it.idempotencyKey == idempotencyKey
            }
        }

        override fun findParts(tenantId: Identifier, sessionId: Identifier): List<ResumableUploadPart> =
            parts[tenantId.value to sessionId.value]?.sortedBy { it.partNumber } ?: emptyList()

        override fun savePart(part: ResumableUploadPart) {
            mutationCalls.incrementAndGet()
            val values = parts.getOrPut(part.tenantId.value to part.sessionId.value) { mutableListOf() }
            val index = values.indexOfFirst { it.partNumber == part.partNumber }
            if (index < 0) values += part else values[index] = part
        }

        override fun claimForCompletion(tenantId: Identifier, sessionId: Identifier, now: Long): ResumableUploadSession? {
            mutationCalls.incrementAndGet()
            val current = findById(tenantId, sessionId) ?: return null
            if (current.expiresAt <= now) return null
            return transition(tenantId, sessionId, now, setOf(ResumableUploadSessionStatus.ACTIVE), ResumableUploadSessionStatus.COMPLETING)
        }

        override fun reactivateAfterCompletionFailure(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long): Boolean =
            transitionMutation(
                tenantId,
                sessionId,
                updatedAt,
                setOf(ResumableUploadSessionStatus.COMPLETING),
                ResumableUploadSessionStatus.ACTIVE,
                message,
            ) != null

        override fun resetAfterCompletionRejection(
            tenantId: Identifier,
            sessionId: Identifier,
            message: String,
            expiresAt: Long,
            updatedAt: Long,
        ): Boolean {
            val reactivated = transitionMutation(
                tenantId,
                sessionId,
                updatedAt,
                setOf(ResumableUploadSessionStatus.COMPLETING),
                ResumableUploadSessionStatus.ACTIVE,
                message,
            ) ?: return false
            val key = reactivated.tenantId.value to reactivated.id.value
            sessions[key] = copySessionForTest(reactivated, expiresAt = expiresAt)
            parts.remove(key)
            return true
        }

        override fun markFailed(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long): Boolean {
            if (findById(tenantId, sessionId)?.lastError == START_CREATION_STAGING_MARKER) return false
            return transitionMutation(
                tenantId, sessionId, updatedAt,
                setOf(ResumableUploadSessionStatus.COMPLETING, ResumableUploadSessionStatus.ABORTING),
                ResumableUploadSessionStatus.FAILED,
                message,
            ) != null
        }

        override fun markQuarantined(
            tenantId: Identifier,
            sessionId: Identifier,
            message: String,
            updatedAt: Long,
        ): Boolean = transitionMutation(
            tenantId, sessionId, updatedAt,
            setOf(ResumableUploadSessionStatus.ABORTING),
            ResumableUploadSessionStatus.QUARANTINED,
            message,
        ) != null

        override fun activateStaged(
            tenantId: Identifier,
            sessionId: Identifier,
            expectedOwnerId: String,
            stagingMarker: String,
            activatedAt: Long,
        ): Boolean {
            val current = findById(tenantId, sessionId) ?: return false
            if (
                current.ownerId != expectedOwnerId ||
                current.status != ResumableUploadSessionStatus.ABORTING ||
                current.lastError != stagingMarker ||
                current.expiresAt <= activatedAt
            ) return false
            return transitionMutation(
                tenantId,
                sessionId,
                activatedAt,
                setOf(ResumableUploadSessionStatus.ABORTING),
                ResumableUploadSessionStatus.ACTIVE,
                error = null,
            ) != null
        }

        override fun markCompleted(tenantId: Identifier, sessionId: Identifier, completedAt: Long): Boolean =
            transitionMutation(
                tenantId, sessionId, completedAt, setOf(ResumableUploadSessionStatus.COMPLETING),
                ResumableUploadSessionStatus.COMPLETED,
                completedAt = completedAt,
            ) != null

        override fun claimForAbort(tenantId: Identifier, sessionId: Identifier, updatedAt: Long): ResumableUploadSession? {
            val current = findById(tenantId, sessionId) ?: return null
            return transitionMutation(
                tenantId, sessionId, updatedAt,
                setOf(ResumableUploadSessionStatus.ACTIVE, ResumableUploadSessionStatus.ABORTING, ResumableUploadSessionStatus.FAILED),
                ResumableUploadSessionStatus.ABORTING,
                error = current.lastError,
            )
        }

        override fun markAborted(tenantId: Identifier, sessionId: Identifier, expired: Boolean, updatedAt: Long): Boolean {
            val current = findById(tenantId, sessionId) ?: return false
            if (current.lastError == START_CREATION_STAGING_MARKER) return false
            return transitionMutation(
                tenantId, sessionId, updatedAt, setOf(ResumableUploadSessionStatus.ABORTING),
                if (expired) ResumableUploadSessionStatus.EXPIRED else ResumableUploadSessionStatus.ABORTED,
                error = current.lastError,
            ) != null
        }

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

        private fun transitionMutation(
            tenantId: Identifier,
            sessionId: Identifier,
            updatedAt: Long,
            expected: Set<ResumableUploadSessionStatus>,
            status: ResumableUploadSessionStatus,
            error: String? = null,
            completedAt: Long? = null,
        ): ResumableUploadSession? {
            mutationCalls.incrementAndGet()
            return transition(tenantId, sessionId, updatedAt, expected, status, error, completedAt)
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
            completedAt, value.createdTime, updatedAt, value.ownerId,
        )
    }

    private class InMemoryFileObjects : FileObjectRepository {
        private val values = linkedMapOf<Pair<String, String>, FileObject>()
        val saveCalls = AtomicInteger()
        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? = values[tenantId.value to fileObjectId.value]
        override fun save(fileObject: FileObject) {
            saveCalls.incrementAndGet()
            values[fileObject.tenantId.value to fileObject.id.value] = fileObject
        }
    }

    private class InMemoryFileAssets : FileAssetRepository {
        private val values = linkedMapOf<Pair<String, String>, FileAsset>()
        val saveCalls = AtomicInteger()
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? = values[tenantId.value to fileAssetId.value]
        override fun save(fileAsset: FileAsset) {
            saveCalls.incrementAndGet()
            values[fileAsset.tenantId.value to fileAsset.id.value] = fileAsset
        }
    }

    private class FakeMultipartStorage : StorageAdapter {
        private val uploadSequence = AtomicInteger()
        private val uploads = linkedMapOf<String, UploadState>()
        private val objects = linkedMapOf<StorageObjectLocation, StoredObject>()
        private val objectBytes = linkedMapOf<StorageObjectLocation, ByteArray>()
        val beginCalls = AtomicInteger()
        val uploadPartCalls = AtomicInteger()
        val completeCalls = AtomicInteger()
        val abortCalls = AtomicInteger()
        val deleteCalls = AtomicInteger()
        val aborted = mutableListOf<Identifier>()
        val deleted = mutableListOf<StorageObjectLocation>()
        var failComplete = false
        var rejectComplete = false
        var rejectCompleteMessage = MultipartCompletionRejectedException.DEFAULT_MESSAGE
        var beforeComplete: (() -> Unit)? = null
        var completeThenFail = false
        var failExists = false
        var failAbort = false
        var failDelete = false
        var trustDeclaredPartLength = false
        var completedLocationOverride: StorageObjectLocation? = null
        var completedLengthOverride: Long? = null
        var completedHashOverride: String? = null
        val existsFailure = IllegalStateException("storage existence check failed")
        val abortFailure = IllegalStateException("storage multipart abort failed")
        val deleteFailure = IllegalStateException("storage object delete failed")

        fun operationCalls(): Int =
            beginCalls.get() + uploadPartCalls.get() + completeCalls.get() + abortCalls.get() + deleteCalls.get()

        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload {
            beginCalls.incrementAndGet()
            val uploadId = Identifier("storage-${uploadSequence.incrementAndGet()}")
            val location = StorageObjectLocation("test", "uploads/${uploadId.value}")
            uploads[uploadId.value] = UploadState(location, request.contentType)
            return MultipartUpload(uploadId, location)
        }

        override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart {
            uploadPartCalls.incrementAndGet()
            val bytes = if (trustDeclaredPartLength) {
                val expected = contentLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val buffer = ByteArray(expected)
                var offset = 0
                while (offset < expected) {
                    val read = content.read(buffer, offset, expected - offset)
                    if (read < 0) break
                    offset += read
                }
                buffer.copyOf(offset)
            } else {
                content.readBytes().also { read -> require(read.size.toLong() == contentLength) }
            }
            uploads.getValue(upload.uploadId.value).parts[partNumber] = bytes
            return MultipartPart(partNumber, "etag-$partNumber-${bytes.size}")
        }

        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject {
            completeCalls.incrementAndGet()
            beforeComplete?.invoke()
            if (rejectComplete) throw MultipartCompletionRejectedException(rejectCompleteMessage)
            if (failComplete) throw IllegalStateException("storage completion failed")
            val state = uploads.getValue(upload.uploadId.value)
            val bytes = parts.sortedBy { it.partNumber }.flatMap { state.parts.getValue(it.partNumber).asIterable() }.toByteArray()
            val stored = StoredObject(
                completedLocationOverride ?: upload.location,
                completedLengthOverride ?: bytes.size.toLong(),
                state.contentType,
                completedHashOverride ?: "sha256:test-${bytes.size}",
            ).also {
                objects[it.location] = it
                objectBytes[it.location] = bytes
                uploads.remove(upload.uploadId.value)
            }
            if (completeThenFail) throw IllegalStateException("storage completion acknowledgement lost")
            return stored
        }

        override fun abortMultipartUpload(upload: MultipartUpload) {
            abortCalls.incrementAndGet()
            if (failAbort) throw abortFailure
            aborted += upload.uploadId
            uploads.remove(upload.uploadId.value)
        }

        override fun delete(location: StorageObjectLocation) {
            deleteCalls.incrementAndGet()
            if (failDelete) throw deleteFailure
            deleted += location
            objects.remove(location)
            objectBytes.remove(location)
        }

        override fun exists(location: StorageObjectLocation): Boolean {
            if (failExists) throw existsFailure
            return location in objects
        }

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject = error("Not used by resumable tests")
        override fun download(location: StorageObjectLocation): StorageDownload {
            val stored = objects.getValue(location)
            return StorageDownload(
                content = ByteArrayInputStream(objectBytes.getValue(location)),
                contentLength = stored.contentLength,
                contentType = stored.contentType,
            )
        }
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

    private class ClaimValidationObservingTransaction : ApplicationTransaction {
        var validationFailedInsideTransaction: Boolean = false
            private set

        override fun <T> execute(action: () -> T): T = try {
            action()
        } catch (failure: ResumableUploadNotFoundException) {
            validationFailedInsideTransaction = true
            throw failure
        }
    }
}

private fun copySessionForTest(
    session: ResumableUploadSession,
    id: Identifier = session.id,
    tenantId: Identifier = session.tenantId,
    idempotencyKey: String = session.idempotencyKey,
    expiresAt: Long = session.expiresAt,
    ownerId: String? = session.ownerId,
): ResumableUploadSession = ResumableUploadSession(
    id = id,
    tenantId = tenantId,
    idempotencyKey = idempotencyKey,
    storageUploadId = session.storageUploadId,
    storageLocation = session.storageLocation,
    fileObjectId = session.fileObjectId,
    fileAssetId = session.fileAssetId,
    fileName = session.fileName,
    contentLength = session.contentLength,
    assetType = session.assetType,
    contentType = session.contentType,
    expectedContentHash = session.expectedContentHash,
    metadata = session.metadata,
    status = session.status,
    expiresAt = expiresAt,
    lastError = session.lastError,
    completedAt = session.completedAt,
    createdTime = session.createdTime,
    updatedTime = session.updatedTime,
    ownerId = ownerId,
)

private fun copySessionWithStatusForTest(
    session: ResumableUploadSession,
    status: ResumableUploadSessionStatus,
    updatedTime: Long,
): ResumableUploadSession = ResumableUploadSession(
    id = session.id,
    tenantId = session.tenantId,
    idempotencyKey = session.idempotencyKey,
    storageUploadId = session.storageUploadId,
    storageLocation = session.storageLocation,
    fileObjectId = session.fileObjectId,
    fileAssetId = session.fileAssetId,
    fileName = session.fileName,
    contentLength = session.contentLength,
    assetType = session.assetType,
    contentType = session.contentType,
    expectedContentHash = session.expectedContentHash,
    metadata = session.metadata,
    status = status,
    expiresAt = session.expiresAt,
    lastError = session.lastError,
    createdTime = session.createdTime,
    updatedTime = updatedTime,
    ownerId = session.ownerId,
)
