package ai.icen.fw.application.retention

import ai.icen.fw.application.outbox.OutboxEventLease
import ai.icen.fw.application.outbox.OutboxEventMutationRepository
import ai.icen.fw.application.outbox.OutboxEventState
import ai.icen.fw.application.outbox.OutboxEventStatus
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.retention.DeletionAuditEvidence
import ai.icen.fw.domain.retention.DeletionAuthorizationSnapshot
import ai.icen.fw.domain.retention.LegalHoldSetSnapshot
import ai.icen.fw.domain.retention.RetentionDeletionDecisionEngine
import ai.icen.fw.domain.retention.RetentionPolicyMode
import ai.icen.fw.domain.retention.RetentionPolicySnapshot
import ai.icen.fw.domain.retention.SecureDeletionPlan
import ai.icen.fw.domain.retention.SecureDeletionRequest
import ai.icen.fw.spi.event.OutboxHandlingStatus
import ai.icen.fw.spi.retention.SecureDeletionProvider
import ai.icen.fw.spi.retention.SecureDeletionProviderReceipt
import ai.icen.fw.spi.retention.SecureDeletionProviderRequest
import ai.icen.fw.spi.retention.SecureDeletionProviderStatus
import ai.icen.fw.spi.retention.SecureDeletionTarget
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SecureDeletionOrchestrationTest {
    @Test
    fun `commits tombstone decision evidence and outbox atomically and collapses replay`() {
        val transaction = TrackingTransaction()
        val repository = RecordingRepository(transaction)
        val outbox = RecordingOutbox(transaction)
        val service = service(repository, outbox, transaction)

        val first = service.request(request())
        val replay = service.request(request())

        assertTrue(first.isAllowed())
        assertTrue(replay.isAllowed())
        assertEquals(2, transaction.calls)
        assertEquals(listOf("create", "outbox", "create", "read"), repositoryAndOutboxEvents(repository, outbox))
        assertEquals(1, outbox.events.size)
        val event = outbox.events.single()
        assertEquals(SecureDeletionApplicationService.dispatchEventId(TENANT, Identifier("plan-1")), event.id)
        assertEquals("7", event.payload[SecureDeletionApplicationService.RESOURCE_REVISION_PAYLOAD_KEY])
        assertNotNull(repository.execution)
    }

    @Test
    fun `dispatch identity is deterministic and tenant bound`() {
        val planId = Identifier("shared-plan")
        val first = SecureDeletionApplicationService.dispatchEventId(TENANT, planId)

        assertEquals(first, SecureDeletionApplicationService.dispatchEventId(TENANT, planId))
        assertFalse(first == SecureDeletionApplicationService.dispatchEventId(Identifier("tenant-2"), planId))
        assertEquals(64, first.value.length)
    }

    @Test
    fun `leased handler invokes providers outside transactions in fixed order and stores verified receipts`() {
        val fixture = fixture(
            RecordingProvider("index", SecureDeletionTarget.INDEX),
            RecordingProvider("objects", SecureDeletionTarget.OBJECT_STORAGE),
        )

        val result = fixture.handler.handle(fixture.lease)

        assertEquals(OutboxHandlingStatus.SUCCEEDED, result.status)
        assertEquals(listOf(SecureDeletionTarget.INDEX, SecureDeletionTarget.OBJECT_STORAGE), fixture.providerCalls)
        assertTrue(fixture.providerTransactionStates.all { active -> !active })
        assertEquals(SecureDeletionExecutionStatus.SUCCEEDED, fixture.repository.execution?.status)
        assertEquals(2, fixture.repository.execution?.receipts?.size)
        assertEquals(1, fixture.repository.completions.size)
        assertTrue(fixture.repository.completions.single().receipts.all { it.providerReceipt.isVerifiedAbsent() })
    }

    @Test
    fun `accepted provider receipt is reconciled before the plan advances`() {
        val index = RecordingProvider(
            "index",
            SecureDeletionTarget.INDEX,
            firstStatus = SecureDeletionProviderStatus.ACCEPTED_UNVERIFIED,
        )
        val fixture = fixture(index, RecordingProvider("objects", SecureDeletionTarget.OBJECT_STORAGE))

        val accepted = fixture.handler.handle(fixture.lease)
        val reconciled = fixture.handler.handle(fixture.lease)

        assertEquals(OutboxHandlingStatus.RETRYABLE_FAILURE, accepted.status)
        assertEquals(OutboxHandlingStatus.SUCCEEDED, reconciled.status)
        assertEquals(1, index.requestCalls)
        assertEquals(1, index.reconcileCalls)
        assertEquals(SecureDeletionExecutionStatus.SUCCEEDED, fixture.repository.execution?.status)
    }

    @Test
    fun `revision mismatch fails before any external provider invocation`() {
        val fixture = fixture(
            RecordingProvider("index", SecureDeletionTarget.INDEX),
            RecordingProvider("objects", SecureDeletionTarget.OBJECT_STORAGE),
        )
        val wrongEvent = OutboxEvent(
            fixture.lease.event.id,
            TENANT,
            fixture.lease.event.type,
            fixture.lease.event.payload + (SecureDeletionApplicationService.RESOURCE_REVISION_PAYLOAD_KEY to "8"),
            fixture.lease.event.timestamp,
        )
        val wrongLease = OutboxEventLease(wrongEvent, 0, OWNER, TOKEN)

        val result = fixture.handler.handle(wrongLease)

        assertEquals(OutboxHandlingStatus.PERMANENT_FAILURE, result.status)
        assertTrue(fixture.providerCalls.isEmpty())
        assertEquals(SecureDeletionExecutionStatus.PENDING, fixture.repository.execution?.status)
    }

    @Test
    fun `provider receipt for another request can never advance deletion`() {
        val fixture = fixture(
            RecordingProvider("index", SecureDeletionTarget.INDEX, tamperRequestBinding = true),
            RecordingProvider("objects", SecureDeletionTarget.OBJECT_STORAGE),
        )

        val result = fixture.handler.handle(fixture.lease)

        assertEquals(OutboxHandlingStatus.PERMANENT_FAILURE, result.status)
        assertEquals(listOf(SecureDeletionTarget.INDEX), fixture.providerCalls)
        assertEquals(SecureDeletionExecutionStatus.FAILED, fixture.repository.execution?.status)
        assertTrue(fixture.repository.execution?.receipts?.isEmpty() == true)
        assertEquals(1, fixture.repository.failures.size)
    }

    private fun fixture(
        index: RecordingProvider,
        objects: RecordingProvider,
    ): Fixture {
        val transaction = TrackingTransaction()
        val repository = RecordingRepository(transaction)
        val outbox = RecordingOutbox(transaction)
        service(repository, outbox, transaction).request(request())
        val event = outbox.events.single()
        val lease = OutboxEventLease(event, 0, OWNER, TOKEN)
        val calls = mutableListOf<SecureDeletionTarget>()
        val states = mutableListOf<Boolean>()
        index.beforeCall = { calls += index.target(); states += transaction.active }
        objects.beforeCall = { calls += objects.target(); states += transaction.active }
        val mutations = object : OutboxEventMutationRepository {
            override fun findForMutation(tenantId: Identifier, eventId: Identifier): OutboxEventState? =
                OutboxEventState(event.id, event.tenantId, OutboxEventStatus.RUNNING, OWNER, TOKEN, event.type)
        }
        return Fixture(
            repository,
            SecureDeletionOutboxEventHandler(repository, mutations, transaction, listOf(index, objects), fixedClock()),
            lease,
            calls,
            states,
        )
    }

    private fun service(
        repository: SecureDeletionRepository,
        outbox: OutboxEventRepository,
        transaction: ApplicationTransaction,
    ) = SecureDeletionApplicationService(
        RetentionDeletionDecisionEngine(fixedClock()),
        repository,
        outbox,
        transaction,
    )

    private fun request() = SecureDeletionRequest(
        decisionEvidenceId = Identifier("decision-1"),
        planId = Identifier("plan-1"),
        tombstoneId = Identifier("tombstone-1"),
        tenantId = TENANT,
        resourceType = "DOCUMENT",
        resourceId = DOCUMENT,
        resourceRevision = 7,
        requestedBy = OPERATOR,
        policy = RetentionPolicySnapshot(
            TENANT, "DOCUMENT", DOCUMENT, "records", "policy-r1",
            RetentionPolicyMode.RETAIN_UNTIL, 0, 900, 2_000, 900,
        ),
        legalHolds = LegalHoldSetSnapshot(
            TENANT, "DOCUMENT", DOCUMENT, "holds-r1", 900, 2_000, true, emptyList(),
        ),
        authorization = DeletionAuthorizationSnapshot(
            TENANT, "DOCUMENT", DOCUMENT, OPERATOR, "auth-r1", 900, 2_000, true, true,
        ),
    )

    private fun fixedClock() = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

    private fun repositoryAndOutboxEvents(
        repository: RecordingRepository,
        outbox: RecordingOutbox,
    ): List<String> = (repository.events + outbox.eventsRecorded).sortedBy { it.second }.map { it.first }

    private class TrackingTransaction : ApplicationTransaction {
        var active = false
            private set
        var calls = 0
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested test transaction." }
            calls++
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private class RecordingRepository(
        private val transaction: TrackingTransaction,
    ) : SecureDeletionRepository {
        var execution: SecureDeletionExecution? = null
        val decisions = mutableListOf<DeletionAuditEvidence>()
        val completions = mutableListOf<SecureDeletionCompletionEvidence>()
        val failures = mutableListOf<SecureDeletionFailureEvidence>()
        val events = mutableListOf<Pair<String, Int>>()
        private var sequence = 0

        override fun createIfAbsent(plan: SecureDeletionPlan, dispatchEventId: Identifier): Boolean {
            check(transaction.active)
            events += "create" to sequence++
            if (execution != null) return false
            execution = SecureDeletionExecution.pending(plan, dispatchEventId)
            decisions += plan.decisionAuditEvidence
            return true
        }

        override fun appendDecisionAuditIfAbsent(evidence: DeletionAuditEvidence): Boolean {
            check(transaction.active)
            if (decisions.any { it.id == evidence.id }) return false
            decisions += evidence
            return true
        }

        override fun findByPlanId(tenantId: Identifier, planId: Identifier): SecureDeletionExecution? {
            check(transaction.active)
            events += "read" to sequence++
            return execution?.takeIf { it.tenantId == tenantId && it.planId == planId }
        }

        override fun findForMutation(tenantId: Identifier, planId: Identifier): SecureDeletionExecution? {
            check(transaction.active)
            return execution?.takeIf { it.tenantId == tenantId && it.planId == planId }
        }

        override fun save(execution: SecureDeletionExecution) {
            check(transaction.active)
            this.execution = execution
        }

        override fun appendCompletionEvidenceIfAbsent(evidence: SecureDeletionCompletionEvidence): Boolean {
            check(transaction.active)
            if (completions.any { it.id == evidence.id }) return false
            completions += evidence
            return true
        }

        override fun appendFailureEvidenceIfAbsent(evidence: SecureDeletionFailureEvidence): Boolean {
            check(transaction.active)
            if (failures.any { it.id == evidence.id }) return false
            failures += evidence
            return true
        }
    }

    private class RecordingOutbox(
        private val transaction: TrackingTransaction,
    ) : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()
        val eventsRecorded = mutableListOf<Pair<String, Int>>()
        private var sequence = 0

        override fun append(event: OutboxEvent) {
            check(transaction.active)
            events += event
            eventsRecorded += "outbox" to sequence++
        }
    }

    private class RecordingProvider(
        private val id: String,
        private val deletionTarget: SecureDeletionTarget,
        private val firstStatus: SecureDeletionProviderStatus = SecureDeletionProviderStatus.VERIFIED_ABSENT,
        private val tamperRequestBinding: Boolean = false,
    ) : SecureDeletionProvider {
        var requestCalls = 0
            private set
        var reconcileCalls = 0
            private set
        var beforeCall: (() -> Unit)? = null

        override fun providerId(): String = id
        override fun target(): SecureDeletionTarget = deletionTarget

        override fun requestDeletion(request: SecureDeletionProviderRequest): SecureDeletionProviderReceipt {
            beforeCall?.invoke()
            requestCalls++
            assertEquals(deletionTarget, request.target)
            return receipt(request, firstStatus, "receipt-$id-request")
        }

        override fun reconcileDeletion(
            request: SecureDeletionProviderRequest,
            previousReceipt: SecureDeletionProviderReceipt,
        ): SecureDeletionProviderReceipt {
            beforeCall?.invoke()
            reconcileCalls++
            assertEquals(SecureDeletionProviderStatus.ACCEPTED_UNVERIFIED, previousReceipt.status)
            assertEquals(request.bindingDigest, previousReceipt.requestBindingDigest)
            return receipt(request, SecureDeletionProviderStatus.VERIFIED_ABSENT, "receipt-$id-reconciled")
        }

        private fun receipt(
            request: SecureDeletionProviderRequest,
            status: SecureDeletionProviderStatus,
            reference: String,
        ) = SecureDeletionProviderReceipt(
            id,
            deletionTarget,
            status,
            if (tamperRequestBinding) "0".repeat(64) else request.bindingDigest,
            reference,
            status.name,
        )
    }

    private class Fixture(
        val repository: RecordingRepository,
        val handler: SecureDeletionOutboxEventHandler,
        val lease: OutboxEventLease,
        val providerCalls: MutableList<SecureDeletionTarget>,
        val providerTransactionStates: MutableList<Boolean>,
    )

    private companion object {
        val TENANT = Identifier("tenant-1")
        val DOCUMENT = Identifier("document-1")
        val OPERATOR = Identifier("operator-1")
        const val OWNER = "worker-1"
        const val TOKEN = "lease-1"
    }
}
