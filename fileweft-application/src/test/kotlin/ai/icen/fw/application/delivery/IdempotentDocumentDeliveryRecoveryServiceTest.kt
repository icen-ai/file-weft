package ai.icen.fw.application.delivery

import ai.icen.fw.application.idempotency.IdempotencyKeyConflictException
import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.IdempotencyStoreException
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyClaim
import ai.icen.fw.application.idempotency.RequestIdempotencyRecord
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.idempotency.RequestIdempotencyStatus
import ai.icen.fw.application.outbox.OutboxEventMutationRepository
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.outbox.OutboxEventState
import ai.icen.fw.application.outbox.OutboxEventStatus
import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.delivery.DeliveryRequirement
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdempotentDocumentDeliveryRecoveryServiceTest {
    @Test
    fun `fresh delivery retry advances one fence and replay creates no second event`() {
        val fixture = fixture(target = failedDelivery(sequence = 3))

        val fresh = fixture.service.retryDelivery(DOCUMENT_ID, DELIVERY_ID, "retry-key")
        val replay = fixture.service.retryDelivery(DOCUMENT_ID, DELIVERY_ID, "retry-key")

        assertReceipt(fresh, DocumentDeliveryRecoveryOperation.DELIVERY)
        assertReceipt(replay, DocumentDeliveryRecoveryOperation.DELIVERY)
        val current = fixture.deliveries.current(DELIVERY_ID)
        assertEquals(DocumentDeliveryStatus.PENDING, current.status)
        assertEquals(DeliveryDispatchOperation.DELIVERY, current.currentDispatchFence?.operation)
        assertEquals(4, current.currentDispatchFence?.sequence)
        assertEquals("event-1", current.currentDispatchFence?.eventId?.value)
        assertEquals(1, fixture.outbox.events.size)
        assertEquals(DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE, fixture.outbox.events.single().type)
        assertEquals(DOCUMENT_ID.value, fixture.outbox.events.single().payload[DocumentDeliveryPlanner.DOCUMENT_ID_PAYLOAD_KEY])
        assertEquals(DELIVERY_ID.value, fixture.outbox.events.single().payload[DocumentDeliveryPlanner.DELIVERY_ID_PAYLOAD_KEY])
        assertEquals(1, fixture.idempotency.recordCount)
        assertEquals(2, fixture.authorization.requests.size)
        assertEquals(1, fixture.deliveries.mutationReads)
        assertEquals(1, fixture.outbox.mutationReads)
    }

    @Test
    fun `same key cannot change from delivery retry to removal retry`() {
        val fixture = fixture(target = failedDelivery())
        fixture.service.retryDelivery(DOCUMENT_ID, DELIVERY_ID, "bound-key")

        assertFailsWith<IdempotencyKeyConflictException> {
            fixture.service.retryRemoval(DOCUMENT_ID, DELIVERY_ID, "bound-key")
        }

        assertEquals(1, fixture.outbox.events.size)
        assertEquals(1, fixture.idempotency.recordCount)
        assertEquals(DeliveryDispatchOperation.DELIVERY, fixture.deliveries.current(DELIVERY_ID).currentDispatchFence?.operation)
    }

    @Test
    fun `current outbox must exist and already be failed before requeue`() {
        val running = fixture(
            target = failedDelivery(),
            currentEventStatus = OutboxEventStatus.RUNNING,
        )
        assertFailsWith<DocumentDeliveryRecoveryConflictException> {
            running.service.retryDelivery(DOCUMENT_ID, DELIVERY_ID, "running-event")
        }
        assertRolledBack(running)

        val missing = fixture(target = failedDelivery(), includeCurrentEvent = false)
        assertFailsWith<IdempotencyStoreException> {
            missing.service.retryDelivery(DOCUMENT_ID, DELIVERY_ID, "missing-event")
        }
        assertRolledBack(missing)
    }

    @Test
    fun `current outbox identity tenant and event type must exactly match the dispatch fence`() {
        listOf(
            OutboxEventState(
                Identifier("event-other"),
                TENANT_ID,
                OutboxEventStatus.FAILED,
                null,
                null,
                DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE,
            ),
            OutboxEventState(
                OLD_EVENT_ID,
                Identifier("tenant-other"),
                OutboxEventStatus.FAILED,
                null,
                null,
                DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE,
            ),
            OutboxEventState(
                OLD_EVENT_ID,
                TENANT_ID,
                OutboxEventStatus.FAILED,
                null,
                null,
                DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE,
            ),
        ).forEachIndexed { index, inconsistent ->
            val fixture = fixture(target = failedDelivery())
            fixture.outbox.forcedMutationState = inconsistent

            assertFailsWith<IdempotencyStoreException> {
                fixture.service.retryDelivery(DOCUMENT_ID, DELIVERY_ID, "inconsistent-$index")
            }
            assertRolledBack(fixture)
        }
    }

    @Test
    fun `failed outbox can recover an active target stranded before terminal projection`() {
        val delivery = fixture(
            target = target(
                status = DocumentDeliveryStatus.RETRYING,
                removalStatus = DocumentDeliveryRemovalStatus.NOT_REQUESTED,
            ).restoreDispatch(DeliveryDispatchFence(OLD_EVENT_ID, DeliveryDispatchOperation.DELIVERY, 4)),
        )

        delivery.service.retryDelivery(DOCUMENT_ID, DELIVERY_ID, "stranded-delivery")

        assertEquals(DocumentDeliveryStatus.PENDING, delivery.deliveries.current(DELIVERY_ID).status)
        assertEquals(5, delivery.deliveries.current(DELIVERY_ID).currentDispatchFence?.sequence)
        assertEquals(1, delivery.outbox.events.size)

        val removal = fixture(
            target = target(
                status = DocumentDeliveryStatus.SUCCEEDED,
                removalStatus = DocumentDeliveryRemovalStatus.RETRYING,
                externalId = "external-1",
            ).restoreDispatch(DeliveryDispatchFence(OLD_EVENT_ID, DeliveryDispatchOperation.REMOVAL, 6)),
        )

        removal.service.retryRemoval(DOCUMENT_ID, DELIVERY_ID, "stranded-removal")

        assertEquals(DocumentDeliveryRemovalStatus.PENDING, removal.deliveries.current(DELIVERY_ID).removalStatus)
        assertEquals(7, removal.deliveries.current(DELIVERY_ID).currentDispatchFence?.sequence)
        assertEquals(1, removal.outbox.events.size)
    }

    @Test
    fun `historical generation and a delivery bound to another document fail closed`() {
        val historical = fixture(target = failedDelivery(generation = 0))
        assertFailsWith<DocumentDeliveryRecoveryConflictException> {
            historical.service.retryDelivery(DOCUMENT_ID, DELIVERY_ID, "historical")
        }
        assertRolledBack(historical)

        val wrongDocument = fixture(target = failedDelivery(documentId = Identifier("document-other")))
        assertFailsWith<NoSuchElementException> {
            wrongDocument.service.retryDelivery(DOCUMENT_ID, DELIVERY_ID, "wrong-document")
        }
        assertRolledBack(wrongDocument)
    }

    @Test
    fun `removal retry advances only the removal lifecycle and writes its own event type`() {
        val fixture = fixture(target = failedRemoval(sequence = 7))

        val receipt = fixture.service.retryRemoval(DOCUMENT_ID, DELIVERY_ID, "removal-key")

        assertReceipt(receipt, DocumentDeliveryRecoveryOperation.REMOVAL)
        val current = fixture.deliveries.current(DELIVERY_ID)
        assertEquals(DocumentDeliveryStatus.SUCCEEDED, current.status)
        assertEquals(DocumentDeliveryRemovalStatus.PENDING, current.removalStatus)
        assertEquals(DeliveryDispatchOperation.REMOVAL, current.currentDispatchFence?.operation)
        assertEquals(8, current.currentDispatchFence?.sequence)
        assertEquals(DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE, fixture.outbox.events.single().type)
    }

    @Test
    fun `denied user cannot probe idempotency target or outbox state`() {
        val fixture = fixture(target = failedDelivery(), authorizationAllowed = false)

        assertFailsWith<ApplicationForbiddenException> {
            fixture.service.retryDelivery(DOCUMENT_ID, DELIVERY_ID, "forbidden")
        }

        assertEquals(1, fixture.authorization.requests.size)
        assertEquals(0, fixture.idempotency.findCalls)
        assertEquals(0, fixture.deliveries.mutationReads)
        assertEquals(0, fixture.outbox.mutationReads)
        assertTrue(fixture.outbox.events.isEmpty())
    }

    private fun assertReceipt(
        receipt: DocumentDeliveryRecoveryReceipt,
        operation: DocumentDeliveryRecoveryOperation,
    ) {
        assertEquals(DOCUMENT_ID, receipt.documentId)
        assertEquals(DELIVERY_ID, receipt.deliveryId)
        assertEquals(operation, receipt.operation)
    }

    private fun assertRolledBack(fixture: Fixture) {
        assertTrue(fixture.outbox.events.isEmpty())
        assertEquals(0, fixture.idempotency.recordCount)
        val current = fixture.deliveries.current(DELIVERY_ID)
        assertEquals(OLD_EVENT_ID, current.currentDispatchFence?.eventId)
        assertEquals(DocumentDeliveryStatus.FAILED, current.status)
    }

    private fun fixture(
        target: DocumentDeliveryTarget,
        document: Document = document(),
        currentEventStatus: OutboxEventStatus = OutboxEventStatus.FAILED,
        includeCurrentEvent: Boolean = true,
        authorizationAllowed: Boolean = true,
    ): Fixture {
        val transaction = SnapshotTransaction()
        val documents = MemoryDocuments(transaction, document).also(transaction::register)
        val deliveries = MemoryDeliveries(transaction, target).also(transaction::register)
        val outbox = MemoryOutbox(transaction).also(transaction::register)
        if (includeCurrentEvent) {
            outbox.putState(
                OutboxEventState(
                    OLD_EVENT_ID,
                    TENANT_ID,
                    currentEventStatus,
                    if (currentEventStatus == OutboxEventStatus.RUNNING) "worker-current" else null,
                    if (currentEventStatus == OutboxEventStatus.RUNNING) "token-current" else null,
                    when (target.currentDispatchFence?.operation) {
                        DeliveryDispatchOperation.DELIVERY -> DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE
                        DeliveryDispatchOperation.REMOVAL ->
                            DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE
                        null -> null
                    },
                ),
            )
        }
        val idempotencyRepository = MemoryIdempotencyRepository(transaction).also(transaction::register)
        val authorization = RecordingAuthorization(authorizationAllowed)
        val idempotency = RequestIdempotencyService(
            idempotencyRepository,
            transaction,
            PrefixIds("idempotency"),
            CLOCK,
        )
        val service = IdempotentDocumentDeliveryRecoveryService(
            tenants = FixedTenant,
            users = FixedUsers,
            authorization = authorization,
            documents = documents,
            deliveries = deliveries,
            outboxMutations = outbox,
            outbox = outbox,
            identifiers = PrefixIds("event"),
            clock = CLOCK,
            idempotency = idempotency,
        )
        return Fixture(service, documents, deliveries, outbox, idempotencyRepository, authorization)
    }

    private data class Fixture(
        val service: IdempotentDocumentDeliveryRecoveryService,
        val documents: MemoryDocuments,
        val deliveries: MemoryDeliveries,
        val outbox: MemoryOutbox,
        val idempotency: MemoryIdempotencyRepository,
        val authorization: RecordingAuthorization,
    )

    private interface TransactionParticipant {
        fun rollbackAction(): () -> Unit
    }

    private class SnapshotTransaction : ApplicationTransaction {
        private val participants = mutableListOf<TransactionParticipant>()
        var active: Boolean = false
            private set

        fun register(participant: TransactionParticipant) {
            participants += participant
        }

        fun requireActive() = check(active) { "Repository work must run in the shared application transaction." }

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested application transactions are not expected in this fixture." }
            val rollbacks = participants.map { it.rollbackAction() }
            active = true
            return try {
                action()
            } catch (failure: Throwable) {
                rollbacks.asReversed().forEach { rollback -> rollback() }
                throw failure
            } finally {
                active = false
            }
        }
    }

    private class MemoryDocuments(
        private val transaction: SnapshotTransaction,
        document: Document,
    ) : DocumentRepository, TransactionParticipant {
        private var stored = document.copyDocument()
        var mutationReads: Int = 0
            private set

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            stored.takeIf { it.tenantId == tenantId && it.id == documentId }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            transaction.requireActive()
            mutationReads++
            return findById(tenantId, documentId)
        }

        override fun save(document: Document) {
            transaction.requireActive()
            stored = document
        }

        override fun rollbackAction(): () -> Unit {
            val snapshot = stored.copyDocument()
            val reads = mutationReads
            return {
                stored = snapshot.copyDocument()
                mutationReads = reads
            }
        }
    }

    private class MemoryDeliveries(
        private val transaction: SnapshotTransaction,
        target: DocumentDeliveryTarget,
    ) : DocumentDeliveryTargetMutationRepository, TransactionParticipant {
        private val targets = linkedMapOf(target.id.value to target.copyTarget())
        var mutationReads: Int = 0
            private set

        override fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? =
            targets[deliveryId.value]?.takeIf { it.tenantId == tenantId }

        override fun findForMutation(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? {
            transaction.requireActive()
            mutationReads++
            return findById(tenantId, deliveryId)
        }

        override fun findByDocument(tenantId: Identifier, documentId: Identifier): List<DocumentDeliveryTarget> =
            targets.values.filter { it.tenantId == tenantId && it.documentId == documentId }

        override fun save(target: DocumentDeliveryTarget) {
            transaction.requireActive()
            targets[target.id.value] = target
        }

        fun current(id: Identifier): DocumentDeliveryTarget = requireNotNull(targets[id.value])

        override fun rollbackAction(): () -> Unit {
            val snapshot = targets.mapValues { (_, value) -> value.copyTarget() }
            val reads = mutationReads
            return {
                targets.clear()
                snapshot.forEach { (id, value) -> targets[id] = value.copyTarget() }
                mutationReads = reads
            }
        }
    }

    private class MemoryOutbox(
        private val transaction: SnapshotTransaction,
    ) : OutboxEventRepository, OutboxEventMutationRepository, TransactionParticipant {
        val events = mutableListOf<OutboxEvent>()
        private val states = linkedMapOf<String, OutboxEventState>()
        var forcedMutationState: OutboxEventState? = null
        var mutationReads: Int = 0
            private set

        fun putState(state: OutboxEventState) {
            states[key(state.tenantId, state.id)] = state
        }

        override fun findForMutation(tenantId: Identifier, eventId: Identifier): OutboxEventState? {
            transaction.requireActive()
            mutationReads++
            return forcedMutationState ?: states[key(tenantId, eventId)]
        }

        override fun append(event: OutboxEvent) {
            transaction.requireActive()
            events += event
            putState(OutboxEventState(event.id, event.tenantId, OutboxEventStatus.PENDING, null, null, event.type))
        }

        override fun rollbackAction(): () -> Unit {
            val eventSnapshot = events.toList()
            val stateSnapshot = LinkedHashMap(states)
            val reads = mutationReads
            return {
                events.clear()
                events.addAll(eventSnapshot)
                states.clear()
                states.putAll(stateSnapshot)
                mutationReads = reads
            }
        }

        private fun key(tenantId: Identifier, eventId: Identifier): String = "${tenantId.value}\u0000${eventId.value}"
    }

    private class MemoryIdempotencyRepository(
        private val transaction: SnapshotTransaction,
    ) : RequestIdempotencyRepository, TransactionParticipant {
        private val records = linkedMapOf<String, RequestIdempotencyRecord>()
        var findCalls: Int = 0
            private set

        val recordCount: Int
            get() = records.size

        override fun findByKeyDigest(tenantId: Identifier, keyDigest: String): RequestIdempotencyRecord? {
            transaction.requireActive()
            findCalls++
            return records[key(tenantId, keyDigest)]
        }

        override fun claim(
            request: RequestIdempotency,
            newRecordId: Identifier,
            now: Long,
        ): RequestIdempotencyClaim {
            transaction.requireActive()
            records[key(request.tenantId, request.keyDigest)]?.let { existing ->
                return RequestIdempotencyClaim(existing, false)
            }
            val created = RequestIdempotencyRecord(
                id = newRecordId,
                tenantId = request.tenantId,
                keyDigest = request.keyDigest,
                operatorId = request.operatorId,
                action = request.action,
                resourceType = request.resourceType,
                resourceId = request.resourceId,
                subresourceId = request.subresourceId,
                requestFingerprint = request.requestFingerprint,
                status = RequestIdempotencyStatus.IN_PROGRESS,
                result = null,
                completedTime = null,
                createdTime = now,
                updatedTime = now,
            )
            records[key(request.tenantId, request.keyDigest)] = created
            return RequestIdempotencyClaim(created, true)
        }

        override fun complete(
            recordId: Identifier,
            tenantId: Identifier,
            keyDigest: String,
            result: IdempotencyResult,
            completedAt: Long,
        ): RequestIdempotencyRecord {
            transaction.requireActive()
            val current = records[key(tenantId, keyDigest)]
                ?: throw IdempotencyStoreException("Idempotency record is missing.")
            if (current.id != recordId || current.status != RequestIdempotencyStatus.IN_PROGRESS) {
                throw IdempotencyStoreException("Idempotency record cannot be completed.")
            }
            return RequestIdempotencyRecord(
                id = current.id,
                tenantId = current.tenantId,
                keyDigest = current.keyDigest,
                operatorId = current.operatorId,
                action = current.action,
                resourceType = current.resourceType,
                resourceId = current.resourceId,
                subresourceId = current.subresourceId,
                requestFingerprint = current.requestFingerprint,
                status = RequestIdempotencyStatus.COMPLETED,
                result = result,
                completedTime = completedAt,
                createdTime = current.createdTime,
                updatedTime = completedAt,
            ).also { completed -> records[key(tenantId, keyDigest)] = completed }
        }

        override fun rollbackAction(): () -> Unit {
            val snapshot = LinkedHashMap(records)
            val reads = findCalls
            return {
                records.clear()
                records.putAll(snapshot)
                findCalls = reads
            }
        }

        private fun key(tenantId: Identifier, digest: String): String = "${tenantId.value}\u0000$digest"
    }

    private class RecordingAuthorization(private val allowed: Boolean) : AuthorizationProvider {
        val requests = mutableListOf<AuthorizationRequest>()

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            return AuthorizationDecision(allowed, if (allowed) null else "denied")
        }
    }

    private class PrefixIds(private val prefix: String) : IdentifierGenerator {
        private var sequence = 0
        override fun nextId(): Identifier = Identifier("$prefix-${++sequence}")
    }

    private fun document(): Document = Document(
        id = DOCUMENT_ID,
        tenantId = TENANT_ID,
        assetId = Identifier("asset-1"),
        documentNumber = "DOC-1",
        title = "Delivery recovery",
        lifecycleState = LifecycleState.SYNC_ERROR,
        deliveryGeneration = 1,
    )

    private fun failedDelivery(
        documentId: Identifier = DOCUMENT_ID,
        generation: Int = 1,
        sequence: Long = 1,
    ): DocumentDeliveryTarget = target(
        documentId = documentId,
        generation = generation,
        status = DocumentDeliveryStatus.FAILED,
        removalStatus = DocumentDeliveryRemovalStatus.NOT_REQUESTED,
    ).restoreDispatch(DeliveryDispatchFence(OLD_EVENT_ID, DeliveryDispatchOperation.DELIVERY, sequence))

    private fun failedRemoval(sequence: Long = 1): DocumentDeliveryTarget = target(
        status = DocumentDeliveryStatus.SUCCEEDED,
        removalStatus = DocumentDeliveryRemovalStatus.FAILED,
        externalId = "external-1",
    ).restoreDispatch(DeliveryDispatchFence(OLD_EVENT_ID, DeliveryDispatchOperation.REMOVAL, sequence))

    private fun target(
        documentId: Identifier = DOCUMENT_ID,
        generation: Int = 1,
        status: DocumentDeliveryStatus,
        removalStatus: DocumentDeliveryRemovalStatus,
        externalId: String? = null,
    ) = DocumentDeliveryTarget(
        id = DELIVERY_ID,
        tenantId = TENANT_ID,
        documentId = documentId,
        profileId = "profile-1",
        targetId = "archive",
        displayName = "Archive",
        connectorId = "archive-connector",
        requirement = DeliveryRequirement.REQUIRED,
        status = status,
        externalId = externalId,
        errorMessage = if (status == DocumentDeliveryStatus.FAILED) "delivery failed" else null,
        removalStatus = removalStatus,
        removalErrorMessage = if (removalStatus == DocumentDeliveryRemovalStatus.FAILED) "removal failed" else null,
        deliveryGeneration = generation,
    )

    private object FixedTenant : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
    }

    private object FixedUsers : UserRealmProvider {
        override fun currentUser(): UserIdentity = UserIdentity(Identifier("operator-1"), "恢复管理员")
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val DOCUMENT_ID = Identifier("document-1")
        val DELIVERY_ID = Identifier("delivery-1")
        val OLD_EVENT_ID = Identifier("event-old")
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    }
}

private fun Document.copyDocument(): Document = Document(
    id = id,
    tenantId = tenantId,
    assetId = assetId,
    documentNumber = documentNumber,
    title = title,
    lifecycleState = lifecycleState,
    versions = versions.map { version ->
        DocumentVersion(
            version.id,
            version.tenantId,
            version.documentId,
            version.versionNumber,
            version.fileObjectId,
        )
    },
    currentVersionId = currentVersionId,
    deliveryGeneration = deliveryGeneration,
)

private fun DocumentDeliveryTarget.copyTarget(): DocumentDeliveryTarget = DocumentDeliveryTarget(
    id = id,
    tenantId = tenantId,
    documentId = documentId,
    profileId = profileId,
    targetId = targetId,
    displayName = displayName,
    connectorId = connectorId,
    requirement = requirement,
    ownerRef = ownerRef,
    status = status,
    externalId = externalId,
    errorMessage = errorMessage,
    retryCount = retryCount,
    removalStatus = removalStatus,
    removalErrorMessage = removalErrorMessage,
    removalRetryCount = removalRetryCount,
    deliveryGeneration = deliveryGeneration,
).also { copy ->
    currentDispatchFence?.let { fence ->
        copy.restoreDispatch(DeliveryDispatchFence(fence.eventId, fence.operation, fence.sequence))
    }
}
