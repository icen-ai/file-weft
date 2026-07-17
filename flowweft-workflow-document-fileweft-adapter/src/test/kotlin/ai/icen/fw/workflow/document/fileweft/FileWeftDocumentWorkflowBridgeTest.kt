package ai.icen.fw.workflow.document.fileweft

import ai.icen.fw.application.document.DocumentCommandService
import ai.icen.fw.application.document.DocumentDetailView
import ai.icen.fw.application.document.DocumentPageRequest
import ai.icen.fw.application.document.DocumentPageResult
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.application.document.DocumentSummaryView
import ai.icen.fw.application.document.DocumentVersionView
import ai.icen.fw.application.retention.DeletionVisibilityGuard
import ai.icen.fw.application.retention.DeletionVisibilityQuery
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.document.DocumentWorkflowAction
import ai.icen.fw.workflow.document.DocumentWorkflowAuthorizationPhase
import ai.icen.fw.workflow.document.DocumentWorkflowAuthorizationRequest
import ai.icen.fw.workflow.document.DocumentWorkflowAuthorizationStatus
import ai.icen.fw.workflow.document.DocumentWorkflowDocumentMutationAction
import ai.icen.fw.workflow.document.DocumentWorkflowDocumentMutationRequest
import ai.icen.fw.workflow.document.DocumentWorkflowPortOutcome
import ai.icen.fw.workflow.document.DocumentWorkflowRevisionPolicyRef
import ai.icen.fw.workflow.document.DocumentWorkflowSelection
import ai.icen.fw.workflow.document.DocumentWorkflowSubjectResolveRequest
import ai.icen.fw.workflow.document.DocumentWorkflowTemplateRef
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileWeftDocumentWorkflowBridgeTest {
    @Test
    fun `subject resolution uses authorized FileWeft query and immutable version projection`() {
        val fixture = Fixture()

        val first = fixture.bridge.resolve(fixture.resolveRequest())
        val second = fixture.bridge.resolve(fixture.resolveRequest())

        assertNotNull(first)
        assertEquals("document", first.snapshot.ref.type)
        assertEquals(fixture.document.id.value, first.snapshot.ref.id)
        assertEquals(fixture.version.id.value, first.snapshot.revision)
        assertEquals(64, first.snapshot.digest.length)
        assertEquals(first.snapshot, second?.snapshot)
        assertEquals("draft", first.lifecycle.code)
        assertTrue(fixture.authorization.actions.count { it == "document:read" } >= 2)
    }

    @Test
    fun `caller tenant and actor cannot replace ambient trusted context`() {
        val fixture = Fixture()
        val foreignContext = WorkflowTrustedCallContext.of(
            "other-tenant",
            WorkflowPrincipalRef.of("user", fixture.user.id.value),
            "authentication-1",
            DIGEST,
        )

        assertNull(fixture.bridge.resolve(fixture.resolveRequest(foreignContext)))
        assertEquals(0, fixture.queries.detailReads)
    }

    @Test
    fun `authorization is current exact and bound to the workflow request`() {
        val fixture = Fixture()
        val subject = assertNotNull(fixture.bridge.resolve(fixture.resolveRequest())).snapshot
        val request = fixture.authorizationRequest(subject)

        val decision = fixture.bridge.authorize(request)

        assertEquals(DocumentWorkflowAuthorizationStatus.AUTHORIZED, decision.status)
        assertTrue(decision.matches(request, NOW))
        assertTrue(fixture.authorization.actions.contains("document:submit"))
    }

    @Test
    fun `submit reuses locked FileWeft command and proves unchanged immutable version`() {
        val fixture = Fixture()
        val subject = assertNotNull(fixture.bridge.resolve(fixture.resolveRequest())).snapshot
        val request = fixture.mutationRequest(
            DocumentWorkflowDocumentMutationAction.SUBMIT_FOR_REVIEW,
            subject,
            0L,
        )

        val result = fixture.bridge.mutate(request)

        assertEquals(DocumentWorkflowPortOutcome.APPLIED, result.outcome)
        assertEquals(subject, result.subject)
        assertNotNull(result.receiptDigest)
        assertEquals(LifecycleState.PENDING_REVIEW, fixture.document.lifecycleState)
    }

    @Test
    fun `pending state without durable old-use-case receipt is not falsely replayed`() {
        val fixture = Fixture()
        val subject = assertNotNull(fixture.bridge.resolve(fixture.resolveRequest())).snapshot
        val request = fixture.mutationRequest(
            DocumentWorkflowDocumentMutationAction.SUBMIT_FOR_REVIEW,
            subject,
            0L,
        )
        assertEquals(DocumentWorkflowPortOutcome.APPLIED, fixture.bridge.mutate(request).outcome)

        val replay = fixture.bridge.mutate(request)

        assertEquals(DocumentWorkflowPortOutcome.OUTCOME_UNKNOWN, replay.outcome)
        assertEquals("idempotency-evidence-unavailable", replay.failureCode)
    }

    @Test
    fun `revision cycle is explicitly unsupported when host application lacks atomic use case`() {
        val fixture = Fixture()
        val subject = assertNotNull(fixture.bridge.resolve(fixture.resolveRequest())).snapshot
        assertEquals(
            DocumentWorkflowPortOutcome.APPLIED,
            fixture.bridge.mutate(
                fixture.mutationRequest(
                    DocumentWorkflowDocumentMutationAction.SUBMIT_FOR_REVIEW,
                    subject,
                    0L,
                ),
            ).outcome,
        )

        val correction = fixture.bridge.mutate(
            fixture.mutationRequest(
                DocumentWorkflowDocumentMutationAction.OPEN_REVISION_DRAFT,
                subject,
                1L,
            ),
        )

        assertEquals(DocumentWorkflowPortOutcome.REJECTED, correction.outcome)
        assertEquals(
            FileWeftDocumentRevisionCycleApplicationFacade.UNSUPPORTED_FAILURE_CODE,
            correction.failureCode,
        )
        assertEquals(LifecycleState.PENDING_REVIEW, fixture.document.lifecycleState)
    }

    private class Fixture {
        val tenantId = Identifier("tenant-1")
        val user = UserIdentity(Identifier("alice"), "Alice")
        val version = DocumentVersion(
            Identifier("version-1"),
            tenantId,
            Identifier("document-1"),
            "1",
            Identifier("file-object-1"),
        )
        val document = Document(
            Identifier("document-1"),
            tenantId,
            Identifier("asset-1"),
            "DOC-1",
            "Contract",
            versions = listOf(version),
            currentVersionId = version.id,
        )
        val authorization = RecordingAuthorizationProvider()
        val queries = ProjectionRepository(document, version)
        private val transaction = object : ApplicationTransaction {
            override fun <T> execute(action: () -> T): T = action()
        }
        private val tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(tenantId)
        }
        private val users = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = user
            override fun findUser(userId: Identifier): UserIdentity? = user.takeIf { it.id == userId }
        }
        private val documents = object : DocumentRepository {
            override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
                document.takeIf { it.tenantId == tenantId && it.id == documentId }

            override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? =
                findById(tenantId, documentId)

            override fun save(document: Document) = Unit
        }
        private val deletionVisibility = DeletionVisibilityGuard.create(object : DeletionVisibilityQuery {
            override fun findFence(
                tenantId: Identifier,
                resourceType: String,
                resourceId: Identifier,
            ) = null
        })
        private val queryService = DocumentQueryService(
            tenantProvider,
            users,
            authorization,
            queries,
            transaction,
            null,
            deletionVisibility,
        )
        private val commandService = DocumentCommandService(
            tenantProvider,
            users,
            authorization,
            documents,
            transaction,
        )
        val bridge = FileWeftDocumentWorkflowBridge(
            tenantProvider,
            users,
            authorization,
            queryService,
            commandService,
        )
        val context = WorkflowTrustedCallContext.of(
            tenantId.value,
            WorkflowPrincipalRef.of("user", user.id.value),
            "authentication-1",
            DIGEST,
        )
        val selection = DocumentWorkflowSelection.of(
            "definition-id-1",
            WorkflowDefinitionRef.of("document-approval", "1", DIGEST),
            DocumentWorkflowTemplateRef.of("knowledge-file", "1", DIGEST),
            DocumentWorkflowRevisionPolicyRef.of("1", DIGEST, "revise-node"),
            "authority-1",
        )

        fun resolveRequest(callContext: WorkflowTrustedCallContext = context) =
            DocumentWorkflowSubjectResolveRequest.of(
                callContext,
                DocumentWorkflowAction.SUBMIT,
                WorkflowSubjectRef.of("document", document.id.value),
                null,
                DIGEST,
                NOW,
            )

        fun authorizationRequest(subject: ai.icen.fw.workflow.api.WorkflowSubjectSnapshot) =
            DocumentWorkflowAuthorizationRequest.of(
                context,
                DocumentWorkflowAuthorizationPhase.COMMIT,
                DocumentWorkflowAction.SUBMIT,
                "instance-1",
                subject,
                selection,
                DIGEST,
                DIGEST,
                0L,
                1L,
                0L,
                NOW,
            )

        fun mutationRequest(
            action: DocumentWorkflowDocumentMutationAction,
            subject: ai.icen.fw.workflow.api.WorkflowSubjectSnapshot,
            cycleNumber: Long,
        ) = DocumentWorkflowDocumentMutationRequest.of(
            context,
            action,
            "instance-1",
            subject,
            selection,
            cycleNumber,
            DIGEST,
            DIGEST,
            DIGEST,
            NOW,
        )
    }

    private class RecordingAuthorizationProvider : AuthorizationProvider {
        val actions = ArrayList<String>()

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            actions += request.action.name
            return AuthorizationDecision(true, "allowed")
        }
    }

    private class ProjectionRepository(
        private val document: Document,
        private val version: DocumentVersion,
    ) : DocumentQueryRepository {
        var detailReads: Int = 0

        override fun findDetail(
            tenantId: Identifier,
            documentId: Identifier,
            folderReadScope: ai.icen.fw.application.document.DocumentFolderReadScope?,
        ): DocumentDetailView? {
            detailReads++
            if (document.tenantId != tenantId || document.id != documentId) return null
            return DocumentDetailView(
                DocumentSummaryView(
                    document.id,
                    document.documentNumber,
                    document.title,
                    document.lifecycleState,
                    1L,
                    2L,
                    document.currentVersionId,
                ),
                listOf(
                    DocumentVersionView(
                        version.id,
                        version.versionNumber,
                        "contract.pdf",
                        42L,
                        1L,
                        1L,
                        "application/pdf",
                    ),
                ),
            )
        }

        override fun findPage(
            tenantId: Identifier,
            request: DocumentPageRequest,
            folderReadScope: ai.icen.fw.application.document.DocumentFolderReadScope?,
        ): DocumentPageResult = DocumentPageResult(emptyList())
    }

    private companion object {
        const val NOW = 1_000L
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
