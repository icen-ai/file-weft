package ai.icen.fw.workflow.document

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.domain.WorkflowExecutionIds
import ai.icen.fw.workflow.runtime.WorkflowRuntimeCommandOptions
import ai.icen.fw.workflow.runtime.WorkflowRuntimeStartRequest
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentWorkflowAdapterTest {
    @Test
    fun `submission binds the exact caller subject definition template and idempotency key`() {
        val fixture = Fixture()

        val result = fixture.adapter.submit(fixture.submission())

        assertSame(DocumentWorkflowResultCode.STARTED, result.code)
        assertEquals(SUBJECT_V1, result.subject)
        assertEquals(2L, result.bindingRevision)
        assertEquals(2, fixture.authorization.calls.size)
        assertEquals(
            listOf(DocumentWorkflowAuthorizationPhase.PREPARE, DocumentWorkflowAuthorizationPhase.COMMIT),
            fixture.authorization.calls.map { it.phase },
        )
        assertEquals(listOf(DocumentWorkflowDocumentMutationAction.SUBMIT_FOR_REVIEW), fixture.document.calls.map { it.action })
        val start = fixture.workflow.starts.single()
        assertEquals(CONTEXT.contextDigest, start.callContext.contextDigest)
        assertEquals("instance-1", start.instanceId)
        assertEquals(SELECTION.definitionId, start.definitionId)
        assertEquals(SELECTION.definitionRef, start.definitionRef)
        assertEquals(SUBJECT_V1, start.subject)
        assertEquals("submission-key", start.options.idempotencyKey)
        assertSame(DocumentWorkflowBindingState.ACTIVE, fixture.bindings.current!!.state)
    }

    @Test
    fun `authorization revocation after reservation prevents every document and workflow mutation`() {
        val fixture = Fixture()
        fixture.authorization.denyCommit = true

        val result = fixture.adapter.submit(fixture.submission())

        assertSame(DocumentWorkflowResultCode.AUTHORIZATION_DENIED, result.code)
        assertEquals(2, fixture.authorization.calls.size)
        assertTrue(fixture.document.calls.isEmpty())
        assertTrue(fixture.workflow.starts.isEmpty())
        assertSame(DocumentWorkflowBindingState.TERMINAL, fixture.bindings.current!!.state)
    }

    @Test
    fun `successful submission replay reauthorizes without duplicating mutations`() {
        val fixture = Fixture()
        val request = fixture.submission()
        assertSame(DocumentWorkflowResultCode.STARTED, fixture.adapter.submit(request).code)
        val documentCalls = fixture.document.calls.size
        val workflowCalls = fixture.workflow.starts.size

        val replay = fixture.adapter.submit(request)

        assertSame(DocumentWorkflowResultCode.REPLAYED, replay.code)
        assertEquals(documentCalls, fixture.document.calls.size)
        assertEquals(workflowCalls, fixture.workflow.starts.size)
        assertEquals(4, fixture.authorization.calls.size)
    }

    @Test
    fun `an active binding blocks a second workflow for the same document`() {
        val fixture = Fixture()
        fixture.bindings.current = fixture.binding(
            state = DocumentWorkflowBindingState.ACTIVE,
            instanceId = "another-instance",
            startKey = "another-key",
            startDigest = C,
        )

        val result = fixture.adapter.submit(fixture.submission())

        assertSame(DocumentWorkflowResultCode.ACTIVE_BINDING_CONFLICT, result.code)
        assertTrue(fixture.document.calls.isEmpty())
        assertTrue(fixture.workflow.starts.isEmpty())
    }

    @Test
    fun `subject drift fails before uniqueness reservation`() {
        val fixture = Fixture()
        fixture.subject.snapshot = SUBJECT_V2

        val result = fixture.adapter.submit(fixture.submission())

        assertSame(DocumentWorkflowResultCode.SUBJECT_DRIFT, result.code)
        assertEquals(null, fixture.bindings.current)
        assertTrue(fixture.authorization.calls.isEmpty())
    }

    @Test
    fun `return without subject change explicitly delegates to generic workflow only`() {
        val fixture = Fixture()
        val request = DocumentWorkflowCorrectionRequest.of(
            CONTEXT,
            options("return-command", "return-key", 1L, 200L),
            "instance-1",
            4L,
            0L,
            SUBJECT_V1,
            SELECTION,
            DocumentWorkflowCorrectionMode.RETURN_WITHOUT_SUBJECT_CHANGE,
            D,
            E,
        )

        val result = fixture.adapter.requestCorrection(request)

        assertSame(DocumentWorkflowResultCode.WORKFLOW_ONLY_REQUIRED, result.code)
        assertTrue(fixture.subject.calls.isEmpty())
        assertTrue(fixture.document.calls.isEmpty())
        assertTrue(fixture.workflow.revisions.isEmpty())
    }

    @Test
    fun `revision cycle preserves old evidence then resumes the same instance on immutable replacement`() {
        val fixture = Fixture()
        assertSame(DocumentWorkflowResultCode.STARTED, fixture.adapter.submit(fixture.submission()).code)
        val active = fixture.bindings.current!!

        val correction = DocumentWorkflowCorrectionRequest.of(
            CONTEXT,
            options("correction-command", "correction-key", 1L, 200L),
            "instance-1",
            active.revision,
            0L,
            SUBJECT_V1,
            SELECTION,
            DocumentWorkflowCorrectionMode.REQUEST_SUBJECT_REVISION,
            D,
            E,
        )
        val waiting = fixture.adapter.requestCorrection(correction)

        assertSame(DocumentWorkflowResultCode.WAITING_FOR_SUBJECT_REVISION, waiting.code)
        assertEquals(1L, waiting.cycleNumber)
        assertSame(DocumentWorkflowBindingState.WAITING_SUBJECT_REVISION, fixture.bindings.current!!.state)
        assertEquals(SUBJECT_V1, fixture.bindings.current!!.subject)
        assertSame(DocumentWorkflowLifecycle.DRAFT, fixture.subject.lifecycle)
        assertEquals(SUBJECT_V1, fixture.workflow.revisions.single().previousSubject)
        assertEquals(SUBJECT_V1, fixture.workflow.revisions.single().replacementSubject)
        val openMutationCount = fixture.document.calls.size
        val pauseCount = fixture.workflow.revisions.size
        val correctionReplay = fixture.adapter.requestCorrection(correction)
        assertSame(DocumentWorkflowResultCode.REPLAYED, correctionReplay.code)
        assertEquals(openMutationCount, fixture.document.calls.size)
        assertEquals(pauseCount, fixture.workflow.revisions.size)

        fixture.subject.snapshot = SUBJECT_V2
        val revisionBinding = fixture.bindings.current!!
        val resume = DocumentWorkflowResumeRevisionRequest.of(
            CONTEXT,
            options("resume-command", "resume-key", 2L, 300L),
            "instance-1",
            revisionBinding.revision,
            1L,
            SUBJECT_V1,
            SUBJECT_V2,
            SELECTION,
            F,
            E,
        )
        val resumed = fixture.adapter.resumeRevision(resume)

        assertSame(DocumentWorkflowResultCode.SUBJECT_REVISION_RESUMED, resumed.code)
        assertEquals(SUBJECT_V2, resumed.subject)
        assertSame(DocumentWorkflowBindingState.ACTIVE, fixture.bindings.current!!.state)
        assertEquals(SUBJECT_V2, fixture.bindings.current!!.subject)
        assertEquals(1L, fixture.bindings.current!!.cycleNumber)
        assertSame(DocumentWorkflowLifecycle.PENDING_REVIEW, fixture.subject.lifecycle)
        assertEquals(SELECTION.revisionPolicy.resumeNodeId, SELECTION.revisionPolicy.resumeNodeId)
        assertEquals(SUBJECT_V2, fixture.workflow.revisions.last().replacementSubject)
        assertEquals(2, fixture.workflow.revisions.size)
        val resumeMutationCount = fixture.document.calls.size
        val revisionCommandCount = fixture.workflow.revisions.size
        val resumeReplay = fixture.adapter.resumeRevision(resume)
        assertSame(DocumentWorkflowResultCode.REPLAYED, resumeReplay.code)
        assertEquals(resumeMutationCount, fixture.document.calls.size)
        assertEquals(revisionCommandCount, fixture.workflow.revisions.size)
    }

    @Test
    fun `unknown workflow start outcome retains the unique binding for reconciliation`() {
        val fixture = Fixture()
        fixture.workflow.startOutcome = DocumentWorkflowPortOutcome.OUTCOME_UNKNOWN

        val result = fixture.adapter.submit(fixture.submission())

        assertSame(DocumentWorkflowResultCode.RECONCILIATION_REQUIRED, result.code)
        assertSame(DocumentWorkflowBindingState.RECONCILIATION_REQUIRED, fixture.bindings.current!!.state)
        assertEquals(1, fixture.document.calls.size)
        assertEquals(1, fixture.workflow.starts.size)
    }

    private class Fixture {
        val subject = SubjectPort()
        val selection = SelectionPort()
        val authorization = AuthorizationPort()
        val bindings = BindingPort()
        val document = DocumentPort(subject)
        val workflow = WorkflowPort()
        val adapter = DocumentWorkflowAdapter(
            subject,
            selection,
            authorization,
            bindings,
            document,
            workflow,
        )

        fun submission(): DocumentWorkflowSubmissionRequest = DocumentWorkflowSubmissionRequest.of(
            CONTEXT,
            options("submission-command", "submission-key", 0L, 100L),
            "instance-1",
            SUBJECT_V1,
            SELECTION,
            E,
        )

        fun binding(
            state: DocumentWorkflowBindingState,
            instanceId: String = "instance-1",
            subject: WorkflowSubjectSnapshot = SUBJECT_V1,
            cycle: Long = 0L,
            revision: Long = 1L,
            startKey: String = "submission-key",
            startDigest: String = submission().requestDigest,
        ): DocumentWorkflowBinding = DocumentWorkflowBinding.of(
            "tenant-a",
            SUBJECT_REF,
            instanceId,
            state,
            subject,
            SELECTION,
            startKey,
            startDigest,
            DocumentWorkflowAction.SUBMIT,
            startKey,
            startDigest,
            cycle,
            revision,
        )
    }

    private class SubjectPort : DocumentWorkflowSubjectApplicationPort {
        val calls = mutableListOf<DocumentWorkflowSubjectResolveRequest>()
        var snapshot = SUBJECT_V1
        var lifecycle = DocumentWorkflowLifecycle.DRAFT

        override fun resolve(request: DocumentWorkflowSubjectResolveRequest): DocumentWorkflowSubjectRecord {
            calls += request
            return DocumentWorkflowSubjectRecord.of(
                request.callContext.tenantId,
                request.callContext.actor,
                snapshot,
                lifecycle,
                "directory-revision-1",
                request.evaluatedAtEpochMilli + 1_000L,
            )
        }
    }

    private class SelectionPort : DocumentWorkflowSelectionApplicationPort {
        val calls = mutableListOf<DocumentWorkflowSelectionRequest>()
        override fun select(request: DocumentWorkflowSelectionRequest): DocumentWorkflowSelection {
            calls += request
            return SELECTION
        }
    }

    private class AuthorizationPort : DocumentWorkflowAuthorizationApplicationPort {
        val calls = mutableListOf<DocumentWorkflowAuthorizationRequest>()
        var denyCommit = false

        override fun authorize(request: DocumentWorkflowAuthorizationRequest): DocumentWorkflowAuthorizationDecision {
            calls += request
            val status = if (denyCommit && request.phase == DocumentWorkflowAuthorizationPhase.COMMIT) {
                DocumentWorkflowAuthorizationStatus.DENIED
            } else {
                DocumentWorkflowAuthorizationStatus.AUTHORIZED
            }
            return DocumentWorkflowAuthorizationDecision.of(
                "authorization-${calls.size}",
                request.callContext.tenantId,
                request.callContext.actor,
                request.requestDigest,
                status,
                "policy-revision-${calls.size}",
                A,
                request.evaluatedAtEpochMilli,
                request.evaluatedAtEpochMilli + 100L,
            )
        }
    }

    private class BindingPort : DocumentWorkflowBindingApplicationPort {
        var current: DocumentWorkflowBinding? = null

        override fun reserve(request: DocumentWorkflowBindingReserveRequest): DocumentWorkflowBindingReservation {
            val existing = current
            if (existing != null) {
                return if (existing.matches(request.submission)) {
                    DocumentWorkflowBindingReservation.accepted(
                        DocumentWorkflowBindingReservationCode.REPLAYED,
                        existing,
                    )
                } else {
                    DocumentWorkflowBindingReservation.rejected(
                        DocumentWorkflowBindingReservationCode.ACTIVE_CONFLICT,
                    )
                }
            }
            val created = DocumentWorkflowBinding.of(
                request.submission.callContext.tenantId,
                request.submission.expectedSubject.ref,
                request.submission.instanceId,
                DocumentWorkflowBindingState.RESERVED,
                request.submission.expectedSubject,
                request.submission.expectedSelection,
                request.submission.options.idempotencyKey,
                request.submission.requestDigest,
                request.submission.action,
                request.submission.options.idempotencyKey,
                request.submission.requestDigest,
                0L,
                1L,
            )
            current = created
            return DocumentWorkflowBindingReservation.accepted(
                DocumentWorkflowBindingReservationCode.RESERVED,
                created,
            )
        }

        override fun find(request: DocumentWorkflowBindingLookupRequest): DocumentWorkflowBinding? = current

        override fun transition(
            request: DocumentWorkflowBindingTransitionRequest,
        ): DocumentWorkflowBindingTransitionResult {
            val before = current ?: return DocumentWorkflowBindingTransitionResult.failure(
                DocumentWorkflowPortOutcome.REJECTED,
                "binding-missing",
            )
            if (before.revision != request.expectedRevision || before.state != request.expectedState ||
                before.subject != request.expectedSubject || before.cycleNumber != request.expectedCycleNumber
            ) return DocumentWorkflowBindingTransitionResult.failure(
                DocumentWorkflowPortOutcome.REJECTED,
                "binding-version-conflict",
            )
            val after = DocumentWorkflowBinding.of(
                before.tenantId,
                before.document,
                before.instanceId,
                request.targetState,
                request.targetSubject,
                before.selection,
                before.startIdempotencyKey,
                before.startRequestDigest,
                request.action,
                request.idempotencyKey,
                request.logicalRequestDigest,
                request.targetCycleNumber,
                before.revision + 1L,
            )
            current = after
            return DocumentWorkflowBindingTransitionResult.success(DocumentWorkflowPortOutcome.APPLIED, after)
        }
    }

    private class DocumentPort(
        private val subjectPort: SubjectPort,
    ) : DocumentWorkflowDocumentApplicationPort {
        val calls = mutableListOf<DocumentWorkflowDocumentMutationRequest>()

        override fun mutate(
            request: DocumentWorkflowDocumentMutationRequest,
        ): DocumentWorkflowDocumentMutationResult {
            calls += request
            subjectPort.lifecycle = when (request.action) {
                DocumentWorkflowDocumentMutationAction.OPEN_REVISION_DRAFT -> DocumentWorkflowLifecycle.DRAFT
                else -> DocumentWorkflowLifecycle.PENDING_REVIEW
            }
            return DocumentWorkflowDocumentMutationResult.success(
                DocumentWorkflowPortOutcome.APPLIED,
                request.subject,
                B,
            )
        }
    }

    private class WorkflowPort : DocumentWorkflowGenericApplicationPort {
        val starts = mutableListOf<WorkflowRuntimeStartRequest>()
        val revisions = mutableListOf<DocumentWorkflowSubjectRevisionCommand>()
        var startOutcome = DocumentWorkflowPortOutcome.APPLIED

        override fun start(request: WorkflowRuntimeStartRequest): DocumentWorkflowGenericCommandResult {
            starts += request
            if (startOutcome == DocumentWorkflowPortOutcome.OUTCOME_UNKNOWN) {
                return DocumentWorkflowGenericCommandResult.failure(startOutcome, "commit-outcome-unknown")
            }
            return DocumentWorkflowGenericCommandResult.success(
                startOutcome,
                request.callContext.tenantId,
                request.instanceId,
                request.definitionId,
                request.definitionRef,
                request.subject,
                1L,
                C,
            )
        }

        override fun transitionSubjectRevision(
            request: DocumentWorkflowSubjectRevisionCommand,
        ): DocumentWorkflowGenericCommandResult {
            revisions += request
            return DocumentWorkflowGenericCommandResult.success(
                DocumentWorkflowPortOutcome.APPLIED,
                request.callContext.tenantId,
                request.instanceId,
                request.selection.definitionId,
                request.selection.definitionRef,
                request.replacementSubject,
                request.options.expectedInstanceVersion + 1L,
                C,
            )
        }
    }

    companion object {
        private val A = "a".repeat(64)
        private val B = "b".repeat(64)
        private val C = "c".repeat(64)
        private val D = "d".repeat(64)
        private val E = "e".repeat(64)
        private val F = "f".repeat(64)
        private val ACTOR = WorkflowPrincipalRef.of("user", "alice")
        private val CONTEXT = WorkflowTrustedCallContext.of("tenant-a", ACTOR, "auth-1", A)
        private val SUBJECT_REF = WorkflowSubjectRef.of(
            DocumentWorkflowSubmissionRequest.DOCUMENT_SUBJECT_TYPE,
            "document-1",
        )
        private val SUBJECT_V1 = WorkflowSubjectSnapshot.of(SUBJECT_REF, "version-1", B)
        private val SUBJECT_V2 = WorkflowSubjectSnapshot.of(SUBJECT_REF, "version-2", C)
        private val SELECTION = DocumentWorkflowSelection.of(
            "definition-id-1",
            WorkflowDefinitionRef.of("knowledge-document-approval", "3", D),
            DocumentWorkflowTemplateRef.of("knowledge-document", "template-7", E),
            DocumentWorkflowRevisionPolicyRef.of("policy-2", F, "content-review"),
            "selection-revision-9",
        )

        private fun options(
            commandId: String,
            idempotencyKey: String,
            expectedVersion: Long,
            now: Long,
        ): WorkflowRuntimeCommandOptions = WorkflowRuntimeCommandOptions.of(
            commandId,
            idempotencyKey,
            expectedVersion,
            now,
            32,
            WorkflowExecutionIds.of(
                listOf("$commandId-token"),
                listOf("$commandId-execution"),
                listOf("$commandId-work-item"),
                listOf("$commandId-effect"),
                listOf("$commandId-event"),
                listOf("$commandId-scope"),
            ),
        )
    }
}
