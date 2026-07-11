package com.fileweft.application.workflow

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.delivery.DocumentDeliveryPlanner
import com.fileweft.application.delivery.DocumentDeliveryTarget
import com.fileweft.application.delivery.DocumentDeliveryTargetRepository
import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.document.LifecycleState
import com.fileweft.domain.workflow.WorkflowInstance
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.domain.workflow.WorkflowState
import com.fileweft.domain.workflow.WorkflowTask
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.ConnectorRemoveRequest
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncResult
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.delivery.DeliveryConnectorResolver
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.delivery.DocumentDeliveryProfile
import com.fileweft.spi.delivery.DocumentDeliveryProfileProvider
import com.fileweft.spi.delivery.DocumentDeliveryTargetDefinition
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.spi.workflow.DocumentReviewRoute
import com.fileweft.spi.workflow.DocumentReviewRouteProvider
import com.fileweft.spi.workflow.DocumentReviewRouteRequest
import com.fileweft.spi.workflow.DocumentReviewRouteTask
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentReviewWorkflowServiceTest {
    @Test
    fun `submits document into a persisted reviewer workflow`() {
        val documents = InMemoryDocuments(draftDocument())
        val workflows = InMemoryWorkflows()
        var action: String? = null
        val service = service(documents, workflows, RecordingOutbox(), listOf("workflow-1", "task-1")) { request ->
            action = request.action.name
            AuthorizationDecision(true)
        }

        val workflow = service.submit(Identifier("document-1"), Identifier("reviewer-1"))

        assertEquals(LifecycleState.PENDING_REVIEW, documents.document?.lifecycleState)
        assertEquals(WorkflowState.PENDING, workflow.state)
        assertEquals(Identifier("reviewer-1"), workflow.tasks.single().assigneeId)
        assertEquals(DocumentReviewWorkflowService.SUBMIT_ACTION, action)
    }

    @Test
    fun `approval publishes document and emits one per target delivery event`() {
        val document = pendingReviewDocument()
        val workflow = pendingWorkflow(document.id)
        val documents = InMemoryDocuments(document)
        val workflows = InMemoryWorkflows(workflow)
        val outbox = RecordingOutbox()
        val service = service(documents, workflows, outbox, listOf("event-1")) { AuthorizationDecision(true) }

        val published = service.approve(workflow.id, workflow.tasks.single().id, "approved")

        assertEquals(LifecycleState.PUBLISHING, published.lifecycleState)
        assertEquals(WorkflowState.APPROVED, workflows.workflow?.state)
        assertEquals("document.delivery.target.requested", outbox.events.single().type)
        assertEquals(document.id.value, outbox.events.single().payload["documentId"])
    }

    @Test
    fun `rejection returns document to rejected state without publication event`() {
        val document = pendingReviewDocument()
        val workflow = pendingWorkflow(document.id)
        val outbox = RecordingOutbox()
        val service = service(InMemoryDocuments(document), InMemoryWorkflows(workflow), outbox, emptyList()) { AuthorizationDecision(true) }

        val rejected = service.reject(workflow.id, workflow.tasks.single().id, "revise")

        assertEquals(LifecycleState.REJECTED, rejected.lifecycleState)
        assertEquals(WorkflowState.REJECTED, workflow.state)
        assertEquals(emptyList(), outbox.events)
    }

    @Test
    fun `records the reviewer opaque id and display name when approving`() {
        val document = pendingReviewDocument()
        val workflow = pendingWorkflow(document.id, Identifier("901"))
        val audits = RecordingAudits()
        val service = service(
            documents = InMemoryDocuments(document),
            workflows = InMemoryWorkflows(workflow),
            outbox = RecordingOutbox(),
            identifiers = listOf("event-1"),
            currentUser = UserIdentity(Identifier("901"), "外部审批人"),
            auditTrail = auditTrail(audits),
            authorization = { AuthorizationDecision(true) },
        )

        service.approve(workflow.id, workflow.tasks.single().id, "批准")

        val audit = audits.records.single()
        assertEquals(DocumentReviewWorkflowService.APPROVED_AUDIT_ACTION, audit.action)
        assertEquals("901", audit.operatorId?.value)
        assertEquals("外部审批人", audit.operatorName)
    }

    @Test
    fun `holds publication until every parallel route task approves outside the database transaction`() {
        val document = draftDocument()
        val documents = InMemoryDocuments(document)
        val workflows = InMemoryWorkflows()
        val outbox = RecordingOutbox()
        var transactionDepth = 0
        val transaction = object : ApplicationTransaction {
            override fun <T> execute(action: () -> T): T {
                transactionDepth += 1
                return try {
                    action()
                } finally {
                    transactionDepth -= 1
                }
            }
        }
        val dualRoute = object : DocumentReviewRouteProvider {
            override fun id(): String = "dual"

            override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute {
                assertEquals(0, transactionDepth, "Route resolution must not run in FileWeft's database transaction.")
                return DocumentReviewRoute(
                    "DOCUMENT_DUAL_CONTROL",
                    listOf(DocumentReviewRouteTask(Identifier("reviewer-1")), DocumentReviewRouteTask(Identifier("reviewer-2"))),
                )
            }
        }
        val routes = DocumentReviewRouteResolver(listOf(dualRoute), "dual")
        val firstReviewer = service(
            documents, workflows, outbox, listOf("workflow-1", "task-1", "task-2"),
            currentUser = UserIdentity(Identifier("reviewer-1"), "审批者一"),
            authorization = { AuthorizationDecision(true) },
            reviewRoutes = routes,
            transaction = transaction,
        )

        val workflow = firstReviewer.submit(document.id, null, "dual")
        val afterFirstApproval = firstReviewer.approve(workflow.id, workflow.tasks[0].id, "first approval")

        assertEquals(LifecycleState.PENDING_REVIEW, afterFirstApproval.lifecycleState)
        assertEquals(WorkflowState.PENDING, workflows.workflow?.state)
        assertEquals(emptyList(), outbox.events)

        val secondReviewer = service(
            documents, workflows, outbox, emptyList(),
            currentUser = UserIdentity(Identifier("reviewer-2"), "审批者二"),
            authorization = { AuthorizationDecision(true) },
            reviewRoutes = routes,
            transaction = transaction,
        )
        val published = secondReviewer.approve(workflow.id, workflow.tasks[1].id, "second approval")

        assertEquals(LifecycleState.PUBLISHING, published.lifecycleState)
        assertEquals(WorkflowState.APPROVED, workflows.workflow?.state)
        assertEquals("document.delivery.target.requested", outbox.events.single().type)
    }

    @Test
    fun `enforces workflow tenant lookup`() {
        val service = service(InMemoryDocuments(draftDocument()), InMemoryWorkflows(), RecordingOutbox(), emptyList()) { AuthorizationDecision(true) }

        assertFailsWith<WorkflowNotFoundException> {
            service.approve(Identifier("missing-workflow"), Identifier("missing-task"))
        }
    }

    private fun service(
        documents: InMemoryDocuments,
        workflows: InMemoryWorkflows,
        outbox: RecordingOutbox,
        identifiers: List<String>,
        currentUser: UserIdentity = UserIdentity(Identifier("reviewer-1"), "审批者"),
        auditTrail: AuditTrail? = null,
        reviewRoutes: DocumentReviewRouteResolver = DocumentReviewRouteResolver(),
        transaction: ApplicationTransaction = DirectTransaction,
        authorization: (AuthorizationRequest) -> AuthorizationDecision,
    ): DocumentReviewWorkflowService {
        val ids = ArrayDeque(identifiers)
        return DocumentReviewWorkflowService(
            tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(Identifier("tenant-1")) },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser(): UserIdentity = currentUser
                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider { override fun authorize(request: AuthorizationRequest) = authorization(request) },
            documentRepository = documents,
            workflowRepository = workflows,
            deliveryPlanner = deliveryPlanner(outbox),
            identifierGenerator = object : IdentifierGenerator { override fun nextId() = Identifier(ids.removeFirst()) },
            transaction = transaction,
            auditTrail = auditTrail,
            reviewRoutes = reviewRoutes,
        )
    }

    private fun auditTrail(repository: RecordingAudits) = AuditTrail(
        repository,
        object : IdentifierGenerator {
            private var sequence = 0
            override fun nextId(): Identifier = Identifier("audit-${++sequence}")
        },
        Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
    )

    private fun draftDocument() = Document(
        Identifier("document-1"), Identifier("tenant-1"), Identifier("asset-1"), "DOC-001", "Contract",
        versions = listOf(DocumentVersion(Identifier("version-1"), Identifier("tenant-1"), Identifier("document-1"), "1.0", Identifier("file-1"))),
        currentVersionId = Identifier("version-1"),
    )

    private fun pendingReviewDocument() = draftDocument().also { it.transition(com.fileweft.domain.document.LifecycleCommand.SUBMIT) }

    private fun pendingWorkflow(documentId: Identifier, assigneeId: Identifier = Identifier("reviewer-1")) = WorkflowInstance(
        Identifier("workflow-1"), Identifier("tenant-1"), documentId, DocumentReviewWorkflowService.REVIEW_WORKFLOW_TYPE,
        tasks = listOf(WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), assigneeId)),
    )

    private class InMemoryDocuments(var document: Document?) : DocumentRepository {
        override fun findById(tenantId: Identifier, documentId: Identifier): Document? = document?.takeIf { it.tenantId == tenantId && it.id == documentId }
        override fun save(document: Document) { this.document = document }
    }

    private class InMemoryWorkflows(var workflow: WorkflowInstance? = null) : WorkflowInstanceRepository {
        override fun findById(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? = workflow?.takeIf { it.tenantId == tenantId && it.id == workflowId }
        override fun findActiveByDocument(tenantId: Identifier, documentId: Identifier): WorkflowInstance? =
            workflow?.takeIf { it.tenantId == tenantId && it.documentId == documentId && it.state == WorkflowState.PENDING }
        override fun save(workflow: WorkflowInstance) { this.workflow = workflow }
    }

    private class RecordingOutbox : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()
        override fun append(event: OutboxEvent) { events += event }
    }

    private class RecordingAudits : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) { records += record }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
    }

    private object DirectTransaction : ApplicationTransaction { override fun <T> execute(action: () -> T): T = action() }

    private fun deliveryPlanner(outbox: RecordingOutbox): DocumentDeliveryPlanner {
        val ids = ArrayDeque(listOf("delivery-1", "delivery-event-1"))
        return DocumentDeliveryPlanner(
            profiles = object : DocumentDeliveryProfileProvider {
                override fun listProfiles(tenantId: Identifier) = listOf(
                    DocumentDeliveryProfile(
                        "default", "Default", listOf(
                            DocumentDeliveryTargetDefinition("main", "Main", "main", DeliveryRequirement.REQUIRED),
                        ),
                    ),
                )
            },
            connectors = object : DeliveryConnectorResolver {
                override fun findConnector(connectorId: String): FileConnector = object : FileConnector {
                    override fun sync(request: ConnectorSyncRequest) = ConnectorSyncResult(com.fileweft.spi.connector.ConnectorSyncStatus.SUCCESS)
                    override fun remove(request: ConnectorRemoveRequest) = ConnectorSyncResult(com.fileweft.spi.connector.ConnectorSyncStatus.SUCCESS)
                    override fun health() = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
                }
            },
            deliveries = object : DocumentDeliveryTargetRepository {
                override fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? = null
                override fun findByDocument(tenantId: Identifier, documentId: Identifier): List<DocumentDeliveryTarget> = emptyList()
                override fun save(target: DocumentDeliveryTarget) = Unit
            },
            outbox = outbox,
            identifiers = object : IdentifierGenerator { override fun nextId() = Identifier(ids.removeFirst()) },
            clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
        )
    }
}
