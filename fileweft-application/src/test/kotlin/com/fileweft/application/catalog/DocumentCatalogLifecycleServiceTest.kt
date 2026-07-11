package com.fileweft.application.catalog

import com.fileweft.application.archive.ArchiveDocumentService
import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.delivery.DocumentDeliveryPlanner
import com.fileweft.application.delivery.DocumentDeliveryTarget
import com.fileweft.application.delivery.DocumentDeliveryTargetRepository
import com.fileweft.application.document.DocumentCommandService
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.offline.OfflineDocumentService
import com.fileweft.application.offline.RestoreOfflineDocumentService
import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.application.publish.PublishDocumentService
import com.fileweft.application.security.ApplicationUnauthenticatedException
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.application.workflow.DocumentReviewConflictException
import com.fileweft.application.workflow.DocumentReviewWorkflowService
import com.fileweft.application.workflow.WorkflowNotFoundException
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
import com.fileweft.domain.file.FileAsset
import com.fileweft.domain.file.FileAssetMutationRepository
import com.fileweft.domain.workflow.WorkflowInstance
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.domain.workflow.WorkflowState
import com.fileweft.domain.workflow.WorkflowTask
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.catalog.DocumentCatalogAccessRequest
import com.fileweft.spi.catalog.DocumentCatalogBinding
import com.fileweft.spi.catalog.DocumentCatalogFolder
import com.fileweft.spi.catalog.DocumentCatalogOperation
import com.fileweft.spi.catalog.DocumentCatalogProvider
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentCatalogLifecycleServiceTest {
    @Test
    fun `denied lifecycle authorization touches no document asset catalog or audit`() {
        val fixture = Fixture(allowed = false)

        assertFailsWith<SecurityException> {
            fixture.service.submit(fixture.document.id)
        }

        assertEquals(listOf(DocumentReviewWorkflowService.SUBMIT_ACTION), fixture.authorization.actions)
        assertEquals(0, fixture.documents.ordinaryReads)
        assertEquals(0, fixture.documents.mutationReads)
        assertEquals(0, fixture.assets.ordinaryReads)
        assertEquals(0, fixture.assets.mutationReads)
        assertTrue(fixture.catalog.requests.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `workflow decision authenticates before any workflow repository read`() {
        val fixture = Fixture(
            initialDocument = pendingDocument(),
            initialWorkflow = dualWorkflow(DOCUMENT_ID),
            currentUser = null,
        )

        assertFailsWith<ApplicationUnauthenticatedException> {
            fixture.service.approve(WORKFLOW_ID, TASK_ONE_ID)
        }

        assertEquals(0, fixture.workflows.ordinaryReads)
        assertEquals(0, fixture.workflows.decisionReads)
        assertTrue(fixture.authorization.actions.isEmpty())
        assertEquals(0, fixture.documents.ordinaryReads)
        assertTrue(fixture.catalog.requests.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `document denial after workflow lookup is hidden as workflow not found`() {
        val fixture = Fixture(
            initialDocument = pendingDocument(),
            initialWorkflow = dualWorkflow(DOCUMENT_ID),
            allowed = false,
        )

        assertFailsWith<WorkflowNotFoundException> {
            fixture.service.approve(WORKFLOW_ID, TASK_ONE_ID)
        }

        assertEquals(1, fixture.workflows.ordinaryReads)
        assertEquals(0, fixture.workflows.decisionReads)
        assertEquals(listOf(DocumentReviewWorkflowService.AUDIT_ACTION), fixture.authorization.actions)
        assertEquals(
            listOf(DocumentReviewWorkflowService.DOCUMENT_RESOURCE_TYPE),
            fixture.authorization.requests.map { request -> request.resource.type },
        )
        assertEquals(0, fixture.documents.ordinaryReads)
        assertEquals(0, fixture.assets.ordinaryReads)
        assertTrue(fixture.catalog.requests.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `document revocation during guarded preparation is also hidden as workflow not found`() {
        var documentAuthorizations = 0
        val fixture = Fixture(
            initialDocument = pendingDocument(),
            initialWorkflow = dualWorkflow(DOCUMENT_ID),
            authorizationDecision = {
                documentAuthorizations++ == 0
            },
        )

        assertFailsWith<WorkflowNotFoundException> {
            fixture.service.approve(WORKFLOW_ID, TASK_ONE_ID)
        }

        assertEquals(1, fixture.workflows.ordinaryReads)
        assertEquals(0, fixture.workflows.decisionReads)
        assertEquals(1, fixture.documents.ordinaryReads)
        assertEquals(1, fixture.assets.ordinaryReads)
        assertEquals(0, fixture.documents.mutationReads)
        assertEquals(0, fixture.assets.mutationReads)
        assertEquals(2, documentAuthorizations)
        assertTrue(fixture.catalog.requests.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `document revocation during guarded revalidation is also hidden as workflow not found`() {
        var documentAuthorizations = 0
        val fixture = Fixture(
            initialDocument = pendingDocument(),
            initialWorkflow = dualWorkflow(DOCUMENT_ID),
            authorizationDecision = {
                documentAuthorizations++ < 2
            },
        )

        assertFailsWith<WorkflowNotFoundException> {
            fixture.service.approve(WORKFLOW_ID, TASK_ONE_ID)
        }

        assertEquals(1, fixture.workflows.ordinaryReads)
        assertEquals(0, fixture.workflows.decisionReads)
        assertEquals(1, fixture.documents.ordinaryReads)
        assertEquals(1, fixture.assets.ordinaryReads)
        assertEquals(0, fixture.documents.mutationReads)
        assertEquals(0, fixture.assets.mutationReads)
        assertEquals(3, documentAuthorizations)
        assertEquals(listOf(SOURCE_FOLDER_ID), fixture.catalog.requests)
        assertTrue(fixture.catalog.transactionStates.none { it })
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `catalog invisible workflow document is hidden as workflow not found`() {
        val fixture = Fixture(
            initialDocument = pendingDocument(),
            initialWorkflow = dualWorkflow(DOCUMENT_ID),
            catalogVisible = false,
        )

        assertFailsWith<WorkflowNotFoundException> {
            fixture.service.approve(WORKFLOW_ID, TASK_ONE_ID)
        }

        assertEquals(1, fixture.workflows.ordinaryReads)
        assertEquals(0, fixture.workflows.decisionReads)
        assertEquals(1, fixture.documents.ordinaryReads)
        assertEquals(1, fixture.assets.ordinaryReads)
        assertEquals(0, fixture.documents.mutationReads)
        assertEquals(0, fixture.assets.mutationReads)
        assertEquals(listOf(SOURCE_FOLDER_ID), fixture.catalog.requests)
        assertTrue(fixture.catalog.transactionStates.none { it })
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `invisible source is hidden before a mutation lock or audit`() {
        val fixture = Fixture(catalogVisible = false)

        assertFailsWith<DocumentNotFoundException> {
            fixture.service.submit(fixture.document.id)
        }

        assertEquals(LifecycleState.DRAFT, fixture.document.lifecycleState)
        assertEquals(1, fixture.documents.ordinaryReads)
        assertEquals(1, fixture.assets.ordinaryReads)
        assertEquals(0, fixture.documents.mutationReads)
        assertEquals(0, fixture.assets.mutationReads)
        assertEquals(listOf(DocumentCatalogOperation.BROWSE), fixture.catalog.operations)
        assertEquals(listOf(SOURCE_FOLDER_ID), fixture.catalog.requests)
        assertEquals(listOf(false), fixture.catalog.transactionStates)
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `revoked source ACL is caught by revalidation before the final transaction`() {
        val fixture = Fixture()
        fixture.catalog.revokeAfterFirstLookup = true

        assertFailsWith<DocumentNotFoundException> {
            fixture.service.submit(fixture.document.id)
        }

        assertEquals(listOf(SOURCE_FOLDER_ID, SOURCE_FOLDER_ID), fixture.catalog.requests)
        assertTrue(fixture.catalog.transactionStates.none { it })
        assertEquals(0, fixture.documents.mutationReads)
        assertEquals(0, fixture.assets.mutationReads)
        assertEquals(LifecycleState.DRAFT, fixture.document.lifecycleState)
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `binding race is rejected under document then asset lock without an audit`() {
        val fixture = Fixture()
        fixture.documents.beforeMutation = {
            fixture.assets.replaceBinding("finance")
        }

        assertFailsWith<DocumentCatalogBindingChangedException> {
            fixture.service.submit(fixture.document.id)
        }

        val documentLock = fixture.events.indexOf("document:lock")
        val assetLock = fixture.events.indexOf("asset:lock")
        assertTrue(documentLock >= 0)
        assertTrue(assetLock > documentLock)
        assertEquals(0, fixture.documents.saves)
        assertEquals(LifecycleState.DRAFT, fixture.document.lifecycleState)
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `malicious cross tenant repository results fail before catalog and retain internal asset semantics`() {
        val foreignDocument = Fixture()
        foreignDocument.documents.snapshotOverride = { _, _ ->
            Document(
                id = DOCUMENT_ID,
                tenantId = FOREIGN_TENANT_ID,
                assetId = ASSET_ID,
                documentNumber = "FOREIGN-001",
                title = "Foreign",
            )
        }

        assertFailsWith<DocumentNotFoundException> {
            foreignDocument.service.submit(DOCUMENT_ID)
        }
        assertTrue(foreignDocument.catalog.requests.isEmpty())
        assertTrue(foreignDocument.audits.records.isEmpty())

        listOf<FileAsset?>(
            null,
            FileAsset(ASSET_ID, FOREIGN_TENANT_ID, FILE_ID, "DOCUMENT"),
            FileAsset(Identifier("asset-wrong"), TENANT_ID, FILE_ID, "DOCUMENT"),
        ).forEach { maliciousAsset ->
            val fixture = Fixture()
            fixture.assets.snapshotOverride = { _, _ -> maliciousAsset }

            assertFailsWith<IllegalStateException> {
                fixture.service.submit(DOCUMENT_ID)
            }
            assertTrue(fixture.catalog.requests.isEmpty())
            assertTrue(fixture.audits.records.isEmpty())
        }
    }

    @Test
    fun `first and last approvals both keep catalog checks outside transactions and lock in order`() {
        val fixture = Fixture(
            initialDocument = pendingDocument(),
            initialWorkflow = dualWorkflow(DOCUMENT_ID),
            currentUser = UserIdentity(REVIEWER_ONE_ID, "Reviewer One"),
        )

        val afterFirst = fixture.service.approve(WORKFLOW_ID, TASK_ONE_ID, "first")

        assertEquals(LifecycleState.PENDING_REVIEW, afterFirst.lifecycleState)
        assertEquals(WorkflowState.PENDING, fixture.workflows.workflow?.state)
        assertTrue(fixture.outbox.events.isEmpty())
        assertEquals(1, fixture.audits.records.size)
        assertDecisionLockOrder(fixture.events)

        fixture.users.current = UserIdentity(REVIEWER_TWO_ID, "Reviewer Two")
        fixture.events.clear()
        val afterLast = fixture.service.approve(WORKFLOW_ID, TASK_TWO_ID, "last")

        assertEquals(LifecycleState.PUBLISHING, afterLast.lifecycleState)
        assertEquals(WorkflowState.APPROVED, fixture.workflows.workflow?.state)
        assertEquals(1, fixture.outbox.events.size)
        assertEquals(2, fixture.audits.records.size)
        assertDecisionLockOrder(fixture.events)
        assertEquals(listOf(false), fixture.deliveryResolutionTransactionStates)
        assertTrue(fixture.catalog.operations.all { operation -> operation == DocumentCatalogOperation.BROWSE })
        assertTrue(fixture.catalog.transactionStates.none { it })
    }

    @Test
    fun `active review and route races use the typed review conflict without persistence`() {
        val active = Fixture(initialWorkflow = dualWorkflow(DOCUMENT_ID))

        assertFailsWith<DocumentReviewConflictException> {
            active.service.submitForReview(DOCUMENT_ID)
        }
        assertEquals(0, active.documents.saves)
        assertEquals(0, active.workflows.saves)
        assertTrue(active.audits.records.isEmpty())

        val routeRace = Fixture()
        routeRace.documents.beforeMutation = {
            routeRace.document.rename("Changed while resolving route")
        }

        assertFailsWith<DocumentReviewConflictException> {
            routeRace.service.submitForReview(DOCUMENT_ID)
        }
        assertEquals(LifecycleState.DRAFT, routeRace.document.lifecycleState)
        assertEquals(0, routeRace.documents.saves)
        assertEquals(0, routeRace.workflows.saves)
        assertTrue(routeRace.audits.records.isEmpty())
    }

    @Test
    fun `reject enforces the complete catalog lifecycle boundary`() {
        val fixture = Fixture(initialDocument = pendingDocument())

        fixture.service.reject(DOCUMENT_ID)

        assertLifecycleBoundary(
            fixture = fixture,
            authorizationAction = "document:reject",
            expectedState = LifecycleState.REJECTED,
            auditAction = "document:reject",
        )
    }

    @Test
    fun `revise enforces the complete catalog lifecycle boundary`() {
        val fixture = Fixture(initialDocument = rejectedDocument())

        fixture.service.revise(DOCUMENT_ID)

        assertLifecycleBoundary(
            fixture = fixture,
            authorizationAction = "document:revise",
            expectedState = LifecycleState.DRAFT,
            auditAction = "document:revise",
        )
    }

    @Test
    fun `review submission enforces the complete catalog lifecycle boundary`() {
        val fixture = Fixture()

        val workflow = fixture.service.submitForReview(DOCUMENT_ID, REVIEWER_ONE_ID, null)

        assertEquals(WorkflowState.PENDING, workflow.state)
        assertEquals(1, fixture.workflows.saves)
        assertLifecycleBoundary(
            fixture = fixture,
            authorizationAction = "document:submit",
            expectedState = LifecycleState.PENDING_REVIEW,
            auditAction = "document:review:submit",
        )
    }

    @Test
    fun `review rejection enforces the complete catalog lifecycle boundary`() {
        val fixture = Fixture(
            initialDocument = pendingDocument(),
            initialWorkflow = dualWorkflow(DOCUMENT_ID),
        )

        fixture.service.rejectReview(WORKFLOW_ID, TASK_ONE_ID, "Needs revision")

        assertEquals(WorkflowState.REJECTED, fixture.workflows.workflow?.state)
        assertEquals(1, fixture.workflows.saves)
        assertLifecycleBoundary(
            fixture = fixture,
            authorizationAction = "document:audit",
            expectedState = LifecycleState.REJECTED,
            auditAction = "document:review:reject",
            workflowDecision = true,
        )
    }

    @Test
    fun `publish enforces the complete catalog lifecycle boundary`() {
        val fixture = Fixture(initialDocument = pendingDocument())

        fixture.service.publish(DOCUMENT_ID, "default")

        assertEquals(1, fixture.outbox.events.size)
        assertLifecycleBoundary(
            fixture = fixture,
            authorizationAction = "document:publish",
            expectedState = LifecycleState.PUBLISHING,
            auditAction = "document:publish:request",
        )
    }

    @Test
    fun `offline enforces the complete catalog lifecycle boundary`() {
        val fixture = Fixture(initialDocument = publishedDocument())

        fixture.service.offline(DOCUMENT_ID)

        assertLifecycleBoundary(
            fixture = fixture,
            authorizationAction = "document:offline",
            expectedState = LifecycleState.OFFLINE,
            auditAction = "document:offline",
        )
    }

    @Test
    fun `restore enforces the complete catalog lifecycle boundary`() {
        val fixture = Fixture(initialDocument = offlineDocument())

        fixture.service.restore(DOCUMENT_ID)

        assertLifecycleBoundary(
            fixture = fixture,
            authorizationAction = "document:restore",
            expectedState = LifecycleState.DRAFT,
            auditAction = "document:restore",
        )
    }

    @Test
    fun `archive enforces the complete catalog lifecycle boundary`() {
        val fixture = Fixture(initialDocument = publishedDocument())

        fixture.service.archive(DOCUMENT_ID)

        assertLifecycleBoundary(
            fixture = fixture,
            authorizationAction = "document:archive",
            expectedState = LifecycleState.HISTORY,
            auditAction = "document:archive",
        )
    }

    @Test
    fun `malicious final document lock results fail closed without persistence or audit`() {
        val maliciousDocuments = listOf(
            document(DOCUMENT_ID, FOREIGN_TENANT_ID, ASSET_ID),
            document(OTHER_DOCUMENT_ID, TENANT_ID, ASSET_ID),
            document(DOCUMENT_ID, TENANT_ID, OTHER_ASSET_ID),
        )

        maliciousDocuments.forEach { maliciousDocument ->
            val fixture = Fixture()
            fixture.documents.mutationOverride = { _, _ -> maliciousDocument }

            val failure = assertFailsWith<RuntimeException> {
                fixture.service.submit(DOCUMENT_ID)
            }

            if (maliciousDocument.tenantId != TENANT_ID || maliciousDocument.id != DOCUMENT_ID) {
                assertTrue(failure is DocumentNotFoundException)
            } else {
                assertTrue(failure is DocumentCatalogBindingChangedException)
            }

            assertNoFinalMutationSideEffects(fixture)
            assertEquals(LifecycleState.DRAFT, fixture.document.lifecycleState)
        }
    }

    @Test
    fun `malicious final asset lock results fail closed without persistence or audit`() {
        val maliciousAssets = listOf(
            fileAsset(ASSET_ID, FOREIGN_TENANT_ID),
            fileAsset(OTHER_ASSET_ID, TENANT_ID),
        )

        maliciousAssets.forEach { maliciousAsset ->
            val fixture = Fixture()
            fixture.assets.mutationOverride = { _, _ -> maliciousAsset }

            assertFailsWith<DocumentCatalogBindingChangedException> {
                fixture.service.submit(DOCUMENT_ID)
            }

            assertNoFinalMutationSideEffects(fixture)
            assertEquals(LifecycleState.DRAFT, fixture.document.lifecycleState)
        }
    }

    @Test
    fun `malicious final workflow lock results fail closed without persistence or audit`() {
        val maliciousWorkflows = listOf(
            workflow(WORKFLOW_ID, FOREIGN_TENANT_ID, DOCUMENT_ID),
            workflow(OTHER_WORKFLOW_ID, TENANT_ID, DOCUMENT_ID),
            workflow(WORKFLOW_ID, TENANT_ID, OTHER_DOCUMENT_ID),
        )

        maliciousWorkflows.forEach { maliciousWorkflow ->
            val fixture = Fixture(
                initialDocument = pendingDocument(),
                initialWorkflow = dualWorkflow(DOCUMENT_ID),
            )
            fixture.workflows.decisionOverride = { _, _ -> maliciousWorkflow }

            assertFailsWith<WorkflowNotFoundException> {
                fixture.service.rejectReview(WORKFLOW_ID, TASK_ONE_ID, "reject")
            }

            assertNoFinalMutationSideEffects(fixture)
            assertEquals(LifecycleState.PENDING_REVIEW, fixture.document.lifecycleState)
            assertDecisionLockOrder(fixture.events)
        }
    }

    private fun assertLifecycleBoundary(
        fixture: Fixture,
        authorizationAction: String,
        expectedState: LifecycleState,
        auditAction: String,
        workflowDecision: Boolean = false,
    ) {
        assertEquals(expectedState, fixture.document.lifecycleState)
        assertEquals(List(3) { authorizationAction }, fixture.authorization.actions)
        assertEquals(3, fixture.authorization.requests.size)
        assertTrue(fixture.authorization.requests.all { request ->
            request.resource.id == DOCUMENT_ID &&
                request.resource.type == "DOCUMENT" &&
                request.resource.tenantId == TENANT_ID
        })
        assertEquals(listOf(SOURCE_FOLDER_ID, SOURCE_FOLDER_ID), fixture.catalog.requests)
        assertEquals(
            listOf(DocumentCatalogOperation.BROWSE, DocumentCatalogOperation.BROWSE),
            fixture.catalog.operations,
        )
        assertEquals(listOf(false, false), fixture.catalog.transactionStates)
        assertEquals(1, fixture.documents.mutationReads)
        assertEquals(1, fixture.assets.mutationReads)
        assertEquals(1, fixture.documents.saves)
        assertEquals(0, fixture.assets.saves)
        if (workflowDecision) {
            assertDecisionLockOrder(fixture.events)
        } else {
            assertDocumentAssetLockOrder(fixture.events)
        }
        val audit = fixture.audits.records.single()
        assertEquals(TENANT_ID, audit.tenantId)
        assertEquals("DOCUMENT", audit.resourceType)
        assertEquals(DOCUMENT_ID, audit.resourceId)
        assertEquals(auditAction, audit.action)
        assertEquals(REVIEWER_ONE_ID, audit.operatorId)
        assertEquals("Reviewer One", audit.operatorName)
    }

    private fun assertNoFinalMutationSideEffects(fixture: Fixture) {
        assertEquals(0, fixture.documents.saves)
        assertEquals(0, fixture.assets.saves)
        assertEquals(0, fixture.workflows.saves)
        assertTrue(fixture.audits.records.isEmpty())
        assertTrue(fixture.outbox.events.isEmpty())
    }

    private fun assertDocumentAssetLockOrder(events: List<String>) {
        val documentLock = events.indexOf("document:lock")
        val assetLock = events.indexOf("asset:lock")
        assertTrue(documentLock >= 0)
        assertTrue(assetLock > documentLock)
    }

    private fun assertDecisionLockOrder(events: List<String>) {
        val documentLock = events.indexOf("document:lock")
        val assetLock = events.indexOf("asset:lock")
        val workflowLock = events.indexOf("workflow:lock")
        assertTrue(documentLock >= 0)
        assertTrue(assetLock > documentLock)
        assertTrue(workflowLock > assetLock)
    }

    private class Fixture(
        initialDocument: Document = draftDocument(),
        initialWorkflow: WorkflowInstance? = null,
        currentUser: UserIdentity? = UserIdentity(REVIEWER_ONE_ID, "Reviewer One"),
        allowed: Boolean = true,
        catalogVisible: Boolean = true,
        authorizationDecision: (AuthorizationRequest) -> Boolean = { allowed },
    ) {
        val events = mutableListOf<String>()
        val transaction = TrackingTransaction(events)
        val document: Document
            get() = requireNotNull(documents.document)
        val documents = RecordingDocuments(initialDocument, transaction, events)
        val assets = RecordingAssets(asset(initialDocument), transaction, events)
        val workflows = RecordingWorkflows(initialWorkflow, transaction, events)
        val audits = RecordingAudits()
        val outbox = RecordingOutbox()
        val users = MutableUsers(currentUser)
        val authorization = RecordingAuthorization(authorizationDecision, transaction)
        val catalog = RecordingCatalog(catalogVisible, transaction, events)
        val deliveryResolutionTransactionStates = mutableListOf<Boolean>()
        private val tenants = object : TenantProvider {
            override fun currentTenant() = TenantContext(TENANT_ID)
        }
        private val ids = CountingIds()
        private val auditTrail = AuditTrail(
            audits,
            ids,
            Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
        )
        private val deliveryPlanner = deliveryPlanner(
            transaction,
            deliveryResolutionTransactionStates,
            workflows.deliveries,
            outbox,
            ids,
        )
        private val commands = DocumentCommandService(
            tenants,
            users,
            authorization,
            documents,
            transaction,
            auditTrail,
        )
        private val reviews = DocumentReviewWorkflowService(
            tenants,
            users,
            authorization,
            documents,
            workflows,
            deliveryPlanner,
            ids,
            transaction,
            auditTrail,
        )
        private val publish = PublishDocumentService(
            tenants,
            users,
            authorization,
            documents,
            deliveryPlanner,
            transaction,
            auditTrail,
            workflows,
        )
        private val offline = OfflineDocumentService(
            tenants,
            users,
            authorization,
            documents,
            transaction,
            auditTrail,
        )
        private val restore = RestoreOfflineDocumentService(
            tenants,
            users,
            authorization,
            documents,
            workflows.deliveries,
            transaction,
            auditTrail,
        )
        private val archive = ArchiveDocumentService(
            tenants,
            users,
            authorization,
            documents,
            transaction,
            auditTrail,
        )
        private val catalogAccess = DocumentCatalogAccessService(
            tenants,
            users,
            authorization,
            catalog,
        )
        val service = DocumentCatalogLifecycleService(
            commands,
            reviews,
            publish,
            offline,
            restore,
            archive,
            catalogAccess,
            documents,
            assets,
            transaction,
        )
    }

    private class TrackingTransaction(
        private val events: MutableList<String>,
    ) : ApplicationTransaction {
        var active: Boolean = false
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested transaction is not expected." }
            events += "transaction:start"
            active = true
            return try {
                action()
            } finally {
                active = false
                events += "transaction:end"
            }
        }
    }

    private class RecordingDocuments(
        var document: Document?,
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : DocumentRepository {
        var ordinaryReads = 0
        var mutationReads = 0
        var saves = 0
        var snapshotOverride: ((Identifier, Identifier) -> Document?)? = null
        var mutationOverride: ((Identifier, Identifier) -> Document?)? = null
        var beforeMutation: (() -> Unit)? = null

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? {
            assertTrue(transaction.active)
            ordinaryReads++
            events += "document:snapshot"
            snapshotOverride?.let { return it(tenantId, documentId) }
            return document?.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == documentId }
        }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            assertTrue(transaction.active)
            mutationReads++
            beforeMutation?.invoke()
            events += "document:lock"
            mutationOverride?.let { return it(tenantId, documentId) }
            return document?.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == documentId }
        }

        override fun save(document: Document) {
            assertTrue(transaction.active)
            this.document = document
            saves++
        }
    }

    private class RecordingAssets(
        var asset: FileAsset,
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : FileAssetMutationRepository {
        var ordinaryReads = 0
        var mutationReads = 0
        var saves = 0
        var snapshotOverride: ((Identifier, Identifier) -> FileAsset?)? = null
        var mutationOverride: ((Identifier, Identifier) -> FileAsset?)? = null

        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? {
            assertTrue(transaction.active)
            ordinaryReads++
            events += "asset:snapshot"
            snapshotOverride?.let { return it(tenantId, fileAssetId) }
            return asset.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == fileAssetId }
        }

        override fun findForMutation(tenantId: Identifier, fileAssetId: Identifier): FileAsset? {
            assertTrue(transaction.active)
            mutationReads++
            events += "asset:lock"
            mutationOverride?.let { return it(tenantId, fileAssetId) }
            return asset.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == fileAssetId }
        }

        override fun save(fileAsset: FileAsset) {
            assertTrue(transaction.active)
            asset = fileAsset
            saves++
        }

        fun replaceBinding(folderId: String) {
            asset = FileAsset(
                asset.id,
                asset.tenantId,
                asset.fileObjectId,
                asset.assetType,
                asset.metadata + (DocumentCatalogBinding.METADATA_KEY to folderId),
            )
        }
    }

    private class RecordingWorkflows(
        var workflow: WorkflowInstance?,
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : WorkflowInstanceRepository {
        var ordinaryReads = 0
        var decisionReads = 0
        var saves = 0
        var decisionOverride: ((Identifier, Identifier) -> WorkflowInstance?)? = null
        val deliveries = RecordingDeliveries()

        override fun findById(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? {
            assertTrue(transaction.active)
            ordinaryReads++
            events += "workflow:snapshot"
            return workflow?.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == workflowId }
        }

        override fun findForDecision(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? {
            assertTrue(transaction.active)
            decisionReads++
            events += "workflow:lock"
            decisionOverride?.let { return it(tenantId, workflowId) }
            return workflow?.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == workflowId }
        }

        override fun findActiveByDocument(tenantId: Identifier, documentId: Identifier): WorkflowInstance? {
            assertTrue(transaction.active)
            events += "workflow:active"
            return workflow?.takeIf { candidate ->
                candidate.tenantId == tenantId &&
                    candidate.documentId == documentId &&
                    candidate.state == WorkflowState.PENDING
            }
        }

        override fun save(workflow: WorkflowInstance) {
            assertTrue(transaction.active)
            this.workflow = workflow
            saves++
        }
    }

    private class RecordingCatalog(
        visible: Boolean,
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : DocumentCatalogProvider {
        private var visible = visible
        var revokeAfterFirstLookup = false
        val requests = mutableListOf<String>()
        val operations = mutableListOf<DocumentCatalogOperation>()
        val transactionStates = mutableListOf<Boolean>()

        override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = emptyList()

        override fun findFolder(
            request: DocumentCatalogAccessRequest,
            folderId: String,
        ): DocumentCatalogFolder? {
            transactionStates += transaction.active
            assertFalse(transaction.active, "Catalog ACL must run outside FileWeft transactions.")
            requests += folderId
            operations += request.operation
            events += "catalog:$folderId"
            val result = if (visible) DocumentCatalogFolder(folderId, null, "Folder $folderId") else null
            if (revokeAfterFirstLookup && requests.size == 1) {
                visible = false
            }
            return result
        }
    }

    private class MutableUsers(var current: UserIdentity?) : UserRealmProvider {
        override fun currentUser(): UserIdentity? = current
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private class RecordingAuthorization(
        private val decision: (AuthorizationRequest) -> Boolean,
        private val transaction: TrackingTransaction,
    ) : AuthorizationProvider {
        val actions = mutableListOf<String>()
        val requests = mutableListOf<AuthorizationRequest>()

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            assertFalse(transaction.active, "Base authorization must not run inside a FileWeft transaction.")
            actions += request.action.name
            requests += request
            return AuthorizationDecision(decision(request), "denied")
        }
    }

    private class RecordingAudits : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) {
            records += record
        }

        override fun findByResource(
            tenantId: Identifier,
            resourceType: String,
            resourceId: Identifier,
            limit: Int,
        ): List<AuditRecord> = emptyList()
    }

    private class RecordingOutbox : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()
        override fun append(event: OutboxEvent) {
            events += event
        }
    }

    private class RecordingDeliveries : DocumentDeliveryTargetRepository {
        val values = mutableListOf<DocumentDeliveryTarget>()
        override fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? =
            values.firstOrNull { target -> target.tenantId == tenantId && target.id == deliveryId }

        override fun findByDocument(tenantId: Identifier, documentId: Identifier): List<DocumentDeliveryTarget> =
            values.filter { target -> target.tenantId == tenantId && target.documentId == documentId }

        override fun save(target: DocumentDeliveryTarget) {
            values.removeAll { existing -> existing.id == target.id }
            values += target
        }
    }

    private class CountingIds : IdentifierGenerator {
        private var sequence = 0
        override fun nextId(): Identifier = Identifier("generated-${++sequence}")
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-a")
        val FOREIGN_TENANT_ID = Identifier("tenant-b")
        val DOCUMENT_ID = Identifier("document-1")
        val OTHER_DOCUMENT_ID = Identifier("document-other")
        val ASSET_ID = Identifier("asset-1")
        val OTHER_ASSET_ID = Identifier("asset-other")
        val FILE_ID = Identifier("file-1")
        val VERSION_ID = Identifier("version-1")
        val WORKFLOW_ID = Identifier("workflow-1")
        val OTHER_WORKFLOW_ID = Identifier("workflow-other")
        val TASK_ONE_ID = Identifier("task-1")
        val TASK_TWO_ID = Identifier("task-2")
        val REVIEWER_ONE_ID = Identifier("reviewer-1")
        val REVIEWER_TWO_ID = Identifier("reviewer-2")
        const val SOURCE_FOLDER_ID = "contracts"

        fun draftDocument(): Document = Document(
            id = DOCUMENT_ID,
            tenantId = TENANT_ID,
            assetId = ASSET_ID,
            documentNumber = "DOC-001",
            title = "Contract",
            versions = listOf(
                DocumentVersion(VERSION_ID, TENANT_ID, DOCUMENT_ID, "1.0", FILE_ID),
            ),
            currentVersionId = VERSION_ID,
        )

        fun pendingDocument(): Document = draftDocument().also { document ->
            document.transition(LifecycleCommand.SUBMIT)
        }

        fun rejectedDocument(): Document = pendingDocument().also { document ->
            document.transition(LifecycleCommand.REJECT)
        }

        fun publishedDocument(): Document = pendingDocument().also { document ->
            document.transition(LifecycleCommand.APPROVE)
            document.transition(LifecycleCommand.PUBLISH_SUCCEEDED)
        }

        fun offlineDocument(): Document = publishedDocument().also { document ->
            document.transition(LifecycleCommand.OFFLINE)
        }

        fun document(id: Identifier, tenantId: Identifier, assetId: Identifier): Document = Document(
            id = id,
            tenantId = tenantId,
            assetId = assetId,
            documentNumber = "MALICIOUS-001",
            title = "Untrusted repository result",
        )

        fun asset(document: Document): FileAsset = FileAsset(
            id = document.assetId,
            tenantId = document.tenantId,
            fileObjectId = FILE_ID,
            assetType = "DOCUMENT",
            metadata = mapOf(DocumentCatalogBinding.METADATA_KEY to SOURCE_FOLDER_ID),
        )

        fun fileAsset(id: Identifier, tenantId: Identifier): FileAsset = FileAsset(
            id = id,
            tenantId = tenantId,
            fileObjectId = FILE_ID,
            assetType = "DOCUMENT",
            metadata = mapOf(DocumentCatalogBinding.METADATA_KEY to SOURCE_FOLDER_ID),
        )

        fun dualWorkflow(documentId: Identifier): WorkflowInstance = WorkflowInstance(
            id = WORKFLOW_ID,
            tenantId = TENANT_ID,
            documentId = documentId,
            workflowType = "DUAL_REVIEW",
            tasks = listOf(
                WorkflowTask(TASK_ONE_ID, TENANT_ID, WORKFLOW_ID, REVIEWER_ONE_ID),
                WorkflowTask(TASK_TWO_ID, TENANT_ID, WORKFLOW_ID, REVIEWER_TWO_ID),
            ),
        )

        fun workflow(
            id: Identifier,
            tenantId: Identifier,
            documentId: Identifier,
        ): WorkflowInstance = WorkflowInstance(
            id = id,
            tenantId = tenantId,
            documentId = documentId,
            workflowType = "UNTRUSTED",
            tasks = listOf(
                WorkflowTask(TASK_ONE_ID, tenantId, id, REVIEWER_ONE_ID),
            ),
        )

        fun deliveryPlanner(
            transaction: TrackingTransaction,
            resolutionStates: MutableList<Boolean>,
            deliveries: DocumentDeliveryTargetRepository,
            outbox: OutboxEventRepository,
            ids: IdentifierGenerator,
        ): DocumentDeliveryPlanner = DocumentDeliveryPlanner(
            profiles = object : DocumentDeliveryProfileProvider {
                override fun listProfiles(tenantId: Identifier): List<DocumentDeliveryProfile> {
                    resolutionStates += transaction.active
                    return listOf(
                        DocumentDeliveryProfile(
                            "default",
                            "Default",
                            listOf(
                                DocumentDeliveryTargetDefinition(
                                    "primary",
                                    "Primary",
                                    "primary",
                                    DeliveryRequirement.REQUIRED,
                                ),
                            ),
                        ),
                    )
                }
            },
            connectors = object : DeliveryConnectorResolver {
                override fun findConnector(connectorId: String): FileConnector = object : FileConnector {
                    override fun sync(request: ConnectorSyncRequest) = ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)
                    override fun remove(request: ConnectorRemoveRequest) = ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)
                    override fun health() = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
                }
            },
            deliveries = deliveries,
            outbox = outbox,
            identifiers = ids,
            clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
        )
    }
}
