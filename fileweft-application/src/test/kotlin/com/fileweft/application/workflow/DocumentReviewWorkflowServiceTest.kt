package com.fileweft.application.workflow

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.delivery.DocumentDeliveryPlanner
import com.fileweft.application.delivery.DocumentDeliveryTarget
import com.fileweft.application.delivery.DocumentDeliveryTargetRepository
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.application.security.ApplicationUnauthenticatedException
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
import com.fileweft.domain.workflow.WorkflowDecisionConflictException
import com.fileweft.domain.workflow.WorkflowState
import com.fileweft.domain.workflow.WorkflowTask
import com.fileweft.domain.workflow.WorkflowTaskAssignmentDeniedException
import com.fileweft.domain.workflow.WorkflowTaskState
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
    fun `submit rejects foreign tenant and wrong id returned by the final document lock without side effects`() {
        finalIdentityMismatches(::draftDocument).forEach { (case, lockedDocument) ->
            val persistedDocument = draftDocument()
            val documents = InMemoryDocuments(persistedDocument).apply {
                findForMutationOverride = { _, _ -> lockedDocument }
            }
            val workflows = InMemoryWorkflows()
            val outbox = RecordingOutbox()
            val audits = RecordingAudits()
            val service = service(
                documents = documents,
                workflows = workflows,
                outbox = outbox,
                identifiers = listOf("workflow-1", "task-1"),
                auditTrail = auditTrail(audits),
                authorization = { AuthorizationDecision(true) },
            )

            assertFailsWith<DocumentNotFoundException>(case) {
                service.submit(Identifier("document-1"), Identifier("reviewer-1"))
            }

            assertEquals(LifecycleState.DRAFT, persistedDocument.lifecycleState, case)
            assertEquals(LifecycleState.DRAFT, lockedDocument.lifecycleState, case)
            assertEquals(0, documents.saveCalls, case)
            assertEquals(0, workflows.saveCalls, case)
            assertEquals(null, workflows.workflow, case)
            assertEquals(emptyList(), audits.records, case)
            assertEquals(emptyList(), outbox.events, case)
        }
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
    fun `approval hides malicious final document identities without side effects`() {
        assertDecisionFinalIdentityFailures(approved = true)
    }

    @Test
    fun `rejection hides malicious final document identities without side effects`() {
        assertDecisionFinalIdentityFailures(approved = false)
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
        assertEquals(2, workflows.decisionLookups)
    }

    @Test
    fun `resolves delivery profile and connector outside the final approval transaction`() {
        val document = pendingReviewDocument()
        val workflow = pendingWorkflow(document.id)
        val documents = InMemoryDocuments(document)
        val workflows = InMemoryWorkflows(workflow)
        val outbox = RecordingOutbox()
        val transaction = TrackingTransaction()
        var profileResolutions = 0
        var connectorResolutions = 0
        val planner = DocumentDeliveryPlanner(
            profiles = object : DocumentDeliveryProfileProvider {
                override fun listProfiles(tenantId: Identifier): List<DocumentDeliveryProfile> {
                    check(!transaction.active) { "Delivery profiles must not resolve in a FileWeft transaction." }
                    profileResolutions++
                    return listOf(
                        DocumentDeliveryProfile(
                            "regulated", "Regulated",
                            listOf(DocumentDeliveryTargetDefinition("archive", "Archive", "archive", DeliveryRequirement.REQUIRED)),
                        ),
                    )
                }
            },
            connectors = object : DeliveryConnectorResolver {
                override fun findConnector(connectorId: String): FileConnector? {
                    check(!transaction.active) { "Delivery connectors must not resolve in a FileWeft transaction." }
                    connectorResolutions++
                    return object : FileConnector {
                        override fun sync(request: ConnectorSyncRequest) = ConnectorSyncResult(com.fileweft.spi.connector.ConnectorSyncStatus.SUCCESS)
                        override fun remove(request: ConnectorRemoveRequest) = ConnectorSyncResult(com.fileweft.spi.connector.ConnectorSyncStatus.SUCCESS)
                        override fun health() = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
                    }
                }
            },
            deliveries = object : DocumentDeliveryTargetRepository {
                override fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? = null
                override fun findByDocument(tenantId: Identifier, documentId: Identifier): List<DocumentDeliveryTarget> = emptyList()
                override fun save(target: DocumentDeliveryTarget) = Unit
            },
            outbox = outbox,
            identifiers = object : IdentifierGenerator {
                private val ids = ArrayDeque(listOf("delivery-1", "event-1"))
                override fun nextId(): Identifier = Identifier(ids.removeFirst())
            },
            clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
        )
        val service = service(
            documents = documents,
            workflows = workflows,
            outbox = outbox,
            identifiers = emptyList(),
            transaction = transaction,
            authorization = { AuthorizationDecision(true) },
            deliveryPlannerOverride = planner,
        )

        service.approve(workflow.id, workflow.tasks.single().id, "approved", "regulated")

        assertEquals(1, profileResolutions)
        assertEquals(1, connectorResolutions)
        assertEquals("document.delivery.target.requested", outbox.events.single().type)
    }

    @Test
    fun `enforces workflow tenant lookup`() {
        val workflows = InMemoryWorkflows()
        val authorizationRequests = mutableListOf<AuthorizationRequest>()
        val service = service(InMemoryDocuments(draftDocument()), workflows, RecordingOutbox(), emptyList()) { request ->
            authorizationRequests += request
            AuthorizationDecision(true)
        }

        assertFailsWith<WorkflowNotFoundException> {
            service.approve(Identifier("missing-workflow"), Identifier("missing-task"))
        }

        assertEquals(1, workflows.snapshotLookups)
        assertEquals(0, workflows.decisionLookups)
        assertEquals(emptyList(), authorizationRequests)
    }

    @Test
    fun `document denial after workflow lookup is hidden as workflow not found`() {
        val document = pendingReviewDocument()
        val workflow = pendingWorkflow(document.id)
        val workflows = InMemoryWorkflows(workflow)
        val authorizationRequests = mutableListOf<AuthorizationRequest>()
        val service = service(InMemoryDocuments(document), workflows, RecordingOutbox(), emptyList()) { request ->
            authorizationRequests += request
            AuthorizationDecision(false, "document audit denied")
        }

        assertFailsWith<WorkflowNotFoundException> {
            service.approve(workflow.id, workflow.tasks.single().id)
        }

        assertEquals(1, workflows.snapshotLookups)
        assertEquals(0, workflows.decisionLookups)
        assertEquals(
            listOf(DocumentReviewWorkflowService.DOCUMENT_RESOURCE_TYPE),
            authorizationRequests.map { request -> request.resource.type },
        )
    }

    @Test
    fun `orphaned and foreign workflow documents are hidden before delivery profile resolution`() {
        listOf(
            "orphaned document" to null,
            "foreign tenant document" to pendingReviewDocument(tenantId = Identifier("tenant-foreign")),
        ).forEach { (case, resolvedDocument) ->
            val workflow = pendingWorkflow(Identifier("document-1"))
            val documents = InMemoryDocuments(resolvedDocument)
            val workflows = InMemoryWorkflows(workflow)
            val outbox = RecordingOutbox()
            val audits = RecordingAudits()
            var profileResolutions = 0
            val service = service(
                documents = documents,
                workflows = workflows,
                outbox = outbox,
                identifiers = emptyList(),
                auditTrail = auditTrail(audits),
                deliveryPlannerOverride = deliveryPlanner(outbox) { profileResolutions++ },
                authorization = { AuthorizationDecision(true) },
            )

            assertFailsWith<WorkflowNotFoundException>(case) {
                service.approve(workflow.id, workflow.tasks.single().id)
            }

            assertEquals(0, profileResolutions, case)
            assertEquals(0, documents.saveCalls, case)
            assertEquals(0, workflows.saveCalls, case)
            assertEquals(0, workflows.decisionLookups, case)
            assertEquals(WorkflowState.PENDING, workflow.state, case)
            assertEquals(WorkflowTaskState.PENDING, workflow.tasks.single().state, case)
            assertEquals(emptyList(), audits.records, case)
            assertEquals(emptyList(), outbox.events, case)
        }
    }

    @Test
    fun `task assignment and decision conflicts remain distinct after document authorization`() {
        val document = pendingReviewDocument()
        val assignedWorkflow = pendingWorkflow(document.id, Identifier("assigned-reviewer"))
        val denied = service(
            InMemoryDocuments(document),
            InMemoryWorkflows(assignedWorkflow),
            RecordingOutbox(),
            emptyList(),
            authorization = { AuthorizationDecision(true) },
        )

        assertFailsWith<WorkflowTaskAssignmentDeniedException> {
            denied.approve(assignedWorkflow.id, assignedWorkflow.tasks.single().id)
        }

        val repeatedDocument = pendingReviewDocument()
        val repeatedWorkflow = WorkflowInstance(
            Identifier("workflow-1"),
            Identifier("tenant-1"),
            repeatedDocument.id,
            DocumentReviewWorkflowService.REVIEW_WORKFLOW_TYPE,
            tasks = listOf(
                WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1")),
                WorkflowTask(Identifier("task-2"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-2")),
            ),
        )
        repeatedWorkflow.approve(Identifier("task-1"), Identifier("reviewer-1"))
        val conflicted = service(
            InMemoryDocuments(repeatedDocument),
            InMemoryWorkflows(repeatedWorkflow),
            RecordingOutbox(),
            emptyList(),
            authorization = { AuthorizationDecision(true) },
        )

        assertFailsWith<WorkflowDecisionConflictException> {
            conflicted.approve(repeatedWorkflow.id, Identifier("task-1"))
        }
    }

    @Test
    fun `classifies a missing reviewer during a workflow decision as unauthenticated`() {
        val document = pendingReviewDocument()
        val workflow = pendingWorkflow(document.id)
        val workflows = InMemoryWorkflows(workflow)
        var authorizationCalls = 0
        val service = service(
            documents = InMemoryDocuments(document),
            workflows = workflows,
            outbox = RecordingOutbox(),
            identifiers = emptyList(),
            currentUser = null,
            authorization = {
                authorizationCalls++
                AuthorizationDecision(true)
            },
        )

        assertFailsWith<ApplicationUnauthenticatedException> {
            service.approve(workflow.id, workflow.tasks.single().id, "approved")
        }

        assertEquals(0, authorizationCalls)
        assertEquals(0, workflows.snapshotLookups)
        assertEquals(0, workflows.decisionLookups)
    }

    private fun service(
        documents: InMemoryDocuments,
        workflows: InMemoryWorkflows,
        outbox: RecordingOutbox,
        identifiers: List<String>,
        currentUser: UserIdentity? = UserIdentity(Identifier("reviewer-1"), "审批者"),
        auditTrail: AuditTrail? = null,
        reviewRoutes: DocumentReviewRouteResolver = DocumentReviewRouteResolver(),
        transaction: ApplicationTransaction = DirectTransaction,
        deliveryPlannerOverride: DocumentDeliveryPlanner? = null,
        authorization: (AuthorizationRequest) -> AuthorizationDecision,
    ): DocumentReviewWorkflowService {
        val ids = ArrayDeque(identifiers)
        return DocumentReviewWorkflowService(
            tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(Identifier("tenant-1")) },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser(): UserIdentity? = currentUser
                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider { override fun authorize(request: AuthorizationRequest) = authorization(request) },
            documentRepository = documents,
            workflowRepository = workflows,
            deliveryPlanner = deliveryPlannerOverride ?: deliveryPlanner(outbox),
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

    private fun draftDocument(
        id: Identifier = Identifier("document-1"),
        tenantId: Identifier = Identifier("tenant-1"),
    ) = Document(
        id, tenantId, Identifier("asset-1"), "DOC-001", "Contract",
        versions = listOf(DocumentVersion(Identifier("version-1"), tenantId, id, "1.0", Identifier("file-1"))),
        currentVersionId = Identifier("version-1"),
    )

    private fun pendingReviewDocument(
        id: Identifier = Identifier("document-1"),
        tenantId: Identifier = Identifier("tenant-1"),
    ) = draftDocument(id, tenantId).also { it.transition(com.fileweft.domain.document.LifecycleCommand.SUBMIT) }

    private fun finalIdentityMismatches(factory: (Identifier, Identifier) -> Document): List<Pair<String, Document>> = listOf(
        "foreign tenant" to factory(Identifier("document-1"), Identifier("tenant-foreign")),
        "wrong document id" to factory(Identifier("document-wrong"), Identifier("tenant-1")),
    )

    private fun pendingWorkflow(documentId: Identifier, assigneeId: Identifier = Identifier("reviewer-1")) = WorkflowInstance(
        Identifier("workflow-1"), Identifier("tenant-1"), documentId, DocumentReviewWorkflowService.REVIEW_WORKFLOW_TYPE,
        tasks = listOf(WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), assigneeId)),
    )

    private class InMemoryDocuments(var document: Document?) : DocumentRepository {
        var saveCalls: Int = 0
            private set
        var findForMutationOverride: ((Identifier, Identifier) -> Document?)? = null

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? = document?.takeIf { it.tenantId == tenantId && it.id == documentId }
        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            findForMutationOverride?.let { provider -> return provider(tenantId, documentId) }
            return findById(tenantId, documentId)
        }
        override fun save(document: Document) {
            saveCalls++
            this.document = document
        }
    }

    private class InMemoryWorkflows(var workflow: WorkflowInstance? = null) : WorkflowInstanceRepository {
        var snapshotLookups = 0
            private set
        var decisionLookups = 0
            private set
        var saveCalls: Int = 0
            private set

        override fun findById(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? {
            snapshotLookups++
            return workflow?.takeIf { it.tenantId == tenantId && it.id == workflowId }
        }
        override fun findForDecision(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? {
            decisionLookups++
            return findById(tenantId, workflowId)
        }
        override fun findActiveByDocument(tenantId: Identifier, documentId: Identifier): WorkflowInstance? =
            workflow?.takeIf { it.tenantId == tenantId && it.documentId == documentId && it.state == WorkflowState.PENDING }
        override fun save(workflow: WorkflowInstance) {
            saveCalls++
            this.workflow = workflow
        }
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

    private class TrackingTransaction : ApplicationTransaction {
        var active = false
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested transaction is not expected in this fixture." }
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private fun deliveryPlanner(
        outbox: RecordingOutbox,
        onProfileResolution: () -> Unit = {},
    ): DocumentDeliveryPlanner {
        val ids = ArrayDeque(listOf("delivery-1", "delivery-event-1"))
        return DocumentDeliveryPlanner(
            profiles = object : DocumentDeliveryProfileProvider {
                override fun listProfiles(tenantId: Identifier): List<DocumentDeliveryProfile> {
                    onProfileResolution()
                    return listOf(
                        DocumentDeliveryProfile(
                            "default", "Default", listOf(
                                DocumentDeliveryTargetDefinition("main", "Main", "main", DeliveryRequirement.REQUIRED),
                            ),
                        ),
                    )
                }
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

    private fun assertDecisionFinalIdentityFailures(approved: Boolean) {
        finalIdentityMismatches(::pendingReviewDocument).forEach { (case, lockedDocument) ->
            val persistedDocument = pendingReviewDocument()
            val workflow = pendingWorkflow(persistedDocument.id)
            val documents = InMemoryDocuments(persistedDocument).apply {
                findForMutationOverride = { _, _ -> lockedDocument }
            }
            val workflows = InMemoryWorkflows(workflow)
            val outbox = RecordingOutbox()
            val audits = RecordingAudits()
            val service = service(
                documents = documents,
                workflows = workflows,
                outbox = outbox,
                identifiers = emptyList(),
                auditTrail = auditTrail(audits),
                authorization = { AuthorizationDecision(true) },
            )

            assertFailsWith<WorkflowNotFoundException>(case) {
                if (approved) {
                    service.approve(workflow.id, workflow.tasks.single().id, "approved")
                } else {
                    service.reject(workflow.id, workflow.tasks.single().id, "rejected")
                }
            }

            assertEquals(LifecycleState.PENDING_REVIEW, persistedDocument.lifecycleState, case)
            assertEquals(LifecycleState.PENDING_REVIEW, lockedDocument.lifecycleState, case)
            assertEquals(WorkflowState.PENDING, workflow.state, case)
            assertEquals(WorkflowTaskState.PENDING, workflow.tasks.single().state, case)
            assertEquals(0, documents.saveCalls, case)
            assertEquals(0, workflows.saveCalls, case)
            assertEquals(0, workflows.decisionLookups, case)
            assertEquals(emptyList(), audits.records, case)
            assertEquals(emptyList(), outbox.events, case)
        }
    }
}
