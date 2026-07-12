package com.fileweft.application.lifecycle

import com.fileweft.application.archive.ArchiveDocumentService
import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.catalog.DocumentLifecycleMutationGuard
import com.fileweft.application.catalog.DocumentLifecycleMutationPermit
import com.fileweft.application.delivery.DocumentDeliveryPlanner
import com.fileweft.application.delivery.DocumentDeliveryTarget
import com.fileweft.application.delivery.DocumentDeliveryTargetRepository
import com.fileweft.application.document.DocumentCommandService
import com.fileweft.application.idempotency.IdempotencyKeyConflictException
import com.fileweft.application.idempotency.IdempotencyResult
import com.fileweft.application.idempotency.IdempotencyStoreException
import com.fileweft.application.idempotency.RequestIdempotency
import com.fileweft.application.idempotency.RequestIdempotencyClaim
import com.fileweft.application.idempotency.RequestIdempotencyRecord
import com.fileweft.application.idempotency.RequestIdempotencyRepository
import com.fileweft.application.idempotency.RequestIdempotencyService
import com.fileweft.application.idempotency.RequestIdempotencyStatus
import com.fileweft.application.idempotency.RequestFingerprint
import com.fileweft.application.offline.OfflineDocumentService
import com.fileweft.application.offline.RestoreOfflineDocumentService
import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.application.publish.PublishDocumentService
import com.fileweft.application.security.ApplicationForbiddenException
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.document.LifecycleState
import com.fileweft.domain.workflow.WorkflowInstance
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.ConnectorRemoveRequest
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncResult
import com.fileweft.spi.connector.ConnectorSyncStatus
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.delivery.DeliveryConnectorResolver
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.delivery.DocumentDeliveryProfile
import com.fileweft.spi.delivery.DocumentDeliveryProfileProvider
import com.fileweft.spi.delivery.DocumentDeliveryTargetDefinition
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdempotentDocumentLifecycleServiceTest {
    @Test
    fun `replays every simple lifecycle action without repeating mutation audit or delivery`() {
        val scenarios = listOf(
            Scenario("revise", ::rejectedDocument, LifecycleState.DRAFT) { service, id, key -> service.revise(id, key) },
            Scenario("publish", ::pendingDocument, LifecycleState.PUBLISHING) { service, id, key -> service.publish(id, key) },
            Scenario("offline", ::publishedDocument, LifecycleState.OFFLINE) { service, id, key -> service.offline(id, key) },
            Scenario("restore", ::offlineDocument, LifecycleState.DRAFT) { service, id, key -> service.restore(id, key) },
            Scenario("archive", ::publishedDocument, LifecycleState.HISTORY) { service, id, key -> service.archive(id, key) },
        )

        scenarios.forEach { scenario ->
            val fixture = Fixture(scenario.document())
            val key = "${scenario.name}-key"

            val first = scenario.invoke(fixture.service, fixture.document.id, key)
            val second = scenario.invoke(fixture.service, fixture.document.id, key)

            assertEquals(fixture.document.id, first.documentId, scenario.name)
            assertEquals(first.documentId, second.documentId, scenario.name)
            assertNull(first.workflowId, scenario.name)
            assertEquals(scenario.expectedState, fixture.document.lifecycleState, scenario.name)
            assertEquals(1, fixture.documents.saveCalls, scenario.name)
            assertEquals(1, fixture.audits.records.size, scenario.name)
            assertEquals(1, fixture.idempotency.claimCalls, scenario.name)
            assertEquals(1, fixture.idempotency.completeCalls, scenario.name)
            assertEquals(2, fixture.users.currentUserCalls, scenario.name)
            assertEquals(2, fixture.authorization.calls, scenario.name)
            assertEquals(1, fixture.transaction.maxDepth, scenario.name)
            if (scenario.name == "publish") {
                assertEquals(1, fixture.profileCalls, scenario.name)
                assertEquals(1, fixture.deliveries.saved.size, scenario.name)
                assertEquals(1, fixture.outbox.events.size, scenario.name)
            } else {
                assertEquals(0, fixture.profileCalls, scenario.name)
            }
        }
    }

    @Test
    fun `rejects authorization before reading idempotency or document state`() {
        val fixture = Fixture(rejectedDocument(), authorizationAllowed = false)

        assertThrows<ApplicationForbiddenException> {
            fixture.service.revise(fixture.document.id, "denied-key")
        }

        assertEquals(0, fixture.idempotency.findCalls)
        assertEquals(0, fixture.idempotency.claimCalls)
        assertEquals(0, fixture.documents.mutationReads)
        assertEquals(0, fixture.documents.saveCalls)
        assertEquals(1, fixture.users.currentUserCalls)
    }

    @Test
    fun `binds one tenant key to one action before later domain state checks`() {
        val fixture = Fixture(rejectedDocument())
        fixture.service.revise(fixture.document.id, "cross-action-key")

        assertThrows<IdempotencyKeyConflictException> {
            fixture.service.publish(fixture.document.id, "cross-action-key")
        }

        assertEquals(LifecycleState.DRAFT, fixture.document.lifecycleState)
        assertEquals(1, fixture.documents.saveCalls)
        assertEquals(0, fixture.profileCalls)
        assertEquals(1, fixture.audits.records.size)
    }

    @Test
    fun `fails closed when a stored receipt points at another document`() {
        val fixture = Fixture(rejectedDocument())
        fixture.service.revise(fixture.document.id, "corrupt-result-key")
        fixture.idempotency.replaceResult(
            IdempotencyResult("DOCUMENT", Identifier("document-other")),
        )

        assertThrows<IdempotencyStoreException> {
            fixture.service.revise(fixture.document.id, "corrupt-result-key")
        }

        assertEquals(1, fixture.documents.saveCalls)
        assertEquals(1, fixture.audits.records.size)
    }

    @Test
    fun `catalog guard is checked before replay and revoked access blocks it`() {
        val fixture = Fixture(rejectedDocument())
        val guard = RecordingGuard(fixture.transaction)
        val guarded = fixture.guarded(guard)

        guarded.revise(fixture.document.id, "guarded-key")
        val readsAfterFirst = fixture.idempotency.findCalls
        guard.denied = true

        assertThrows<ApplicationForbiddenException> {
            guarded.revise(fixture.document.id, "guarded-key")
        }

        assertEquals(2, guard.prepareCalls)
        assertEquals(1, guard.revalidateCalls)
        assertEquals(1, guard.verifyCalls)
        assertEquals(listOf(Identifier("operator-1"), Identifier("operator-1")), guard.prepareOperators)
        assertEquals(listOf(Identifier("operator-1")), guard.revalidateOperators)
        assertEquals(readsAfterFirst, fixture.idempotency.findCalls)
        assertEquals(1, fixture.documents.saveCalls)
    }

    @Test
    fun `publish keeps policy resolution outside transactions and completes after all side effects`() {
        val fixture = Fixture(pendingDocument())

        fixture.service.publish(fixture.document.id, "publish-order-key")

        assertTrue(fixture.profileTransactionStates.isNotEmpty())
        assertTrue(fixture.profileTransactionStates.all { active -> !active })
        assertEquals(1, fixture.transaction.maxDepth)
        assertOrder(fixture.events, "idem:claim", "document:lock")
        assertOrder(fixture.events, "document:save", "delivery:save")
        assertOrder(fixture.events, "delivery:save", "outbox:append")
        assertOrder(fixture.events, "outbox:append", "audit:append")
        assertOrder(fixture.events, "audit:append", "idem:complete")
    }

    @Test
    fun `publish normalizes an explicit profile and replay does not resolve or deliver twice`() {
        val fixture = Fixture(pendingDocument())

        val first = fixture.service.publish(fixture.document.id, "  regulated  ", "profile-key")
        val replay = fixture.service.publish(fixture.document.id, "regulated", "profile-key")

        assertEquals(first.documentId, replay.documentId)
        assertEquals("regulated", fixture.deliveries.saved.single().profileId)
        assertEquals(1, fixture.profileCalls)
        assertEquals(1, fixture.deliveries.saved.size)
        assertEquals(1, fixture.outbox.events.size)
        assertEquals(1, fixture.documents.saveCalls)
        assertEquals(1, fixture.idempotency.claimCalls)
        assertEquals(1, fixture.idempotency.completeCalls)
    }

    @Test
    fun `blank publish profile shares the default fingerprint while another profile conflicts before policy resolution`() {
        val fixture = Fixture(pendingDocument())

        fixture.service.publish(fixture.document.id, "default-profile-key")
        val replay = fixture.service.publish(fixture.document.id, "   ", "default-profile-key")

        assertEquals(fixture.document.id, replay.documentId)
        assertEquals("default", fixture.deliveries.saved.single().profileId)
        assertEquals(1, fixture.profileCalls)

        assertThrows<IdempotencyKeyConflictException> {
            fixture.service.publish(fixture.document.id, "regulated", "default-profile-key")
        }
        assertEquals(1, fixture.profileCalls)
        assertEquals(1, fixture.deliveries.saved.size)
        assertEquals(1, fixture.outbox.events.size)
    }

    @Test
    fun `default publish replays a completed record created with the legacy fingerprint`() {
        val fixture = Fixture(pendingDocument())
        fixture.seedLegacyDefaultPublish("legacy-publish-key")

        val replay = fixture.service.publish(fixture.document.id, "legacy-publish-key")

        assertEquals(fixture.document.id, replay.documentId)
        assertEquals(1, fixture.idempotency.findCalls)
        assertEquals(0, fixture.idempotency.claimCalls)
        assertEquals(0, fixture.idempotency.completeCalls)
        assertEquals(0, fixture.profileCalls)
        assertEquals(0, fixture.documents.saveCalls)
        assertEquals(0, fixture.deliveries.saved.size)
        assertEquals(0, fixture.outbox.events.size)
        assertEquals(0, fixture.audits.records.size)
    }

    @Test
    fun `publish accepts the bounded profile limit and rejects unsafe or oversized profiles before authorization`() {
        val maximumProfileId = "p".repeat(256)
        val bounded = Fixture(pendingDocument())

        bounded.service.publish(bounded.document.id, maximumProfileId, "bounded-profile-key")

        assertEquals(maximumProfileId, bounded.deliveries.saved.single().profileId)

        listOf("p".repeat(257), "regulated\nprofile", "regulated\u200Eprofile").forEach { invalidProfile ->
            val fixture = Fixture(pendingDocument())
            assertThrows<IllegalArgumentException> {
                fixture.service.publish(fixture.document.id, invalidProfile, "invalid-profile-key")
            }
            assertEquals(0, fixture.users.currentUserCalls)
            assertEquals(0, fixture.authorization.calls)
            assertEquals(0, fixture.idempotency.findCalls)
            assertEquals(0, fixture.profileCalls)
            assertEquals(0, fixture.documents.saveCalls)
        }
    }

    private fun assertOrder(events: List<String>, first: String, second: String) {
        val firstIndex = events.indexOf(first)
        val secondIndex = events.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing event $first in $events")
        assertTrue(secondIndex > firstIndex, "Expected $first before $second in $events")
    }

    private class Fixture(
        val document: Document,
        authorizationAllowed: Boolean = true,
    ) {
        val events = mutableListOf<String>()
        val transaction = TrackingTransaction(events)
        val users = RecordingUsers()
        val authorization = RecordingAuthorization(authorizationAllowed, events)
        val documents = RecordingDocuments(document, transaction, events)
        val audits = RecordingAudits(transaction, events)
        val outbox = RecordingOutbox(transaction, events)
        val deliveries = RecordingDeliveries(transaction, events)
        val profileTransactionStates = mutableListOf<Boolean>()
        var profileCalls: Int = 0
            private set
        val idempotency = RecordingIdempotencyRepository(transaction, events)

        private val auditTrail = AuditTrail(
            audits,
            IncrementingIdentifierGenerator("audit"),
            FIXED_CLOCK,
        )
        private val deliveryPlanner = DocumentDeliveryPlanner(
            profiles = object : DocumentDeliveryProfileProvider {
                override fun listProfiles(tenantId: Identifier): List<DocumentDeliveryProfile> {
                    profileCalls += 1
                    profileTransactionStates += transaction.active
                    events += "delivery:profile"
                    return listOf(
                        deliveryProfile("default", "Default", "default-primary"),
                        deliveryProfile("regulated", "Regulated", "regulated-primary"),
                        deliveryProfile("p".repeat(256), "Bounded", "bounded-primary"),
                    )
                }
            },
            connectors = object : DeliveryConnectorResolver {
                override fun findConnector(connectorId: String): FileConnector? {
                    profileTransactionStates += transaction.active
                    events += "delivery:connector"
                    return SuccessfulConnector
                }
            },
            deliveries = deliveries,
            outbox = outbox,
            identifiers = IncrementingIdentifierGenerator("delivery"),
            clock = FIXED_CLOCK,
        )
        private val commands = DocumentCommandService(
            tenantProvider(), users, authorization, documents, transaction, auditTrail,
        )
        private val publish = PublishDocumentService(
            tenantProvider(), users, authorization, documents, deliveryPlanner, transaction, auditTrail, EmptyWorkflows,
        )
        private val offline = OfflineDocumentService(
            tenantProvider(), users, authorization, documents, transaction, auditTrail,
        )
        private val restore = RestoreOfflineDocumentService(
            tenantProvider(), users, authorization, documents, deliveries, transaction, auditTrail,
        )
        private val archive = ArchiveDocumentService(
            tenantProvider(), users, authorization, documents, transaction, auditTrail,
        )
        private val idempotencyService = RequestIdempotencyService(
            idempotency,
            transaction,
            IncrementingIdentifierGenerator("idem"),
            FIXED_CLOCK,
        )

        val service = IdempotentDocumentLifecycleService(
            commands,
            publish,
            offline,
            restore,
            archive,
            idempotencyService,
        )

        fun guarded(guard: DocumentLifecycleMutationGuard): IdempotentDocumentLifecycleDelegate =
            IdempotentDocumentLifecycleDelegate(
                commands,
                publish,
                offline,
                restore,
                archive,
                idempotencyService,
                guard,
            )

        fun seedLegacyDefaultPublish(idempotencyKey: String) {
            val request = RequestIdempotency.create(
                tenantId = TENANT_ID,
                operatorId = Identifier("operator-1"),
                idempotencyKey = idempotencyKey,
                action = "document:publish",
                resourceType = "DOCUMENT",
                resourceId = document.id,
                requestFingerprint = RequestFingerprint.sha256("fileweft:lifecycle:publish:v1"),
            )
            transaction.execute {
                idempotency.seedCompleted(
                    request,
                    IdempotencyResult("DOCUMENT", document.id),
                )
            }
        }

        private fun deliveryProfile(
            id: String,
            displayName: String,
            targetId: String,
        ): DocumentDeliveryProfile = DocumentDeliveryProfile(
            id,
            displayName,
            listOf(
                DocumentDeliveryTargetDefinition(
                    targetId,
                    displayName,
                    "connector-1",
                    DeliveryRequirement.REQUIRED,
                ),
            ),
        )

        private fun tenantProvider() = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
        }
    }

    private class RecordingUsers : UserRealmProvider {
        var currentUserCalls: Int = 0

        override fun currentUser(): UserIdentity {
            currentUserCalls += 1
            return UserIdentity(Identifier("operator-1"), "Operator")
        }

        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private class RecordingAuthorization(
        private val allowed: Boolean,
        private val events: MutableList<String>,
    ) : AuthorizationProvider {
        var calls: Int = 0

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            calls += 1
            events += "authorization"
            return AuthorizationDecision(allowed)
        }
    }

    private class TrackingTransaction(
        private val events: MutableList<String>,
    ) : ApplicationTransaction {
        private var depth = 0
        var maxDepth: Int = 0
            private set
        val active: Boolean get() = depth > 0

        override fun <T> execute(action: () -> T): T {
            depth += 1
            maxDepth = maxOf(maxDepth, depth)
            events += "tx:start"
            return try {
                action().also { events += "tx:commit" }
            } catch (failure: Throwable) {
                events += "tx:rollback"
                throw failure
            } finally {
                depth -= 1
            }
        }
    }

    private class RecordingDocuments(
        private val document: Document,
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : DocumentRepository {
        var mutationReads: Int = 0
        var saveCalls: Int = 0

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? {
            check(transaction.active)
            events += "document:read"
            return document.takeIf { it.tenantId == tenantId && it.id == documentId }
        }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            check(transaction.active)
            mutationReads += 1
            events += "document:lock"
            return document.takeIf { it.tenantId == tenantId && it.id == documentId }
        }

        override fun save(document: Document) {
            check(transaction.active)
            saveCalls += 1
            events += "document:save"
        }
    }

    private class RecordingAudits(
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()

        override fun append(record: AuditRecord) {
            check(transaction.active)
            records += record
            events += "audit:append"
        }

        override fun findByResource(
            tenantId: Identifier,
            resourceType: String,
            resourceId: Identifier,
            limit: Int,
        ): List<AuditRecord> = emptyList()
    }

    private class RecordingOutbox(
        private val transaction: TrackingTransaction,
        private val eventLog: MutableList<String>,
    ) : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()

        override fun append(event: OutboxEvent) {
            check(transaction.active)
            events += event
            eventLog += "outbox:append"
        }
    }

    private class RecordingDeliveries(
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : DocumentDeliveryTargetRepository {
        val saved = mutableListOf<DocumentDeliveryTarget>()

        override fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? =
            saved.firstOrNull { it.tenantId == tenantId && it.id == deliveryId }

        override fun findByDocument(tenantId: Identifier, documentId: Identifier): List<DocumentDeliveryTarget> =
            saved.filter { it.tenantId == tenantId && it.documentId == documentId }

        override fun save(target: DocumentDeliveryTarget) {
            check(transaction.active)
            saved += target
            events += "delivery:save"
        }
    }

    private class RecordingIdempotencyRepository(
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : RequestIdempotencyRepository {
        private var record: RequestIdempotencyRecord? = null
        var findCalls: Int = 0
            private set
        var claimCalls: Int = 0
            private set
        var completeCalls: Int = 0
            private set

        override fun findByKeyDigest(tenantId: Identifier, keyDigest: String): RequestIdempotencyRecord? {
            check(transaction.active)
            findCalls += 1
            events += "idem:find"
            return record?.takeIf { it.tenantId == tenantId && it.keyDigest == keyDigest }
        }

        override fun claim(
            request: RequestIdempotency,
            newRecordId: Identifier,
            now: Long,
        ): RequestIdempotencyClaim {
            check(transaction.active)
            claimCalls += 1
            events += "idem:claim"
            record?.let { return RequestIdempotencyClaim(it, acquired = false) }
            val claimed = RequestIdempotencyRecord(
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
            record = claimed
            return RequestIdempotencyClaim(claimed, acquired = true)
        }

        override fun complete(
            recordId: Identifier,
            tenantId: Identifier,
            keyDigest: String,
            result: IdempotencyResult,
            completedAt: Long,
        ): RequestIdempotencyRecord {
            check(transaction.active)
            completeCalls += 1
            events += "idem:complete"
            val current = requireNotNull(record)
            val completed = RequestIdempotencyRecord(
                id = recordId,
                tenantId = tenantId,
                keyDigest = keyDigest,
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
            )
            record = completed
            return completed
        }

        fun replaceResult(result: IdempotencyResult) {
            val current = requireNotNull(record)
            record = RequestIdempotencyRecord(
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
                completedTime = current.completedTime,
                createdTime = current.createdTime,
                updatedTime = current.updatedTime,
            )
        }

        fun seedCompleted(request: RequestIdempotency, result: IdempotencyResult) {
            check(transaction.active)
            record = RequestIdempotencyRecord(
                id = Identifier("idem-legacy"),
                tenantId = request.tenantId,
                keyDigest = request.keyDigest,
                operatorId = request.operatorId,
                action = request.action,
                resourceType = request.resourceType,
                resourceId = request.resourceId,
                subresourceId = request.subresourceId,
                requestFingerprint = request.requestFingerprint,
                status = RequestIdempotencyStatus.COMPLETED,
                result = result,
                completedTime = 100,
                createdTime = 100,
                updatedTime = 100,
            )
        }
    }

    private class RecordingGuard(
        private val transaction: TrackingTransaction,
    ) : DocumentLifecycleMutationGuard {
        var denied: Boolean = false
        var prepareCalls: Int = 0
        var revalidateCalls: Int = 0
        var verifyCalls: Int = 0
        val prepareOperators = mutableListOf<Identifier>()
        val revalidateOperators = mutableListOf<Identifier>()

        override fun prepareLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            actionName: String,
        ): DocumentLifecycleMutationPermit {
            check(!transaction.active)
            prepareCalls += 1
            prepareOperators += operator.id
            if (denied) throw ApplicationForbiddenException()
            return Permit
        }

        override fun revalidateLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            permit: DocumentLifecycleMutationPermit,
        ) {
            check(!transaction.active)
            revalidateCalls += 1
            revalidateOperators += operator.id
        }

        override fun verifyLifecycleLocked(
            tenantId: Identifier,
            document: Document,
            permit: DocumentLifecycleMutationPermit,
        ) {
            check(transaction.active)
            verifyCalls += 1
        }

        private object Permit : DocumentLifecycleMutationPermit
    }

    private class IncrementingIdentifierGenerator(
        private val prefix: String,
    ) : IdentifierGenerator {
        private var sequence = 0
        override fun nextId(): Identifier = Identifier("$prefix-${++sequence}")
    }

    private data class Scenario(
        val name: String,
        val document: () -> Document,
        val expectedState: LifecycleState,
        val invoke: (IdempotentDocumentLifecycleService, Identifier, String) -> DocumentLifecycleReceipt,
    )

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)

        val EmptyWorkflows = object : WorkflowInstanceRepository {
            override fun findById(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? = null
            override fun findForDecision(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? = null
            override fun findActiveByDocument(tenantId: Identifier, documentId: Identifier): WorkflowInstance? = null
            override fun save(workflow: WorkflowInstance) = Unit
        }

        val SuccessfulConnector = object : FileConnector {
            override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult =
                ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

            override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult =
                ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

            override fun health(): ConnectorHealth = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
        }
    }
}

private fun draftDocument(): Document = Document(
    id = Identifier("document-1"),
    tenantId = Identifier("tenant-1"),
    assetId = Identifier("asset-1"),
    documentNumber = "DOC-001",
    title = "Document",
).also { document ->
    document.addVersion(
        DocumentVersion(
            id = Identifier("version-1"),
            tenantId = document.tenantId,
            documentId = document.id,
            versionNumber = "1.0",
            fileObjectId = Identifier("file-1"),
        ),
    )
}

private fun pendingDocument(): Document = draftDocument().also { it.transition(LifecycleCommand.SUBMIT) }

private fun rejectedDocument(): Document = pendingDocument().also { it.transition(LifecycleCommand.REJECT) }

private fun publishedDocument(): Document = pendingDocument().also {
    it.transition(LifecycleCommand.APPROVE)
    it.transition(LifecycleCommand.PUBLISH_SUCCEEDED)
}

private fun offlineDocument(): Document = publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) }
