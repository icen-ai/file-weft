package ai.icen.fw.application.workflow

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentLifecycleMutationGuard
import ai.icen.fw.application.catalog.DocumentLifecycleMutationPermit
import ai.icen.fw.application.delivery.DocumentDeliveryPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryTarget
import ai.icen.fw.application.delivery.DocumentDeliveryTargetRepository
import ai.icen.fw.application.idempotency.IdempotencyKeyConflictException
import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.IdempotencyStoreException
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyClaim
import ai.icen.fw.application.idempotency.RequestIdempotencyRecord
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.idempotency.RequestIdempotencyStatus
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.audit.AuditRecord
import ai.icen.fw.domain.audit.AuditRecordRepository
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.document.LifecycleCommand
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.domain.workflow.WorkflowInstance
import ai.icen.fw.domain.workflow.WorkflowInstanceRepository
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTask
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.delivery.DeliveryConnectorResolver
import ai.icen.fw.spi.delivery.DeliveryRequirement
import ai.icen.fw.spi.delivery.DocumentDeliveryProfile
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import ai.icen.fw.spi.delivery.DocumentDeliveryTargetDefinition
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.workflow.DocumentReviewRoute
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest
import ai.icen.fw.spi.workflow.DocumentReviewRouteTask
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdempotentDocumentReviewWorkflowServiceTest {
    @Test
    fun `submit fresh and replay write workflow audit and route only once`() {
        val fixture = Fixture(draftDocument())

        val first = fixture.service.submitForReview(
            fixture.document.id,
            PRIMARY_USER.id,
            ROUTE_ID,
            "submit-key",
        )
        val replay = fixture.service.submitForReview(
            fixture.document.id,
            PRIMARY_USER.id,
            ROUTE_ID,
            "submit-key",
        )

        assertEquals(first.documentId, replay.documentId)
        assertEquals(first.workflowId, replay.workflowId)
        assertNull(replay.taskId)
        assertEquals(LifecycleState.PENDING_REVIEW, fixture.document.lifecycleState)
        assertEquals(1, fixture.documents.saveCalls)
        assertEquals(1, fixture.workflows.saveCalls)
        assertEquals(1, fixture.audits.records.size)
        assertEquals(1, fixture.route.calls)
        assertEquals(1, fixture.idempotency.claimCalls)
        assertEquals(1, fixture.idempotency.completeCalls)
        assertEquals(2, fixture.users.currentUserCalls)
        assertEquals(2, fixture.authorizationRequests.size)
    }

    @Test
    fun `approve fresh and replay publish delivery audit and decision only once`() {
        val workflow = pendingWorkflow(pendingDocument().id, listOf(PRIMARY_USER.id))
        val fixture = Fixture(pendingDocument(), workflow)

        val first = fixture.service.approve(workflow.id, workflow.tasks.single().id, "ok", PROFILE_ID, "approve-key")
        val replay = fixture.service.approve(workflow.id, workflow.tasks.single().id, "ok", PROFILE_ID, "approve-key")

        assertEquals(first.documentId, replay.documentId)
        assertEquals(workflow.id, replay.workflowId)
        assertEquals(workflow.tasks.single().id, replay.taskId)
        assertEquals(LifecycleState.PUBLISHING, fixture.document.lifecycleState)
        assertEquals(WorkflowState.APPROVED, fixture.workflows.workflow?.state)
        assertEquals(1, fixture.documents.saveCalls)
        assertEquals(1, fixture.workflows.saveCalls)
        assertEquals(1, fixture.deliveries.saved.size)
        assertEquals(1, fixture.outbox.events.size)
        assertEquals(1, fixture.audits.records.size)
        assertEquals(1, fixture.profileCalls)
        assertEquals(1, fixture.idempotency.claimCalls)
        assertEquals(1, fixture.idempotency.completeCalls)
    }

    @Test
    fun `reject fresh and replay reject workflow and audit only once`() {
        val document = pendingDocument()
        val workflow = pendingWorkflow(document.id, listOf(PRIMARY_USER.id))
        val fixture = Fixture(document, workflow)

        val first = fixture.service.reject(workflow.id, workflow.tasks.single().id, "revise", "reject-key")
        val replay = fixture.service.reject(workflow.id, workflow.tasks.single().id, "revise", "reject-key")

        assertEquals(first.documentId, replay.documentId)
        assertEquals(workflow.id, replay.workflowId)
        assertEquals(workflow.tasks.single().id, replay.taskId)
        assertEquals(LifecycleState.REJECTED, fixture.document.lifecycleState)
        assertEquals(WorkflowState.REJECTED, fixture.workflows.workflow?.state)
        assertEquals(1, fixture.documents.saveCalls)
        assertEquals(1, fixture.workflows.saveCalls)
        assertEquals(1, fixture.audits.records.size)
        assertEquals(0, fixture.profileCalls)
        assertEquals(emptyList(), fixture.outbox.events)
        assertEquals(1, fixture.idempotency.claimCalls)
        assertEquals(1, fixture.idempotency.completeCalls)
    }

    @Test
    fun `guarded replay reauthorizes and checks acl before skipping route profile and mutation locks`() {
        val submit = Fixture(draftDocument())
        submit.guarded.submitForReview(submit.document.id, PRIMARY_USER.id, ROUTE_ID, "guarded-submit")
        submit.events.clear()

        submit.guarded.submitForReview(submit.document.id, PRIMARY_USER.id, ROUTE_ID, "guarded-submit")

        assertEquals(listOf("user", "authorization", "guard:prepare"), submit.events.take(3))
        assertTrue("idem:find" in submit.events)
        assertTrue("route:resolve" !in submit.events)
        assertTrue("document:lock" !in submit.events)
        assertTrue("asset:lock" !in submit.events)
        assertTrue("workflow:active" !in submit.events)
        assertEquals(2, submit.guard.prepareCalls)
        assertEquals(1, submit.guard.revalidateCalls)
        assertEquals(1, submit.guard.verifyCalls)

        val document = pendingDocument()
        val workflow = pendingWorkflow(document.id, listOf(PRIMARY_USER.id))
        val approve = Fixture(document, workflow)
        approve.guarded.approve(workflow.id, workflow.tasks.single().id, "ok", PROFILE_ID, "guarded-approve")
        approve.events.clear()

        approve.guarded.approve(workflow.id, workflow.tasks.single().id, "ok", PROFILE_ID, "guarded-approve")

        assertTrue("authorization" in approve.events)
        assertTrue("guard:prepare" in approve.events)
        assertTrue("idem:find" in approve.events)
        assertTrue("profile:resolve" !in approve.events)
        assertTrue("document:lock" !in approve.events)
        assertTrue("asset:lock" !in approve.events)
        assertTrue("workflow:lock" !in approve.events)
        assertEquals(2, approve.guard.prepareCalls)
        assertEquals(1, approve.guard.revalidateCalls)
        assertEquals(1, approve.guard.verifyCalls)
    }

    @Test
    fun `same key conflicts across action task comment profile and operator`() {
        val document = pendingDocument()
        val workflow = pendingWorkflow(document.id, listOf(PRIMARY_USER.id, PRIMARY_USER.id))
        val fixture = Fixture(document, workflow)
        val firstTask = workflow.tasks[0].id
        val secondTask = workflow.tasks[1].id

        fixture.service.approve(workflow.id, firstTask, "first", null, "bound-key")

        assertFailsWith<IdempotencyKeyConflictException> {
            fixture.service.reject(workflow.id, firstTask, "first", "bound-key")
        }
        assertFailsWith<IdempotencyKeyConflictException> {
            fixture.service.approve(workflow.id, secondTask, "first", null, "bound-key")
        }
        assertFailsWith<IdempotencyKeyConflictException> {
            fixture.service.approve(workflow.id, firstTask, "changed", null, "bound-key")
        }
        assertFailsWith<IdempotencyKeyConflictException> {
            fixture.service.approve(workflow.id, firstTask, "first", PROFILE_ID, "bound-key")
        }
        fixture.users.current = SECONDARY_USER
        assertFailsWith<IdempotencyKeyConflictException> {
            fixture.service.approve(workflow.id, firstTask, "first", null, "bound-key")
        }

        assertEquals(1, fixture.documents.saveCalls)
        assertEquals(1, fixture.workflows.saveCalls)
        assertEquals(1, fixture.audits.records.size)
        assertEquals(1, fixture.idempotency.claimCalls)
        assertEquals(1, fixture.idempotency.completeCalls)
        assertEquals(0, fixture.profileCalls)
    }

    @Test
    fun `replay rejects every tampered document workflow receipt binding`() {
        val document = pendingDocument()
        val workflow = pendingWorkflow(document.id, listOf(PRIMARY_USER.id))
        val fixture = Fixture(document, workflow)
        val taskId = workflow.tasks.single().id
        fixture.service.approve(workflow.id, taskId, "ok", PROFILE_ID, "tamper-key")
        val original = requireNotNull(fixture.idempotency.record?.result)
        val corruptions = listOf(
            IdempotencyResult("WORKFLOW", document.id, "WORKFLOW", workflow.id),
            IdempotencyResult("DOCUMENT", Identifier("document-other"), "WORKFLOW", workflow.id),
            IdempotencyResult("DOCUMENT", document.id, "DOCUMENT", workflow.id),
            IdempotencyResult("DOCUMENT", document.id, "WORKFLOW", Identifier("workflow-other")),
        )

        corruptions.forEach { corruption ->
            fixture.idempotency.replaceResult(corruption)
            assertFailsWith<IdempotencyStoreException> {
                fixture.service.approve(workflow.id, taskId, "ok", PROFILE_ID, "tamper-key")
            }
        }
        fixture.idempotency.replaceResult(original)

        assertEquals(1, fixture.documents.saveCalls)
        assertEquals(1, fixture.workflows.saveCalls)
        assertEquals(1, fixture.audits.records.size)
    }

    @Test
    fun `completing approval keeps claim document asset workflow delivery audit completion order`() {
        val document = pendingDocument()
        val workflow = pendingWorkflow(document.id, listOf(PRIMARY_USER.id))
        val fixture = Fixture(document, workflow)

        fixture.guarded.approve(workflow.id, workflow.tasks.single().id, "ok", PROFILE_ID, "ordered-key")

        assertSubsequence(
            fixture.events,
            "idem:claim",
            "document:lock",
            "asset:lock",
            "workflow:lock",
            "delivery:save",
            "outbox:append",
            "audit:append",
            "idem:complete",
        )
    }

    @Test
    fun `last vote race rolls back claim prepares outside transaction and retries one delivery`() {
        val document = pendingDocument()
        val workflow = pendingWorkflow(document.id, listOf(SECONDARY_USER.id, PRIMARY_USER.id))
        val fixture = Fixture(document, workflow)
        fixture.workflows.beforeFirstDecision = { current ->
            fixture.events += "race:first-approved"
            current.approve(current.tasks[0].id, SECONDARY_USER.id, "parallel")
        }

        val receipt = fixture.guarded.approve(
            workflow.id,
            workflow.tasks[1].id,
            "final",
            PROFILE_ID,
            "race-key",
        )

        assertEquals(workflow.id, receipt.workflowId)
        assertEquals(workflow.tasks[1].id, receipt.taskId)
        assertEquals(2, fixture.idempotency.claimCalls)
        assertEquals(1, fixture.idempotency.completeCalls)
        assertEquals(1, fixture.transaction.rollbackCount)
        assertEquals(2, fixture.guard.revalidateCalls)
        assertEquals(2, fixture.guard.verifyCalls)
        assertEquals(1, fixture.profileCalls)
        assertEquals(1, fixture.deliveries.saved.size)
        assertEquals(1, fixture.outbox.events.size)
        assertEquals(1, fixture.audits.records.size)
        assertEquals(1, fixture.documents.saveCalls)
        assertEquals(1, fixture.workflows.saveCalls)
        val claims = fixture.events.indices.filter { fixture.events[it] == "idem:claim" }
        val rollback = fixture.events.indexOf("tx:rollback")
        val profile = fixture.events.indexOf("profile:resolve")
        assertEquals(2, claims.size)
        assertTrue(rollback > claims.first())
        assertTrue(profile > rollback)
        assertTrue(claims.last() > profile)
    }

    private fun assertSubsequence(events: List<String>, vararg expected: String) {
        var position = -1
        expected.forEach { event ->
            position = ((position + 1)..events.lastIndex).firstOrNull { index -> events[index] == event } ?: -1
            assertTrue(position >= 0, "Missing ordered event $event in $events")
        }
    }

    private class Fixture(
        val document: Document,
        workflow: WorkflowInstance? = null,
    ) {
        val events = mutableListOf<String>()
        val transaction = RecordingTransaction(events)
        val users = RecordingUsers(events)
        val authorizationRequests = mutableListOf<AuthorizationRequest>()
        val documents = RecordingDocuments(document, transaction, events)
        val workflows = RecordingWorkflows(workflow, transaction, events)
        val audits = RecordingAudits(transaction, events)
        val outbox = RecordingOutbox(transaction, events)
        val deliveries = RecordingDeliveries(transaction, events)
        val route = RecordingRoute(transaction, events)
        val guard = RecordingGuard(transaction, events)
        val idempotency = RecordingIdempotency(transaction, events)
        var profileCalls: Int = 0
            private set

        private val planner = DocumentDeliveryPlanner(
            profiles = object : DocumentDeliveryProfileProvider {
                override fun listProfiles(tenantId: Identifier): List<DocumentDeliveryProfile> {
                    check(!transaction.active)
                    profileCalls++
                    events += "profile:resolve"
                    return listOf(
                        DocumentDeliveryProfile(
                            PROFILE_ID,
                            "Profile",
                            listOf(
                                DocumentDeliveryTargetDefinition(
                                    "target",
                                    "Target",
                                    "connector",
                                    DeliveryRequirement.REQUIRED,
                                ),
                            ),
                        ),
                    )
                }
            },
            connectors = object : DeliveryConnectorResolver {
                override fun findConnector(connectorId: String): FileConnector {
                    check(!transaction.active)
                    events += "connector:resolve"
                    return HealthyConnector
                }
            },
            deliveries = deliveries,
            outbox = outbox,
            identifiers = PrefixIds("delivery"),
            clock = FIXED_CLOCK,
        )
        private val reviews = DocumentReviewWorkflowService(
            tenantProvider = object : TenantProvider {
                override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
            },
            userRealmProvider = users,
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
                    events += "authorization"
                    authorizationRequests += request
                    return AuthorizationDecision(true)
                }
            },
            documentRepository = documents,
            workflowRepository = workflows,
            deliveryPlanner = planner,
            identifierGenerator = PrefixIds("workflow"),
            transaction = transaction,
            auditTrail = AuditTrail(audits, PrefixIds("audit"), FIXED_CLOCK),
            reviewRoutes = DocumentReviewRouteResolver(listOf(route), ROUTE_ID),
        )
        private val requestIdempotency = RequestIdempotencyService(
            idempotency,
            transaction,
            PrefixIds("idem"),
            FIXED_CLOCK,
        )
        val service = IdempotentDocumentReviewWorkflowService(reviews, requestIdempotency)
        val guarded = IdempotentDocumentReviewWorkflowDelegate(reviews, requestIdempotency, guard)
    }

    private class RecordingTransaction(private val events: MutableList<String>) : ApplicationTransaction {
        var active: Boolean = false
            private set
        var rollbackCount: Int = 0
            private set
        private var rollbackActions: MutableList<() -> Unit>? = null

        fun onRollback(action: () -> Unit) {
            check(active)
            checkNotNull(rollbackActions).add(action)
        }

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested transaction is not expected." }
            active = true
            rollbackActions = mutableListOf()
            events += "tx:start"
            return try {
                action().also { events += "tx:commit" }
            } catch (failure: Throwable) {
                checkNotNull(rollbackActions).asReversed().forEach { rollback -> rollback() }
                rollbackCount++
                events += "tx:rollback"
                throw failure
            } finally {
                rollbackActions = null
                active = false
            }
        }
    }

    private class RecordingUsers(private val events: MutableList<String>) : UserRealmProvider {
        var current: UserIdentity = PRIMARY_USER
        var currentUserCalls: Int = 0
            private set

        override fun currentUser(): UserIdentity {
            currentUserCalls++
            events += "user"
            return current
        }

        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private class RecordingDocuments(
        val document: Document,
        private val transaction: RecordingTransaction,
        private val events: MutableList<String>,
    ) : DocumentRepository {
        var saveCalls: Int = 0
            private set

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? {
            check(transaction.active)
            events += "document:read"
            return document.takeIf { it.tenantId == tenantId && it.id == documentId }
        }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            check(transaction.active)
            events += "document:lock"
            return document.takeIf { it.tenantId == tenantId && it.id == documentId }
        }

        override fun save(document: Document) {
            check(transaction.active)
            saveCalls++
            events += "document:save"
        }
    }

    private class RecordingWorkflows(
        var workflow: WorkflowInstance?,
        private val transaction: RecordingTransaction,
        private val events: MutableList<String>,
    ) : WorkflowInstanceRepository {
        var saveCalls: Int = 0
            private set
        var beforeFirstDecision: ((WorkflowInstance) -> Unit)? = null
        private var decisionHookUsed = false

        override fun findById(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? {
            check(transaction.active)
            events += "workflow:snapshot"
            return workflow?.takeIf { it.tenantId == tenantId && it.id == workflowId }
        }

        override fun findForDecision(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? {
            check(transaction.active)
            events += "workflow:lock"
            val current = workflow?.takeIf { it.tenantId == tenantId && it.id == workflowId }
            if (current != null && !decisionHookUsed) {
                decisionHookUsed = true
                beforeFirstDecision?.invoke(current)
            }
            return current
        }

        override fun findActiveByDocument(tenantId: Identifier, documentId: Identifier): WorkflowInstance? {
            check(transaction.active)
            events += "workflow:active"
            return workflow?.takeIf {
                it.tenantId == tenantId && it.documentId == documentId && it.state == WorkflowState.PENDING
            }
        }

        override fun save(workflow: WorkflowInstance) {
            check(transaction.active)
            this.workflow = workflow
            saveCalls++
            events += "workflow:save"
        }
    }

    private class RecordingGuard(
        private val transaction: RecordingTransaction,
        private val events: MutableList<String>,
    ) : DocumentLifecycleMutationGuard {
        var prepareCalls = 0
            private set
        var revalidateCalls = 0
            private set
        var verifyCalls = 0
            private set

        override fun prepareLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            actionName: String,
        ): DocumentLifecycleMutationPermit {
            check(!transaction.active)
            prepareCalls++
            events += "guard:prepare"
            return Permit(operator.id)
        }

        override fun revalidateLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            permit: DocumentLifecycleMutationPermit,
        ) {
            check(!transaction.active)
            check((permit as Permit).operatorId == operator.id)
            revalidateCalls++
            events += "guard:revalidate"
        }

        override fun verifyLifecycleLocked(
            tenantId: Identifier,
            document: Document,
            permit: DocumentLifecycleMutationPermit,
        ) {
            check(transaction.active)
            verifyCalls++
            events += "asset:lock"
        }
    }

    private class Permit(val operatorId: Identifier) : DocumentLifecycleMutationPermit

    private class RecordingRoute(
        private val transaction: RecordingTransaction,
        private val events: MutableList<String>,
    ) : DocumentReviewRouteProvider {
        var calls: Int = 0
            private set

        override fun id(): String = ROUTE_ID

        override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute {
            check(!transaction.active)
            calls++
            events += "route:resolve"
            return DocumentReviewRoute(
                DocumentReviewWorkflowService.REVIEW_WORKFLOW_TYPE,
                listOf(DocumentReviewRouteTask(request.requestedReviewerId)),
            )
        }
    }

    private class RecordingDeliveries(
        private val transaction: RecordingTransaction,
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

    private class RecordingOutbox(
        private val transaction: RecordingTransaction,
        private val recordedEvents: MutableList<String>,
    ) : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()

        override fun append(event: OutboxEvent) {
            check(transaction.active)
            events += event
            recordedEvents += "outbox:append"
        }
    }

    private class RecordingAudits(
        private val transaction: RecordingTransaction,
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

    private class RecordingIdempotency(
        private val transaction: RecordingTransaction,
        private val events: MutableList<String>,
    ) : RequestIdempotencyRepository {
        var record: RequestIdempotencyRecord? = null
            private set
        var claimCalls: Int = 0
            private set
        var completeCalls: Int = 0
            private set

        override fun findByKeyDigest(tenantId: Identifier, keyDigest: String): RequestIdempotencyRecord? {
            check(transaction.active)
            events += "idem:find"
            return record?.takeIf { it.tenantId == tenantId && it.keyDigest == keyDigest }
        }

        override fun claim(
            request: RequestIdempotency,
            newRecordId: Identifier,
            now: Long,
        ): RequestIdempotencyClaim {
            check(transaction.active)
            claimCalls++
            events += "idem:claim"
            record?.let { return RequestIdempotencyClaim(it, acquired = false) }
            val claimed = RequestIdempotencyRecord(
                newRecordId,
                request.tenantId,
                request.keyDigest,
                request.operatorId,
                request.action,
                request.resourceType,
                request.resourceId,
                request.subresourceId,
                request.requestFingerprint,
                RequestIdempotencyStatus.IN_PROGRESS,
                null,
                null,
                now,
                now,
            )
            val previous = record
            transaction.onRollback { record = previous }
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
            completeCalls++
            events += "idem:complete"
            val current = checkNotNull(record)
            check(current.id == recordId && current.tenantId == tenantId && current.keyDigest == keyDigest)
            val completed = copyCompleted(current, result, completedAt)
            val previous = record
            transaction.onRollback { record = previous }
            record = completed
            return completed
        }

        fun replaceResult(result: IdempotencyResult) {
            val current = checkNotNull(record)
            record = copyCompleted(current, result, checkNotNull(current.completedTime))
        }

        private fun copyCompleted(
            source: RequestIdempotencyRecord,
            result: IdempotencyResult,
            completedAt: Long,
        ) = RequestIdempotencyRecord(
            source.id,
            source.tenantId,
            source.keyDigest,
            source.operatorId,
            source.action,
            source.resourceType,
            source.resourceId,
            source.subresourceId,
            source.requestFingerprint,
            RequestIdempotencyStatus.COMPLETED,
            result,
            completedAt,
            source.createdTime,
            completedAt,
        )
    }

    private class PrefixIds(private val prefix: String) : IdentifierGenerator {
        private var sequence = 0
        override fun nextId(): Identifier = Identifier("$prefix-${++sequence}")
    }

    private object HealthyConnector : FileConnector {
        override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult =
            ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

        override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult =
            ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

        override fun health(): ConnectorHealth = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val PRIMARY_USER = UserIdentity(Identifier("reviewer-primary"), "Primary")
        val SECONDARY_USER = UserIdentity(Identifier("reviewer-secondary"), "Secondary")
        const val ROUTE_ID = "review-route"
        const val PROFILE_ID = "profile"
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    }
}

private fun draftDocument(): Document = Document(
    Identifier("document-1"),
    Identifier("tenant-1"),
    Identifier("asset-1"),
    "DOC-001",
    "Contract",
    versions = listOf(
        DocumentVersion(
            Identifier("version-1"),
            Identifier("tenant-1"),
            Identifier("document-1"),
            "1.0",
            Identifier("file-1"),
        ),
    ),
    currentVersionId = Identifier("version-1"),
)

private fun pendingDocument(): Document = draftDocument().also { it.transition(LifecycleCommand.SUBMIT) }

private fun pendingWorkflow(documentId: Identifier, assignees: List<Identifier>): WorkflowInstance {
    val workflowId = Identifier("workflow-existing")
    return WorkflowInstance(
        workflowId,
        Identifier("tenant-1"),
        documentId,
        DocumentReviewWorkflowService.REVIEW_WORKFLOW_TYPE,
        tasks = assignees.mapIndexed { index, assignee ->
            WorkflowTask(
                Identifier("task-${index + 1}"),
                Identifier("tenant-1"),
                workflowId,
                assignee,
            )
        },
    )
}
