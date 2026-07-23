package ai.icen.fw.application.workflow

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentLifecycleMutationGuard
import ai.icen.fw.application.catalog.DocumentLifecycleMutationPermit
import ai.icen.fw.application.delivery.DocumentDeliveryPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryTarget
import ai.icen.fw.application.delivery.DocumentDeliveryTargetRepository
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.audit.AuditRecord
import ai.icen.fw.domain.audit.AuditRecordRepository
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.domain.workflow.WorkflowInstance
import ai.icen.fw.domain.workflow.WorkflowInstanceRepository
import ai.icen.fw.domain.workflow.WorkflowDecisionConflictException
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTask
import ai.icen.fw.domain.workflow.WorkflowTaskAssignmentDeniedException
import ai.icen.fw.domain.workflow.WorkflowTaskState
import ai.icen.fw.domain.workflow.WorkflowWithdrawalConflictException
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentReviewWorkflowServiceTest {
    @Test
    fun `submit uses one fixed user snapshot for authorization guard route and audit`() {
        val operator = UserIdentity(
            Identifier("submitter-primary"),
            "固定提交人",
            mapOf("identity-source" to "primary"),
        )
        val users = RotatingUsers(operator)
        val authorizationRequests = mutableListOf<AuthorizationRequest>()
        val guard = IdentitySnapshotGuard()
        val route = RecordingIdentityRouteProvider()
        val audits = RecordingAudits()
        val document = draftDocument()
        val service = service(
            documents = InMemoryDocuments(document),
            workflows = InMemoryWorkflows(),
            outbox = RecordingOutbox(),
            identifiers = listOf("workflow-1", "task-1"),
            userRealmProviderOverride = users,
            auditTrail = auditTrail(audits),
            reviewRoutes = DocumentReviewRouteResolver(listOf(route), route.id()),
            authorization = { request ->
                authorizationRequests += request
                AuthorizationDecision(true)
            },
        )

        service.submit(document.id, Identifier("reviewer-1"), route.id(), guard)

        assertEquals(1, users.currentUserCalls)
        assertEquals(1, authorizationRequests.size)
        assertEquals(operator.id, authorizationRequests.single().subject.id)
        assertEquals(operator.attributes, authorizationRequests.single().subject.attributes)
        assertEquals(DocumentReviewWorkflowService.SUBMIT_ACTION, authorizationRequests.single().action.name)
        guard.assertSingleSnapshot(operator, DocumentReviewWorkflowService.SUBMIT_ACTION)
        assertEquals(operator.id, route.request?.submittedBy)
        assertAuditOperator(audits.records.single(), operator)
    }

    @Test
    fun `approve uses one fixed user snapshot for authorization guard and audit`() {
        assertDecisionUsesOneFixedUserSnapshot(approved = true)
    }

    @Test
    fun `reject uses one fixed user snapshot for authorization guard and audit`() {
        assertDecisionUsesOneFixedUserSnapshot(approved = false)
    }

    @Test
    fun `submitter withdraws with one trusted identity and catalog visibility snapshot`() {
        val submitter = UserIdentity(Identifier("submitter-1"), "提交人")
        val document = pendingReviewDocument()
        val workflow = pendingWorkflow(document.id, submittedBy = submitter.id)
        val workflows = InMemoryWorkflows(workflow)
        val documents = InMemoryDocuments(document)
        val audits = RecordingAudits()
        val guard = IdentitySnapshotGuard()
        var policyCalls = 0
        val service = service(
            documents = documents,
            workflows = workflows,
            outbox = RecordingOutbox(),
            identifiers = emptyList(),
            currentUser = submitter,
            auditTrail = auditTrail(audits),
            authorization = {
                policyCalls++
                AuthorizationDecision(false, "withdraw policy denied")
            },
        )

        val withdrawn = service.withdraw(workflow.id, guard)

        assertSame(document, withdrawn)
        assertEquals(0, policyCalls)
        assertEquals(LifecycleState.DRAFT, document.lifecycleState)
        assertEquals(WorkflowState.WITHDRAWN, workflow.state)
        assertEquals(1, documents.saveCalls)
        assertEquals(1, workflows.saveCalls)
        guard.assertSingleSnapshot(submitter, "document:read")
        val audit = audits.records.single()
        assertEquals(DocumentReviewWorkflowService.WITHDRAWN_AUDIT_ACTION, audit.action)
        assertEquals("WITHDRAWN", audit.details["workflowState"])
        assertEquals("SUBMITTER", audit.details["authorizationBasis"])
        assertAuditOperator(audit, submitter)
    }

    @Test
    fun `authorized operator withdraws a legacy workflow while unauthorized user learns no document identity`() {
        val document = pendingReviewDocument()
        val legacyWorkflow = pendingWorkflow(document.id)
        val audits = RecordingAudits()
        val authorizationRequests = mutableListOf<AuthorizationRequest>()
        val authorized = service(
            documents = InMemoryDocuments(document),
            workflows = InMemoryWorkflows(legacyWorkflow),
            outbox = RecordingOutbox(),
            identifiers = emptyList(),
            currentUser = UserIdentity(Identifier("operator-1"), "管理员"),
            auditTrail = auditTrail(audits),
            authorization = { request ->
                authorizationRequests += request
                AuthorizationDecision(true)
            },
        )

        authorized.withdraw(legacyWorkflow.id)

        assertEquals(listOf(DocumentReviewWorkflowService.WITHDRAW_ACTION), authorizationRequests.map { it.action.name })
        assertEquals(WorkflowState.WITHDRAWN, legacyWorkflow.state)
        assertEquals("POLICY", audits.records.single().details["authorizationBasis"])

        val deniedDocument = pendingReviewDocument()
        val deniedWorkflow = pendingWorkflow(deniedDocument.id, submittedBy = Identifier("submitter-other"))
        val deniedAudits = RecordingAudits()
        val deniedWorkflows = InMemoryWorkflows(deniedWorkflow)
        val deniedDocuments = InMemoryDocuments(deniedDocument)
        val denied = service(
            documents = deniedDocuments,
            workflows = deniedWorkflows,
            outbox = RecordingOutbox(),
            identifiers = emptyList(),
            currentUser = UserIdentity(Identifier("operator-denied")),
            auditTrail = auditTrail(deniedAudits),
            authorization = { AuthorizationDecision(false, "withdraw denied") },
        )

        assertFailsWith<WorkflowNotFoundException> { denied.withdraw(deniedWorkflow.id) }
        assertEquals(LifecycleState.PENDING_REVIEW, deniedDocument.lifecycleState)
        assertEquals(WorkflowState.PENDING, deniedWorkflow.state)
        assertEquals(0, deniedDocuments.saveCalls)
        assertEquals(0, deniedWorkflows.saveCalls)
        assertEquals(emptyList(), deniedAudits.records)
    }

    @Test
    fun `cross tenant withdrawal is hidden before authorization or mutation`() {
        val foreignTenant = Identifier("tenant-foreign")
        val operator = UserIdentity(Identifier("submitter-1"), "提交人")
        val document = pendingReviewDocument(Identifier("document-foreign"), foreignTenant)
        val workflow = WorkflowInstance(
            Identifier("workflow-foreign"),
            foreignTenant,
            document.id,
            DocumentReviewWorkflowService.REVIEW_WORKFLOW_TYPE,
            WorkflowState.PENDING,
            listOf(
                WorkflowTask(
                    Identifier("task-foreign"),
                    foreignTenant,
                    Identifier("workflow-foreign"),
                    Identifier("reviewer-foreign"),
                ),
            ),
            operator.id,
        )
        val documents = InMemoryDocuments(document)
        val workflows = InMemoryWorkflows(workflow)
        val audits = RecordingAudits()
        var authorizationCalls = 0
        val service = service(
            documents = documents,
            workflows = workflows,
            outbox = RecordingOutbox(),
            identifiers = emptyList(),
            currentUser = operator,
            auditTrail = auditTrail(audits),
            authorization = {
                authorizationCalls++
                AuthorizationDecision(true)
            },
        )

        assertFailsWith<WorkflowNotFoundException> { service.withdraw(workflow.id) }

        assertEquals(LifecycleState.PENDING_REVIEW, document.lifecycleState)
        assertEquals(WorkflowState.PENDING, workflow.state)
        assertEquals(1, workflows.snapshotLookups)
        assertEquals(0, workflows.decisionLookups)
        assertEquals(0, authorizationCalls)
        assertEquals(0, documents.saveCalls)
        assertEquals(0, workflows.saveCalls)
        assertEquals(emptyList(), audits.records)
    }

    @Test
    fun `completed workflow and missing audit trail reject withdrawal without mutations`() {
        val document = pendingReviewDocument()
        val workflow = pendingWorkflow(document.id, submittedBy = Identifier("reviewer-1"))
        workflow.approve(workflow.tasks.single().id, Identifier("reviewer-1"))
        val documents = InMemoryDocuments(document)
        val workflows = InMemoryWorkflows(workflow)
        val audited = service(
            documents = documents,
            workflows = workflows,
            outbox = RecordingOutbox(),
            identifiers = emptyList(),
            auditTrail = auditTrail(RecordingAudits()),
            authorization = { AuthorizationDecision(true) },
        )

        assertFailsWith<WorkflowWithdrawalConflictException> { audited.withdraw(workflow.id) }
        assertEquals(LifecycleState.PENDING_REVIEW, document.lifecycleState)
        assertEquals(0, documents.saveCalls)
        assertEquals(0, workflows.saveCalls)

        val pending = pendingWorkflow(document.id, submittedBy = Identifier("reviewer-1"))
        val withoutAudit = service(
            documents = InMemoryDocuments(document),
            workflows = InMemoryWorkflows(pending),
            outbox = RecordingOutbox(),
            identifiers = emptyList(),
            authorization = { AuthorizationDecision(true) },
        )
        assertFailsWith<IllegalArgumentException> { withoutAudit.withdraw(pending.id) }
        assertEquals(WorkflowState.PENDING, pending.state)
    }

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
        assertEquals(Identifier("reviewer-1"), workflow.submittedBy)
        assertEquals(DocumentReviewWorkflowService.SUBMIT_ACTION, action)
    }

    @Test
    fun `submit returns the existing active workflow instead of conflicting`() {
        val persistedDocument = draftDocument()
        val documents = InMemoryDocuments(persistedDocument)
        val workflows = InMemoryWorkflows()
        val audits = RecordingAudits()
        val service = service(
            documents = documents,
            workflows = workflows,
            outbox = RecordingOutbox(),
            identifiers = listOf("workflow-1", "task-1"),
            auditTrail = auditTrail(audits),
            authorization = { AuthorizationDecision(true) },
        )

        val first = service.submit(Identifier("document-1"), Identifier("reviewer-1"))
        // Submission is naturally idempotent per document: a repeated submit
        // reuses the active review instead of raising a review conflict.
        val repeated = service.submit(Identifier("document-1"), Identifier("reviewer-1"))

        assertEquals(first.id, repeated.id)
        assertEquals(WorkflowState.PENDING, repeated.state)
        assertEquals(LifecycleState.PENDING_REVIEW, persistedDocument.lifecycleState)
        assertEquals(1, documents.saveCalls)
        assertEquals(1, workflows.saveCalls)
        // Reusing the active review must not append a second submission audit.
        assertEquals(1, audits.records.size)
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
    fun `submit rejects every unsafe provider assignee before the write transaction`() {
        val invalidAssigneeIds = listOf(
            "u".repeat(257),
            " reviewer-2",
            "reviewer-2\u200B",
            "reviewer-2\u0007",
            "reviewer-2\uD800",
        )

        invalidAssigneeIds.forEach { invalidAssigneeId ->
            val document = draftDocument()
            val documents = InMemoryDocuments(document)
            val workflows = InMemoryWorkflows()
            val transaction = TrackingTransaction()
            val route = object : DocumentReviewRouteProvider {
                override fun id(): String = "unsafe-assignee"

                override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute {
                    assertTrue(!transaction.active, "Route resolution must stay outside FileWeft's transaction.")
                    return DocumentReviewRoute(
                        DocumentReviewWorkflowService.REVIEW_WORKFLOW_TYPE,
                        listOf(
                            DocumentReviewRouteTask(Identifier("reviewer-1")),
                            DocumentReviewRouteTask(Identifier(invalidAssigneeId)),
                        ),
                    )
                }
            }
            val service = service(
                documents = documents,
                workflows = workflows,
                outbox = RecordingOutbox(),
                identifiers = emptyList(),
                reviewRoutes = DocumentReviewRouteResolver(listOf(route), route.id()),
                transaction = transaction,
                authorization = { AuthorizationDecision(true) },
            )

            val failure = assertFailsWith<DocumentReviewRouteConfigurationException> {
                service.submit(document.id, null, route.id())
            }

            assertEquals(
                "Document review route unsafe-assignee returned an invalid assignee id at task index 1.",
                failure.message,
            )
            assertTrue(failure.cause is IllegalArgumentException)
            assertEquals(1, transaction.executeCalls, "Only the read-only route snapshot transaction may run.")
            assertEquals(LifecycleState.DRAFT, document.lifecycleState)
            assertEquals(0, documents.saveCalls)
            assertEquals(0, workflows.saveCalls)
            assertEquals(null, workflows.workflow)
        }
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
                        override fun sync(request: ConnectorSyncRequest) = ConnectorSyncResult(ai.icen.fw.spi.connector.ConnectorSyncStatus.SUCCESS)
                        override fun remove(request: ConnectorRemoveRequest) = ConnectorSyncResult(ai.icen.fw.spi.connector.ConnectorSyncStatus.SUCCESS)
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
        userRealmProviderOverride: UserRealmProvider? = null,
        auditTrail: AuditTrail? = null,
        reviewRoutes: DocumentReviewRouteResolver = DocumentReviewRouteResolver(),
        transaction: ApplicationTransaction = DirectTransaction,
        deliveryPlannerOverride: DocumentDeliveryPlanner? = null,
        authorization: (AuthorizationRequest) -> AuthorizationDecision,
    ): DocumentReviewWorkflowService {
        val ids = ArrayDeque(identifiers)
        return DocumentReviewWorkflowService(
            tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(Identifier("tenant-1")) },
            userRealmProvider = userRealmProviderOverride ?: object : UserRealmProvider {
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

    private fun assertDecisionUsesOneFixedUserSnapshot(approved: Boolean) {
        val operator = UserIdentity(
            Identifier(if (approved) "approver-primary" else "rejecter-primary"),
            if (approved) "固定审批人" else "固定驳回人",
            mapOf("identity-source" to "primary"),
        )
        val users = RotatingUsers(operator)
        val authorizationRequests = mutableListOf<AuthorizationRequest>()
        val guard = IdentitySnapshotGuard()
        val audits = RecordingAudits()
        val document = pendingReviewDocument()
        val workflow = pendingWorkflow(document.id, operator.id)
        val workflowRepository = InMemoryWorkflows(workflow)
        val service = service(
            documents = InMemoryDocuments(document),
            workflows = workflowRepository,
            outbox = RecordingOutbox(),
            identifiers = emptyList(),
            userRealmProviderOverride = users,
            auditTrail = auditTrail(audits),
            authorization = { request ->
                authorizationRequests += request
                AuthorizationDecision(true)
            },
        )

        if (approved) {
            service.approve(workflow.id, workflow.tasks.single().id, "approved", null, guard)
        } else {
            service.reject(workflow.id, workflow.tasks.single().id, "rejected", guard)
        }

        assertEquals(1, users.currentUserCalls)
        assertEquals(1, authorizationRequests.size)
        assertEquals(operator.id, authorizationRequests.single().subject.id)
        assertEquals(operator.attributes, authorizationRequests.single().subject.attributes)
        assertEquals(DocumentReviewWorkflowService.AUDIT_ACTION, authorizationRequests.single().action.name)
        guard.assertSingleSnapshot(operator, DocumentReviewWorkflowService.AUDIT_ACTION)
        val audit = audits.records.single()
        assertEquals(
            if (approved) DocumentReviewWorkflowService.APPROVED_AUDIT_ACTION else DocumentReviewWorkflowService.REJECTED_AUDIT_ACTION,
            audit.action,
        )
        assertAuditOperator(audit, operator)
        val decidedTask = requireNotNull(workflowRepository.workflow).tasks.single { task ->
            task.id == workflow.tasks.single().id
        }
        assertEquals(operator.id, decidedTask.decisionOperatorId)
        assertEquals(operator.displayName, decidedTask.decisionOperatorName)
    }

    private fun assertAuditOperator(audit: AuditRecord, operator: UserIdentity) {
        assertEquals(operator.id, audit.operatorId)
        assertEquals(operator.displayName, audit.operatorName)
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
    ) = draftDocument(id, tenantId).also { it.transition(ai.icen.fw.domain.document.LifecycleCommand.SUBMIT) }

    private fun finalIdentityMismatches(factory: (Identifier, Identifier) -> Document): List<Pair<String, Document>> = listOf(
        "foreign tenant" to factory(Identifier("document-1"), Identifier("tenant-foreign")),
        "wrong document id" to factory(Identifier("document-wrong"), Identifier("tenant-1")),
    )

    private fun pendingWorkflow(
        documentId: Identifier,
        assigneeId: Identifier = Identifier("reviewer-1"),
        submittedBy: Identifier? = null,
    ) = WorkflowInstance(
        Identifier("workflow-1"),
        Identifier("tenant-1"),
        documentId,
        DocumentReviewWorkflowService.REVIEW_WORKFLOW_TYPE,
        WorkflowState.PENDING,
        listOf(WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), assigneeId)),
        submittedBy,
    )

    private class RotatingUsers(first: UserIdentity) : UserRealmProvider {
        private val identities = listOf(
            first,
            UserIdentity(Identifier("rotating-secondary"), "错误的第二身份", mapOf("identity-source" to "secondary")),
            UserIdentity(Identifier("rotating-tertiary"), "错误的第三身份", mapOf("identity-source" to "tertiary")),
        )
        var currentUserCalls: Int = 0
            private set

        override fun currentUser(): UserIdentity {
            val identity = identities[currentUserCalls.coerceAtMost(identities.lastIndex)]
            currentUserCalls++
            return identity
        }

        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private class RecordingIdentityRouteProvider : DocumentReviewRouteProvider {
        var request: DocumentReviewRouteRequest? = null
            private set

        override fun id(): String = "identity-snapshot"

        override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute {
            this.request = request
            return DocumentReviewRoute(
                DocumentReviewWorkflowService.REVIEW_WORKFLOW_TYPE,
                listOf(DocumentReviewRouteTask(request.requestedReviewerId)),
            )
        }
    }

    private class IdentitySnapshotGuard : DocumentLifecycleMutationGuard {
        private val preparedOperators = mutableListOf<UserIdentity>()
        private val revalidatedOperators = mutableListOf<UserIdentity>()
        private val actions = mutableListOf<String>()
        private var permit: IdentitySnapshotPermit? = null
        private var verifyCalls: Int = 0

        override fun prepareLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            actionName: String,
        ): DocumentLifecycleMutationPermit {
            preparedOperators += operator
            actions += actionName
            return IdentitySnapshotPermit(operator).also { permit = it }
        }

        override fun revalidateLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            permit: DocumentLifecycleMutationPermit,
        ) {
            revalidatedOperators += operator
            assertTrue(this.permit === permit)
            assertSame((permit as IdentitySnapshotPermit).operator, operator)
        }

        override fun verifyLifecycleLocked(
            tenantId: Identifier,
            document: Document,
            permit: DocumentLifecycleMutationPermit,
        ) {
            verifyCalls++
            assertTrue(this.permit === permit)
        }

        fun assertSingleSnapshot(operator: UserIdentity, action: String) {
            assertEquals(1, preparedOperators.size)
            assertEquals(1, revalidatedOperators.size)
            assertEquals(1, verifyCalls)
            assertEquals(operator, preparedOperators.single())
            assertSame(preparedOperators.single(), revalidatedOperators.single())
            assertEquals(listOf(action), actions)
        }
    }

    private class IdentitySnapshotPermit(val operator: UserIdentity) : DocumentLifecycleMutationPermit

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
        var executeCalls = 0
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested transaction is not expected in this fixture." }
            executeCalls++
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
                    override fun sync(request: ConnectorSyncRequest) = ConnectorSyncResult(ai.icen.fw.spi.connector.ConnectorSyncStatus.SUCCESS)
                    override fun remove(request: ConnectorRemoveRequest) = ConnectorSyncResult(ai.icen.fw.spi.connector.ConnectorSyncStatus.SUCCESS)
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
