package ai.icen.fw.workflow.document

import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.runtime.WorkflowRuntimeStartRequest

/**
 * Optional document subject adapter for the generic Workflow runtime.
 *
 * It owns no repository, database transaction, Spring component, object store, or catalog CRUD.
 * Cross-module work is a fail-closed, idempotent saga: the durable uniqueness binding is reserved
 * first, every authority/subject/selection is re-read before mutation, and uncertain outcomes keep
 * the binding blocked for reconciliation instead of starting a second approval flow.
 */
class DocumentWorkflowAdapter(
    private val subjectPort: DocumentWorkflowSubjectApplicationPort,
    private val selectionPort: DocumentWorkflowSelectionApplicationPort,
    private val authorizationPort: DocumentWorkflowAuthorizationApplicationPort,
    private val bindingPort: DocumentWorkflowBindingApplicationPort,
    private val documentPort: DocumentWorkflowDocumentApplicationPort,
    private val workflowPort: DocumentWorkflowGenericApplicationPort,
) {
    fun submit(request: DocumentWorkflowSubmissionRequest): DocumentWorkflowResult {
        val initial = validateSubjectAndSelection(
            request.callContext,
            request.action,
            request.expectedSubject,
            request.expectedSelection,
            request.purposeDigest,
            request.options.now,
            setOf(DocumentWorkflowLifecycle.DRAFT, DocumentWorkflowLifecycle.PENDING_REVIEW),
        )
        initial.failure?.let { return result(it, request.instanceId, request.expectedSubject, 0L, null) }

        val prepareAuthorization = authorize(
            request.callContext,
            DocumentWorkflowAuthorizationPhase.PREPARE,
            request.action,
            request.instanceId,
            request.expectedSubject,
            request.expectedSelection,
            request.requestDigest,
            request.purposeDigest,
            0L,
            0L,
            0L,
            request.options.now,
        ) ?: return result(
            DocumentWorkflowResultCode.AUTHORIZATION_DENIED,
            request.instanceId,
            request.expectedSubject,
            0L,
            null,
            "authorization-denied",
        )

        val reservation = try {
            bindingPort.reserve(
                DocumentWorkflowBindingReserveRequest.of(request, prepareAuthorization.decisionDigest),
            )
        } catch (_: RuntimeException) {
            return result(
                DocumentWorkflowResultCode.PORT_UNAVAILABLE,
                request.instanceId,
                request.expectedSubject,
                0L,
                null,
                "binding-port-unavailable",
            )
        }
        when (reservation.code) {
            DocumentWorkflowBindingReservationCode.ACTIVE_CONFLICT -> return result(
                DocumentWorkflowResultCode.ACTIVE_BINDING_CONFLICT,
                request.instanceId,
                request.expectedSubject,
                0L,
                null,
                "active-binding-conflict",
            )
            DocumentWorkflowBindingReservationCode.IDEMPOTENCY_CONFLICT -> return result(
                DocumentWorkflowResultCode.IDEMPOTENCY_CONFLICT,
                request.instanceId,
                request.expectedSubject,
                0L,
                null,
                "idempotency-conflict",
            )
            DocumentWorkflowBindingReservationCode.OUTCOME_UNKNOWN -> return result(
                DocumentWorkflowResultCode.RECONCILIATION_REQUIRED,
                request.instanceId,
                request.expectedSubject,
                0L,
                null,
                "binding-reservation-outcome-unknown",
            )
        }
        val binding = reservation.binding ?: return result(
            DocumentWorkflowResultCode.BINDING_CONFLICT,
            request.instanceId,
            request.expectedSubject,
            0L,
            null,
            "binding-reservation-invalid",
        )
        if (!binding.matches(request)) {
            return result(
                DocumentWorkflowResultCode.BINDING_CONFLICT,
                request.instanceId,
                request.expectedSubject,
                binding.cycleNumber,
                binding.revision,
                "binding-reservation-drift",
            )
        }
        if (binding.state == DocumentWorkflowBindingState.ACTIVE &&
            reservation.code == DocumentWorkflowBindingReservationCode.REPLAYED
        ) {
            if (initial.record?.lifecycle != DocumentWorkflowLifecycle.PENDING_REVIEW) {
                return result(
                    DocumentWorkflowResultCode.SUBJECT_DRIFT,
                    request.instanceId,
                    binding.subject,
                    binding.cycleNumber,
                    binding.revision,
                    "replayed-submission-lifecycle-drift",
                )
            }
            if (authorize(
                    request.callContext,
                    DocumentWorkflowAuthorizationPhase.COMMIT,
                    request.action,
                    request.instanceId,
                    request.expectedSubject,
                    request.expectedSelection,
                    request.requestDigest,
                    request.purposeDigest,
                    0L,
                    binding.revision,
                    binding.cycleNumber,
                    request.options.now,
                ) == null
            ) {
                return result(
                    DocumentWorkflowResultCode.AUTHORIZATION_DENIED,
                    request.instanceId,
                    binding.subject,
                    binding.cycleNumber,
                    binding.revision,
                    "authorization-denied",
                )
            }
            return result(
                DocumentWorkflowResultCode.REPLAYED,
                request.instanceId,
                binding.subject,
                binding.cycleNumber,
                binding.revision,
            )
        }
        if (binding.state != DocumentWorkflowBindingState.RESERVED) {
            return result(
                DocumentWorkflowResultCode.RECONCILIATION_REQUIRED,
                request.instanceId,
                binding.subject,
                binding.cycleNumber,
                binding.revision,
                "binding-state-requires-reconciliation",
            )
        }

        val current = validateSubjectAndSelection(
            request.callContext,
            request.action,
            request.expectedSubject,
            request.expectedSelection,
            request.purposeDigest,
            request.options.now,
            if (reservation.code == DocumentWorkflowBindingReservationCode.REPLAYED) {
                setOf(DocumentWorkflowLifecycle.DRAFT, DocumentWorkflowLifecycle.PENDING_REVIEW)
            } else {
                setOf(DocumentWorkflowLifecycle.DRAFT)
            },
        )
        current.failure?.let { failure ->
            return abandonReservation(binding, request, prepareAuthorization.decisionDigest, failure)
        }
        val commitAuthorization = authorize(
            request.callContext,
            DocumentWorkflowAuthorizationPhase.COMMIT,
            request.action,
            request.instanceId,
            request.expectedSubject,
            request.expectedSelection,
            request.requestDigest,
            request.purposeDigest,
            0L,
            binding.revision,
            0L,
            request.options.now,
        ) ?: return abandonReservation(
            binding,
            request,
            evidence("authorization-denied", request.requestDigest),
            DocumentWorkflowResultCode.AUTHORIZATION_DENIED,
        )

        val documentMutation = try {
            documentPort.mutate(
                DocumentWorkflowDocumentMutationRequest.of(
                    request.callContext,
                    DocumentWorkflowDocumentMutationAction.SUBMIT_FOR_REVIEW,
                    request.instanceId,
                    request.expectedSubject,
                    request.expectedSelection,
                    0L,
                    request.requestDigest,
                    commitAuthorization.decisionDigest,
                    request.purposeDigest,
                    request.options.now,
                ),
            )
        } catch (_: RuntimeException) {
            return reconciliation(request, binding, "document-submit-port-failed")
        }
        if (documentMutation.outcome == DocumentWorkflowPortOutcome.OUTCOME_UNKNOWN) {
            return reconciliation(request, binding, "document-submit-outcome-unknown")
        }
        if (documentMutation.outcome == DocumentWorkflowPortOutcome.REJECTED ||
            documentMutation.subject != request.expectedSubject ||
            documentMutation.receiptDigest == null
        ) {
            if (documentMutation.outcome == DocumentWorkflowPortOutcome.REJECTED) {
                return abandonReservation(
                    binding,
                    request,
                    evidence("document-submit-rejected", request.requestDigest),
                    DocumentWorkflowResultCode.DOCUMENT_REJECTED,
                )
            }
            return reconciliation(request, binding, "document-submit-receipt-drift")
        }

        val workflowStart = try {
            workflowPort.start(
                WorkflowRuntimeStartRequest.of(
                    request.callContext,
                    request.options,
                    request.instanceId,
                    request.expectedSelection.definitionId,
                    request.expectedSelection.definitionRef,
                    request.expectedSubject,
                ),
            )
        } catch (_: RuntimeException) {
            return reconciliation(request, binding, "workflow-start-port-failed")
        }
        if (workflowStart.outcome == DocumentWorkflowPortOutcome.OUTCOME_UNKNOWN) {
            return reconciliation(request, binding, "workflow-start-outcome-unknown")
        }
        if (workflowStart.outcome == DocumentWorkflowPortOutcome.REJECTED) {
            return reconciliation(request, binding, "workflow-start-rejected-after-document-submit")
        }
        if (!workflowStart.matches(
                request.callContext.tenantId,
                request.instanceId,
                request.expectedSelection,
                request.expectedSubject,
                1L,
            )
        ) {
            return reconciliation(request, binding, "workflow-start-receipt-drift")
        }

        val transition = transition(
            request.callContext,
            binding,
            request.action,
            DocumentWorkflowBindingState.ACTIVE,
            request.expectedSubject,
            0L,
            request.options.idempotencyKey,
            request.requestDigest,
            evidence(
                "submit-committed",
                commitAuthorization.decisionDigest,
                checkNotNull(documentMutation.receiptDigest),
                checkNotNull(workflowStart.receiptDigest),
            ),
            request.options.now,
        ) ?: return reconciliation(request, binding, "binding-activation-port-failed")
        val activated = transition.binding
        if (!transition.isExact(
                binding,
                DocumentWorkflowBindingState.ACTIVE,
                request.expectedSubject,
                0L,
                request.action,
                request.options.idempotencyKey,
                request.requestDigest,
            )
        ) {
            return reconciliation(request, binding, "binding-activation-receipt-drift")
        }
        return result(
            if (reservation.code == DocumentWorkflowBindingReservationCode.REPLAYED ||
                workflowStart.outcome == DocumentWorkflowPortOutcome.REPLAYED
            ) DocumentWorkflowResultCode.REPLAYED else DocumentWorkflowResultCode.STARTED,
            request.instanceId,
            request.expectedSubject,
            0L,
            checkNotNull(activated).revision,
        )
    }

    fun requestCorrection(request: DocumentWorkflowCorrectionRequest): DocumentWorkflowResult {
        if (request.mode == DocumentWorkflowCorrectionMode.RETURN_WITHOUT_SUBJECT_CHANGE) {
            return result(
                DocumentWorkflowResultCode.WORKFLOW_ONLY_REQUIRED,
                request.instanceId,
                request.expectedSubject,
                request.expectedCycleNumber,
                request.expectedBindingRevision,
            )
        }
        if (request.mode != DocumentWorkflowCorrectionMode.REQUEST_SUBJECT_REVISION) {
            return result(
                DocumentWorkflowResultCode.WORKFLOW_REJECTED,
                request.instanceId,
                request.expectedSubject,
                request.expectedCycleNumber,
                request.expectedBindingRevision,
                "correction-mode-unsupported",
            )
        }
        val validation = validateSubjectAndSelection(
            request.callContext,
            request.action,
            request.expectedSubject,
            request.expectedSelection,
            request.purposeDigest,
            request.options.now,
            setOf(DocumentWorkflowLifecycle.PENDING_REVIEW, DocumentWorkflowLifecycle.DRAFT),
        )
        validation.failure?.let {
            return result(
                it,
                request.instanceId,
                request.expectedSubject,
                request.expectedCycleNumber,
                request.expectedBindingRevision,
            )
        }
        val binding = findBinding(
            request.callContext,
            request.expectedSubject,
            request.instanceId,
            request.options.now,
        ) ?: return result(
            DocumentWorkflowResultCode.BINDING_CONFLICT,
            request.instanceId,
            request.expectedSubject,
            request.expectedCycleNumber,
            request.expectedBindingRevision,
            "active-binding-not-found",
        )
        if (binding.matchesReplay(request)) {
            if (validation.record?.lifecycle != DocumentWorkflowLifecycle.DRAFT) {
                return result(
                    DocumentWorkflowResultCode.SUBJECT_DRIFT,
                    request.instanceId,
                    request.expectedSubject,
                    binding.cycleNumber,
                    binding.revision,
                    "replayed-correction-lifecycle-drift",
                )
            }
            val replayAuthorization = authorizeOperation(
                request.callContext,
                DocumentWorkflowAuthorizationPhase.COMMIT,
                request.action,
                request.instanceId,
                request.expectedSubject,
                request.expectedSelection,
                request.requestDigest,
                request.purposeDigest,
                request.options.expectedInstanceVersion,
                binding.revision,
                binding.cycleNumber,
                request.options.now,
            )
            if (replayAuthorization == null) return denied(request.instanceId, request.expectedSubject, binding)
            return result(
                DocumentWorkflowResultCode.REPLAYED,
                request.instanceId,
                binding.subject,
                binding.cycleNumber,
                binding.revision,
            )
        }
        if (validation.record?.lifecycle != DocumentWorkflowLifecycle.PENDING_REVIEW) {
            return result(
                DocumentWorkflowResultCode.SUBJECT_DRIFT,
                request.instanceId,
                request.expectedSubject,
                binding.cycleNumber,
                binding.revision,
                "correction-lifecycle-drift",
            )
        }
        if (!binding.matchesOperation(
                request.callContext.tenantId,
                request.instanceId,
                DocumentWorkflowBindingState.ACTIVE,
                request.expectedSubject,
                request.expectedSelection,
                request.expectedCycleNumber,
                request.expectedBindingRevision,
            )
        ) {
            return result(
                DocumentWorkflowResultCode.BINDING_CONFLICT,
                request.instanceId,
                request.expectedSubject,
                request.expectedCycleNumber,
                binding.revision,
                "active-binding-drift",
            )
        }
        if (authorizeOperation(
                request.callContext,
                DocumentWorkflowAuthorizationPhase.PREPARE,
                request.action,
                request.instanceId,
                request.expectedSubject,
                request.expectedSelection,
                request.requestDigest,
                request.purposeDigest,
                request.options.expectedInstanceVersion,
                binding.revision,
                binding.cycleNumber,
                request.options.now,
            ) == null
        ) return denied(request.instanceId, request.expectedSubject, binding)

        val current = validateSubjectAndSelection(
            request.callContext,
            request.action,
            request.expectedSubject,
            request.expectedSelection,
            request.purposeDigest,
            request.options.now,
            setOf(DocumentWorkflowLifecycle.PENDING_REVIEW),
        )
        current.failure?.let {
            return result(it, request.instanceId, request.expectedSubject, binding.cycleNumber, binding.revision)
        }
        val commitAuthorization = authorizeOperation(
            request.callContext,
            DocumentWorkflowAuthorizationPhase.COMMIT,
            request.action,
            request.instanceId,
            request.expectedSubject,
            request.expectedSelection,
            request.requestDigest,
            request.purposeDigest,
            request.options.expectedInstanceVersion,
            binding.revision,
            binding.cycleNumber,
            request.options.now,
        ) ?: return denied(request.instanceId, request.expectedSubject, binding)

        val nextCycle = binding.cycleNumber + 1L
        val workflowPause = try {
            workflowPort.transitionSubjectRevision(
                DocumentWorkflowSubjectRevisionCommand.of(
                    request.callContext,
                    request.options,
                    request.action,
                    request.instanceId,
                    request.expectedSelection,
                    request.expectedSubject,
                    request.expectedSubject,
                    nextCycle,
                    commitAuthorization.decisionDigest,
                    request.reasonDigest,
                ),
            )
        } catch (_: RuntimeException) {
            return reconciliation(request, binding, "workflow-revision-pause-port-failed")
        }
        if (workflowPause.outcome == DocumentWorkflowPortOutcome.OUTCOME_UNKNOWN) {
            return reconciliation(request, binding, "workflow-revision-pause-outcome-unknown")
        }
        if (workflowPause.outcome == DocumentWorkflowPortOutcome.REJECTED) {
            return result(
                DocumentWorkflowResultCode.WORKFLOW_REJECTED,
                request.instanceId,
                request.expectedSubject,
                binding.cycleNumber,
                binding.revision,
                workflowPause.failureCode ?: "workflow-revision-pause-rejected",
            )
        }
        if (!workflowPause.matches(
                request.callContext.tenantId,
                request.instanceId,
                request.expectedSelection,
                request.expectedSubject,
                request.options.expectedInstanceVersion + 1L,
            )
        ) return reconciliation(request, binding, "workflow-revision-pause-receipt-drift")

        val documentOpen = try {
            documentPort.mutate(
                DocumentWorkflowDocumentMutationRequest.of(
                    request.callContext,
                    DocumentWorkflowDocumentMutationAction.OPEN_REVISION_DRAFT,
                    request.instanceId,
                    request.expectedSubject,
                    request.expectedSelection,
                    nextCycle,
                    request.requestDigest,
                    commitAuthorization.decisionDigest,
                    request.reasonDigest,
                    request.options.now,
                ),
            )
        } catch (_: RuntimeException) {
            return reconciliation(request, binding, "document-open-revision-port-failed")
        }
        if (documentOpen.outcome == DocumentWorkflowPortOutcome.OUTCOME_UNKNOWN ||
            documentOpen.outcome == DocumentWorkflowPortOutcome.REJECTED ||
            documentOpen.subject != request.expectedSubject || documentOpen.receiptDigest == null
        ) return reconciliation(request, binding, "document-open-revision-not-confirmed")

        val transition = transition(
            request.callContext,
            binding,
            request.action,
            DocumentWorkflowBindingState.WAITING_SUBJECT_REVISION,
            request.expectedSubject,
            nextCycle,
            request.options.idempotencyKey,
            request.requestDigest,
            evidence(
                "revision-opened",
                commitAuthorization.decisionDigest,
                checkNotNull(workflowPause.receiptDigest),
                checkNotNull(documentOpen.receiptDigest),
            ),
            request.options.now,
        ) ?: return reconciliation(request, binding, "binding-revision-open-port-failed")
        if (!transition.isExact(
                binding,
                DocumentWorkflowBindingState.WAITING_SUBJECT_REVISION,
                request.expectedSubject,
                nextCycle,
                request.action,
                request.options.idempotencyKey,
                request.requestDigest,
            )
        ) return reconciliation(request, binding, "binding-revision-open-receipt-drift")

        return result(
            DocumentWorkflowResultCode.WAITING_FOR_SUBJECT_REVISION,
            request.instanceId,
            request.expectedSubject,
            nextCycle,
            checkNotNull(transition.binding).revision,
        )
    }

    fun resumeRevision(request: DocumentWorkflowResumeRevisionRequest): DocumentWorkflowResult {
        val validation = validateSubjectAndSelection(
            request.callContext,
            request.action,
            request.replacementSubject,
            request.expectedSelection,
            request.purposeDigest,
            request.options.now,
            setOf(DocumentWorkflowLifecycle.DRAFT, DocumentWorkflowLifecycle.PENDING_REVIEW),
        )
        validation.failure?.let {
            return result(
                it,
                request.instanceId,
                request.replacementSubject,
                request.cycleNumber,
                request.expectedBindingRevision,
            )
        }
        val binding = findBinding(
            request.callContext,
            request.previousSubject,
            request.instanceId,
            request.options.now,
        ) ?: return result(
            DocumentWorkflowResultCode.BINDING_CONFLICT,
            request.instanceId,
            request.replacementSubject,
            request.cycleNumber,
            request.expectedBindingRevision,
            "revision-binding-not-found",
        )
        if (binding.matchesReplay(request)) {
            if (validation.record?.lifecycle != DocumentWorkflowLifecycle.PENDING_REVIEW) {
                return result(
                    DocumentWorkflowResultCode.SUBJECT_DRIFT,
                    request.instanceId,
                    request.replacementSubject,
                    binding.cycleNumber,
                    binding.revision,
                    "replayed-revision-lifecycle-drift",
                )
            }
            val replayAuthorization = authorizeOperation(
                request.callContext,
                DocumentWorkflowAuthorizationPhase.COMMIT,
                request.action,
                request.instanceId,
                request.replacementSubject,
                request.expectedSelection,
                request.requestDigest,
                request.purposeDigest,
                request.options.expectedInstanceVersion,
                binding.revision,
                binding.cycleNumber,
                request.options.now,
            )
            if (replayAuthorization == null) return denied(request.instanceId, request.replacementSubject, binding)
            return result(
                DocumentWorkflowResultCode.REPLAYED,
                request.instanceId,
                binding.subject,
                binding.cycleNumber,
                binding.revision,
            )
        }
        if (validation.record?.lifecycle != DocumentWorkflowLifecycle.DRAFT) {
            return result(
                DocumentWorkflowResultCode.SUBJECT_DRIFT,
                request.instanceId,
                request.replacementSubject,
                binding.cycleNumber,
                binding.revision,
                "revision-lifecycle-drift",
            )
        }
        if (!binding.matchesOperation(
                request.callContext.tenantId,
                request.instanceId,
                DocumentWorkflowBindingState.WAITING_SUBJECT_REVISION,
                request.previousSubject,
                request.expectedSelection,
                request.cycleNumber,
                request.expectedBindingRevision,
            )
        ) return result(
            DocumentWorkflowResultCode.BINDING_CONFLICT,
            request.instanceId,
            request.replacementSubject,
            request.cycleNumber,
            binding.revision,
            "revision-binding-drift",
        )

        if (authorizeOperation(
                request.callContext,
                DocumentWorkflowAuthorizationPhase.PREPARE,
                request.action,
                request.instanceId,
                request.replacementSubject,
                request.expectedSelection,
                request.requestDigest,
                request.purposeDigest,
                request.options.expectedInstanceVersion,
                binding.revision,
                binding.cycleNumber,
                request.options.now,
            ) == null
        ) return denied(request.instanceId, request.replacementSubject, binding)

        val current = validateSubjectAndSelection(
            request.callContext,
            request.action,
            request.replacementSubject,
            request.expectedSelection,
            request.purposeDigest,
            request.options.now,
            setOf(DocumentWorkflowLifecycle.DRAFT),
        )
        current.failure?.let {
            return result(it, request.instanceId, request.replacementSubject, binding.cycleNumber, binding.revision)
        }
        val commitAuthorization = authorizeOperation(
            request.callContext,
            DocumentWorkflowAuthorizationPhase.COMMIT,
            request.action,
            request.instanceId,
            request.replacementSubject,
            request.expectedSelection,
            request.requestDigest,
            request.purposeDigest,
            request.options.expectedInstanceVersion,
            binding.revision,
            binding.cycleNumber,
            request.options.now,
        ) ?: return denied(request.instanceId, request.replacementSubject, binding)

        val documentSubmit = try {
            documentPort.mutate(
                DocumentWorkflowDocumentMutationRequest.of(
                    request.callContext,
                    DocumentWorkflowDocumentMutationAction.SUBMIT_REVISION_FOR_REVIEW,
                    request.instanceId,
                    request.replacementSubject,
                    request.expectedSelection,
                    request.cycleNumber,
                    request.requestDigest,
                    commitAuthorization.decisionDigest,
                    request.reasonDigest,
                    request.options.now,
                ),
            )
        } catch (_: RuntimeException) {
            return reconciliation(request, binding, "document-revision-submit-port-failed")
        }
        if (documentSubmit.outcome == DocumentWorkflowPortOutcome.OUTCOME_UNKNOWN) {
            return reconciliation(request, binding, "document-revision-submit-outcome-unknown")
        }
        if (documentSubmit.outcome == DocumentWorkflowPortOutcome.REJECTED) {
            return result(
                DocumentWorkflowResultCode.DOCUMENT_REJECTED,
                request.instanceId,
                request.replacementSubject,
                request.cycleNumber,
                binding.revision,
                documentSubmit.failureCode ?: "document-revision-submit-rejected",
            )
        }
        if (documentSubmit.subject != request.replacementSubject || documentSubmit.receiptDigest == null) {
            return reconciliation(request, binding, "document-revision-submit-receipt-drift")
        }

        val workflowResume = try {
            workflowPort.transitionSubjectRevision(
                DocumentWorkflowSubjectRevisionCommand.of(
                    request.callContext,
                    request.options,
                    request.action,
                    request.instanceId,
                    request.expectedSelection,
                    request.previousSubject,
                    request.replacementSubject,
                    request.cycleNumber,
                    commitAuthorization.decisionDigest,
                    request.reasonDigest,
                ),
            )
        } catch (_: RuntimeException) {
            return reconciliation(request, binding, "workflow-revision-resume-port-failed")
        }
        if (workflowResume.outcome == DocumentWorkflowPortOutcome.OUTCOME_UNKNOWN ||
            workflowResume.outcome == DocumentWorkflowPortOutcome.REJECTED
        ) return reconciliation(request, binding, "workflow-revision-resume-not-confirmed")
        if (!workflowResume.matches(
                request.callContext.tenantId,
                request.instanceId,
                request.expectedSelection,
                request.replacementSubject,
                request.options.expectedInstanceVersion + 1L,
            )
        ) return reconciliation(request, binding, "workflow-revision-resume-receipt-drift")

        val transition = transition(
            request.callContext,
            binding,
            request.action,
            DocumentWorkflowBindingState.ACTIVE,
            request.replacementSubject,
            request.cycleNumber,
            request.options.idempotencyKey,
            request.requestDigest,
            evidence(
                "revision-resumed",
                commitAuthorization.decisionDigest,
                checkNotNull(documentSubmit.receiptDigest),
                checkNotNull(workflowResume.receiptDigest),
            ),
            request.options.now,
        ) ?: return reconciliation(request, binding, "binding-revision-resume-port-failed")
        if (!transition.isExact(
                binding,
                DocumentWorkflowBindingState.ACTIVE,
                request.replacementSubject,
                request.cycleNumber,
                request.action,
                request.options.idempotencyKey,
                request.requestDigest,
            )
        ) return reconciliation(request, binding, "binding-revision-resume-receipt-drift")

        return result(
            DocumentWorkflowResultCode.SUBJECT_REVISION_RESUMED,
            request.instanceId,
            request.replacementSubject,
            request.cycleNumber,
            checkNotNull(transition.binding).revision,
        )
    }

    private fun validateSubjectAndSelection(
        context: ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext,
        action: DocumentWorkflowAction,
        subject: WorkflowSubjectSnapshot,
        selection: DocumentWorkflowSelection,
        purposeDigest: String,
        now: Long,
        allowedLifecycles: Set<DocumentWorkflowLifecycle>,
    ): Validation {
        val resolveRequest = DocumentWorkflowSubjectResolveRequest.of(
            context,
            action,
            subject.ref,
            subject,
            purposeDigest,
            now,
        )
        val record = try {
            subjectPort.resolve(resolveRequest)
        } catch (_: RuntimeException) {
            return Validation(null, DocumentWorkflowResultCode.PORT_UNAVAILABLE)
        } ?: return Validation(null, DocumentWorkflowResultCode.SUBJECT_NOT_FOUND)
        if (!record.matches(resolveRequest) || record.snapshot != subject || record.lifecycle !in allowedLifecycles) {
            return Validation(null, DocumentWorkflowResultCode.SUBJECT_DRIFT)
        }
        val selectionRequest = try {
            DocumentWorkflowSelectionRequest.of(
                context,
                action,
                record,
                selection,
                purposeDigest,
                now,
            )
        } catch (_: IllegalArgumentException) {
            return Validation(null, DocumentWorkflowResultCode.SUBJECT_DRIFT)
        }
        val selected = try {
            selectionPort.select(selectionRequest)
        } catch (_: RuntimeException) {
            return Validation(null, DocumentWorkflowResultCode.PORT_UNAVAILABLE)
        }
        if (selected == null || selected != selection) {
            return Validation(null, DocumentWorkflowResultCode.SELECTION_DRIFT)
        }
        return Validation(record, null)
    }

    private fun authorize(
        context: ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext,
        phase: DocumentWorkflowAuthorizationPhase,
        action: DocumentWorkflowAction,
        instanceId: String,
        subject: WorkflowSubjectSnapshot,
        selection: DocumentWorkflowSelection,
        logicalRequestDigest: String,
        purposeDigest: String,
        expectedInstanceVersion: Long,
        expectedBindingRevision: Long,
        cycleNumber: Long,
        now: Long,
    ): DocumentWorkflowAuthorizationDecision? = authorizeOperation(
        context,
        phase,
        action,
        instanceId,
        subject,
        selection,
        logicalRequestDigest,
        purposeDigest,
        expectedInstanceVersion,
        expectedBindingRevision,
        cycleNumber,
        now,
    )

    private fun authorizeOperation(
        context: ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext,
        phase: DocumentWorkflowAuthorizationPhase,
        action: DocumentWorkflowAction,
        instanceId: String,
        subject: WorkflowSubjectSnapshot,
        selection: DocumentWorkflowSelection,
        logicalRequestDigest: String,
        purposeDigest: String,
        expectedInstanceVersion: Long,
        expectedBindingRevision: Long,
        cycleNumber: Long,
        now: Long,
    ): DocumentWorkflowAuthorizationDecision? {
        val authorizationRequest = DocumentWorkflowAuthorizationRequest.of(
            context,
            phase,
            action,
            instanceId,
            subject,
            selection,
            logicalRequestDigest,
            purposeDigest,
            expectedInstanceVersion,
            expectedBindingRevision,
            cycleNumber,
            now,
        )
        val decision = try {
            authorizationPort.authorize(authorizationRequest)
        } catch (_: RuntimeException) {
            return null
        }
        return decision.takeIf {
            it.status == DocumentWorkflowAuthorizationStatus.AUTHORIZED &&
                it.matches(authorizationRequest, now)
        }
    }

    private fun findBinding(
        context: ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext,
        subject: WorkflowSubjectSnapshot,
        instanceId: String,
        now: Long,
    ): DocumentWorkflowBinding? = try {
        bindingPort.find(
            DocumentWorkflowBindingLookupRequest.of(context, subject.ref, instanceId, now),
        )
    } catch (_: RuntimeException) {
        null
    }

    private fun transition(
        context: ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext,
        binding: DocumentWorkflowBinding,
        action: DocumentWorkflowAction,
        targetState: DocumentWorkflowBindingState,
        targetSubject: WorkflowSubjectSnapshot,
        targetCycle: Long,
        idempotencyKey: String,
        logicalRequestDigest: String,
        evidenceDigest: String,
        now: Long,
    ): DocumentWorkflowBindingTransitionResult? = try {
        require(context.tenantId == binding.tenantId) {
            "Document workflow binding transition tenant does not match the authenticated context."
        }
        bindingPort.transition(
            DocumentWorkflowBindingTransitionRequest.of(
                context,
                action,
                binding.document,
                binding.instanceId,
                binding.revision,
                binding.state,
                targetState,
                binding.subject,
                targetSubject,
                binding.cycleNumber,
                targetCycle,
                idempotencyKey,
                logicalRequestDigest,
                evidenceDigest,
                now,
            ),
        )
    } catch (_: RuntimeException) {
        null
    }

    private fun abandonReservation(
        binding: DocumentWorkflowBinding,
        request: DocumentWorkflowSubmissionRequest,
        evidenceDigest: String,
        failure: DocumentWorkflowResultCode,
    ): DocumentWorkflowResult {
        val transition = transition(
            request.callContext,
            binding,
            request.action,
            DocumentWorkflowBindingState.TERMINAL,
            binding.subject,
            binding.cycleNumber,
            request.options.idempotencyKey,
            request.requestDigest,
            evidenceDigest,
            request.options.now,
        )
        if (!transition.isExact(
                binding,
                DocumentWorkflowBindingState.TERMINAL,
                binding.subject,
                binding.cycleNumber,
                request.action,
                request.options.idempotencyKey,
                request.requestDigest,
            )
        ) {
            return reconciliation(request, binding, "binding-abandonment-not-confirmed")
        }
        return result(
            failure,
            request.instanceId,
            request.expectedSubject,
            binding.cycleNumber,
            checkNotNull(transition?.binding).revision,
            when (failure) {
                DocumentWorkflowResultCode.AUTHORIZATION_DENIED -> "authorization-denied"
                DocumentWorkflowResultCode.DOCUMENT_REJECTED -> "document-submit-rejected"
                DocumentWorkflowResultCode.SUBJECT_DRIFT -> "subject-drift"
                DocumentWorkflowResultCode.SELECTION_DRIFT -> "selection-drift"
                else -> "submission-abandoned"
            },
        )
    }

    private fun reconciliation(
        request: DocumentWorkflowSubmissionRequest,
        binding: DocumentWorkflowBinding,
        failureCode: String,
    ): DocumentWorkflowResult = reconciliation(
        request.callContext,
        request.action,
        request.options.idempotencyKey,
        request.options.now,
        binding,
        request.requestDigest,
        failureCode,
    )

    private fun reconciliation(
        request: DocumentWorkflowCorrectionRequest,
        binding: DocumentWorkflowBinding,
        failureCode: String,
    ): DocumentWorkflowResult = reconciliation(
        request.callContext,
        request.action,
        request.options.idempotencyKey,
        request.options.now,
        binding,
        request.requestDigest,
        failureCode,
    )

    private fun reconciliation(
        request: DocumentWorkflowResumeRevisionRequest,
        binding: DocumentWorkflowBinding,
        failureCode: String,
    ): DocumentWorkflowResult = reconciliation(
        request.callContext,
        request.action,
        request.options.idempotencyKey,
        request.options.now,
        binding,
        request.requestDigest,
        failureCode,
    )

    private fun reconciliation(
        context: ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext,
        action: DocumentWorkflowAction,
        idempotencyKey: String,
        now: Long,
        binding: DocumentWorkflowBinding,
        logicalRequestDigest: String,
        failureCode: String,
    ): DocumentWorkflowResult {
        val reconciled = if (binding.state != DocumentWorkflowBindingState.TERMINAL) {
            transition(
                context,
                binding,
                action,
                DocumentWorkflowBindingState.RECONCILIATION_REQUIRED,
                binding.subject,
                binding.cycleNumber,
                idempotencyKey,
                logicalRequestDigest,
                evidence(failureCode, logicalRequestDigest),
                now,
            )
        } else null
        return result(
            DocumentWorkflowResultCode.RECONCILIATION_REQUIRED,
            binding.instanceId,
            binding.subject,
            binding.cycleNumber,
            reconciled?.binding?.revision ?: binding.revision,
            failureCode,
        )
    }

    private fun denied(
        instanceId: String,
        subject: WorkflowSubjectSnapshot,
        binding: DocumentWorkflowBinding,
    ): DocumentWorkflowResult = result(
        DocumentWorkflowResultCode.AUTHORIZATION_DENIED,
        instanceId,
        subject,
        binding.cycleNumber,
        binding.revision,
        "authorization-denied",
    )

    private fun result(
        code: DocumentWorkflowResultCode,
        instanceId: String,
        subject: WorkflowSubjectSnapshot,
        cycleNumber: Long?,
        bindingRevision: Long?,
        failureCode: String? = null,
    ): DocumentWorkflowResult = DocumentWorkflowResult.of(
        code,
        instanceId,
        subject,
        cycleNumber,
        bindingRevision,
        failureCode,
    )

    private fun evidence(label: String, vararg digests: String): String {
        val writer = DocumentWorkflowSupport.digest("flowweft-workflow-document-adapter-evidence-v1")
            .text(label)
            .integer(digests.size)
        digests.forEach(writer::text)
        return writer.finish()
    }

    private class Validation(
        val record: DocumentWorkflowSubjectRecord?,
        val failure: DocumentWorkflowResultCode?,
    )
}

private fun DocumentWorkflowGenericCommandResult.matches(
    tenantId: String,
    instanceId: String,
    selection: DocumentWorkflowSelection,
    subject: WorkflowSubjectSnapshot,
    resultVersion: Long,
): Boolean =
    (outcome == DocumentWorkflowPortOutcome.APPLIED || outcome == DocumentWorkflowPortOutcome.REPLAYED) &&
        this.tenantId == tenantId &&
        this.instanceId == instanceId &&
        definitionId == selection.definitionId &&
        definitionRef == selection.definitionRef &&
        this.subject == subject &&
        resultInstanceVersion == resultVersion &&
        receiptDigest != null

private fun DocumentWorkflowBinding.matchesOperation(
    tenantId: String,
    instanceId: String,
    state: DocumentWorkflowBindingState,
    subject: WorkflowSubjectSnapshot,
    selection: DocumentWorkflowSelection,
    cycleNumber: Long,
    revision: Long,
): Boolean =
    this.tenantId == tenantId &&
        this.instanceId == instanceId &&
        this.state == state &&
        this.subject == subject &&
        this.selection == selection &&
        this.cycleNumber == cycleNumber &&
        this.revision == revision

private fun DocumentWorkflowBinding.matchesReplay(
    request: DocumentWorkflowCorrectionRequest,
): Boolean =
    tenantId == request.callContext.tenantId &&
        instanceId == request.instanceId &&
        state == DocumentWorkflowBindingState.WAITING_SUBJECT_REVISION &&
        subject == request.expectedSubject &&
        selection == request.expectedSelection &&
        cycleNumber == request.expectedCycleNumber + 1L &&
        revision > request.expectedBindingRevision &&
        lastAction == request.action &&
        lastOperationIdempotencyKey == request.options.idempotencyKey &&
        lastOperationRequestDigest == request.requestDigest

private fun DocumentWorkflowBinding.matchesReplay(
    request: DocumentWorkflowResumeRevisionRequest,
): Boolean =
    tenantId == request.callContext.tenantId &&
        instanceId == request.instanceId &&
        state == DocumentWorkflowBindingState.ACTIVE &&
        subject == request.replacementSubject &&
        selection == request.expectedSelection &&
        cycleNumber == request.cycleNumber &&
        revision > request.expectedBindingRevision &&
        lastAction == request.action &&
        lastOperationIdempotencyKey == request.options.idempotencyKey &&
        lastOperationRequestDigest == request.requestDigest

private fun DocumentWorkflowBindingTransitionResult?.isExact(
    previous: DocumentWorkflowBinding,
    targetState: DocumentWorkflowBindingState,
    targetSubject: WorkflowSubjectSnapshot,
    targetCycle: Long,
    action: DocumentWorkflowAction,
    idempotencyKey: String,
    logicalRequestDigest: String,
): Boolean {
    val current = this?.binding ?: return false
    return (outcome == DocumentWorkflowPortOutcome.APPLIED || outcome == DocumentWorkflowPortOutcome.REPLAYED) &&
        current.tenantId == previous.tenantId &&
        current.document == previous.document &&
        current.instanceId == previous.instanceId &&
        current.state == targetState &&
        current.subject == targetSubject &&
        current.selection == previous.selection &&
        current.startIdempotencyKey == previous.startIdempotencyKey &&
        current.startRequestDigest == previous.startRequestDigest &&
        current.lastAction == action &&
        current.lastOperationIdempotencyKey == idempotencyKey &&
        current.lastOperationRequestDigest == logicalRequestDigest &&
        current.cycleNumber == targetCycle &&
        current.revision > previous.revision
}
