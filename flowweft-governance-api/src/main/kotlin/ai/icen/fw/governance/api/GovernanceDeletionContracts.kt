package ai.icen.fw.governance.api

enum class GovernanceDeletionSurface {
    METADATA,
    OUTBOX,
    INDEX,
    OBJECT,
}

/** Fixed safety order: deny reads first, propagate tombstone, purge derived/external data, then finalize. */
enum class GovernanceDeletionStage(val surface: GovernanceDeletionSurface) {
    PERSIST_TOMBSTONE(GovernanceDeletionSurface.METADATA),
    APPEND_DECISION_AUDIT(GovernanceDeletionSurface.METADATA),
    ENQUEUE_PURGE_OUTBOX(GovernanceDeletionSurface.OUTBOX),
    PURGE_INDEX_PROJECTIONS(GovernanceDeletionSurface.INDEX),
    PURGE_OBJECT_CONTENT(GovernanceDeletionSurface.OBJECT),
    FINALIZE_METADATA(GovernanceDeletionSurface.METADATA),
    APPEND_COMPLETION_AUDIT(GovernanceDeletionSurface.METADATA),
}

/** One immutable target in a deletion plan. Target references are opaque, never object keys or URLs. */
class GovernanceDeletionStep private constructor(
    stepId: String,
    val sequence: Int,
    val stage: GovernanceDeletionStage,
    targetRef: String,
    targetRevision: String,
    targetDigest: String,
    idempotencyKey: String,
) {
    val stepId: String = GovernanceContractSupport.requireOpaqueReference(
        stepId, "Governance deletion step identifier is invalid.",
    )
    val targetRef: String = GovernanceContractSupport.requireOpaqueReference(
        targetRef, "Governance deletion target reference is invalid.",
    )
    val targetRevision: String = GovernanceContractSupport.requireText(
        targetRevision, GovernanceContractSupport.MAX_REVISION_UTF8_BYTES,
        "Governance deletion target revision is invalid.",
    )
    val targetDigest: String = GovernanceContractSupport.requireSha256(
        targetDigest, "Governance deletion target digest is invalid.",
    )
    val idempotencyKey: String = GovernanceContractSupport.requireText(
        idempotencyKey, GovernanceContractSupport.MAX_ID_UTF8_BYTES,
        "Governance deletion step idempotency key is invalid.",
    )
    val stepDigest: String

    init {
        require(sequence in 1..GovernanceContractSupport.MAX_STEPS) {
            "Governance deletion step sequence is invalid."
        }
        stepDigest = GovernanceContractSupport.digest("flowweft-governance-api-deletion-step-v1")
            .text(this.stepId)
            .integer(sequence)
            .text(stage.name)
            .text(stage.surface.name)
            .text(this.targetRef)
            .text(this.targetRevision)
            .text(this.targetDigest)
            .text(this.idempotencyKey)
            .finish()
    }

    override fun toString(): String = "GovernanceDeletionStep(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            stepId: String,
            sequence: Int,
            stage: GovernanceDeletionStage,
            targetRef: String,
            targetRevision: String,
            targetDigest: String,
            idempotencyKey: String,
        ): GovernanceDeletionStep = GovernanceDeletionStep(
            stepId, sequence, stage, targetRef, targetRevision, targetDigest, idempotencyKey,
        )
    }
}

/** Immutable secure-deletion plan. Dry-run and executable plans have different digests. */
class GovernanceDeletionPlan private constructor(
    planId: String,
    val context: GovernanceCallContext,
    val fence: GovernanceVersionFence,
    val assessment: GovernanceRetentionAssessment,
    steps: Collection<GovernanceDeletionStep>,
    val dryRun: Boolean,
    val createdAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val planId: String = GovernanceContractSupport.requireOpaqueReference(
        planId, "Governance deletion plan identifier is invalid.",
    )
    val tenantId: String = context.tenantId
    val resource: GovernanceResourceRef = fence.resource
    val steps: List<GovernanceDeletionStep> = GovernanceContractSupport.immutableList(
        steps, GovernanceContractSupport.MAX_STEPS, "Governance deletion plan steps are invalid.",
    )
    val planDigest: String

    init {
        require(context.purpose == GovernancePurpose.PLAN_SECURE_DELETION) {
            "Governance deletion plan requires its exact purpose."
        }
        require(context.authorization.resource == resource && assessment.resource == resource &&
            assessment.tenantId == tenantId) {
            "Governance deletion plan does not match the exact tenant and resource."
        }
        require(context.deadlineEpochMilli <= assessment.validUntilEpochMilli) {
            "Governance deletion assessment is not fresh for the complete planning call window."
        }
        require(assessment.isDeletionEligible() &&
            assessment.legalHolds.status == GovernanceLegalHoldResolutionStatus.CLEAR &&
            assessment.legalHolds.complete) {
            "Governance deletion plan requires complete retention eligibility and clear legal holds."
        }
        require(createdAtEpochMilli in context.requestedAtEpochMilli..context.deadlineEpochMilli &&
            expiresAtEpochMilli > createdAtEpochMilli &&
            expiresAtEpochMilli - createdAtEpochMilli <= GovernanceContractSupport.MAX_DELETION_PLAN_WINDOW_MILLIS) {
            "Governance deletion plan validity window is invalid."
        }
        require(this.steps.map { it.sequence } == (1..this.steps.size).toList() &&
            this.steps.map { it.stage } == REQUIRED_STAGE_ORDER) {
            "Governance deletion plan must preserve the complete secure stage order."
        }
        require(this.steps.map { it.stepId }.toSet().size == this.steps.size &&
            this.steps.map { it.idempotencyKey }.toSet().size == this.steps.size) {
            "Governance deletion plan step identifiers and idempotency keys must be unique."
        }
        require(this.steps.map { it.stage.surface }.toSet().containsAll(GovernanceDeletionSurface.values().toSet())) {
            "Governance deletion plan must cover metadata, Outbox, index and object surfaces."
        }
        val writer = GovernanceContractSupport.digest("flowweft-governance-api-deletion-plan-v1")
            .text(this.planId)
            .text(context.contextDigest)
            .text(fence.fenceDigest)
            .text(assessment.assessmentDigest)
            .booleanValue(dryRun)
            .longValue(createdAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .integer(this.steps.size)
        this.steps.forEach { step -> writer.text(step.stepDigest) }
        planDigest = writer.finish()
    }

    override fun toString(): String = "GovernanceDeletionPlan(<redacted>)"

    companion object {
        @JvmField
        val REQUIRED_STAGE_ORDER: List<GovernanceDeletionStage> = listOf(
            GovernanceDeletionStage.PERSIST_TOMBSTONE,
            GovernanceDeletionStage.APPEND_DECISION_AUDIT,
            GovernanceDeletionStage.ENQUEUE_PURGE_OUTBOX,
            GovernanceDeletionStage.PURGE_INDEX_PROJECTIONS,
            GovernanceDeletionStage.PURGE_OBJECT_CONTENT,
            GovernanceDeletionStage.FINALIZE_METADATA,
            GovernanceDeletionStage.APPEND_COMPLETION_AUDIT,
        )

        @JvmStatic
        fun of(
            planId: String,
            context: GovernanceCallContext,
            fence: GovernanceVersionFence,
            assessment: GovernanceRetentionAssessment,
            steps: Collection<GovernanceDeletionStep>,
            dryRun: Boolean,
            createdAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): GovernanceDeletionPlan = GovernanceDeletionPlan(
            planId,
            context,
            fence,
            assessment,
            steps,
            dryRun,
            createdAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

class GovernanceDeletionStepStatus private constructor(code: String) {
    val code: String = GovernanceContractSupport.requireMachineCode(
        code, "Governance deletion step status is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceDeletionStepStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceDeletionStepStatus(<redacted>)"

    companion object {
        @JvmField val COMPLETED = GovernanceDeletionStepStatus("completed")
        @JvmField val VERIFIED_ABSENT = GovernanceDeletionStepStatus("verified-absent")
        @JvmField val RETRYABLE_FAILURE = GovernanceDeletionStepStatus("retryable-failure")
        @JvmField val PERMANENT_FAILURE = GovernanceDeletionStepStatus("permanent-failure")
        @JvmField val OUTCOME_UNKNOWN = GovernanceDeletionStepStatus("outcome-unknown")

        @JvmStatic
        fun of(code: String): GovernanceDeletionStepStatus = when (code) {
            COMPLETED.code -> COMPLETED
            VERIFIED_ABSENT.code -> VERIFIED_ABSENT
            RETRYABLE_FAILURE.code -> RETRYABLE_FAILURE
            PERMANENT_FAILURE.code -> PERMANENT_FAILURE
            OUTCOME_UNKNOWN.code -> OUTCOME_UNKNOWN
            else -> GovernanceDeletionStepStatus(code)
        }
    }
}

/** Fresh execution request for exactly the next step. Every request re-evaluates retention and legal hold. */
class GovernanceDeletionExecutionRequest private constructor(
    val context: GovernanceCallContext,
    val plan: GovernanceDeletionPlan,
    val step: GovernanceDeletionStep,
    val currentAssessment: GovernanceRetentionAssessment,
    val attempt: Int,
    val previousAttempt: GovernanceDeletionStepReceipt?,
    previousReceipts: Collection<GovernanceDeletionStepReceipt>,
    val expectedPlanVersion: Long,
) {
    val previousReceipts: List<GovernanceDeletionStepReceipt> = GovernanceContractSupport.immutableList(
        previousReceipts, GovernanceContractSupport.MAX_STEPS,
        "Governance deletion previous receipts are invalid.",
    )
    val requestDigest: String

    init {
        require(context.purpose == GovernancePurpose.EXECUTE_SECURE_DELETION) {
            "Governance deletion execution requires its exact purpose."
        }
        require(!plan.dryRun) { "Dry-run governance deletion plans cannot be executed." }
        require(context.authorization.resource == plan.resource && context.tenantId == plan.tenantId) {
            "Governance deletion execution does not match the exact tenant and resource."
        }
        require(context.deadlineEpochMilli <= plan.expiresAtEpochMilli) {
            "Governance deletion plan is not valid for the complete execution call window."
        }
        require(currentAssessment.resource == plan.resource && currentAssessment.tenantId == plan.tenantId &&
            currentAssessment.isDeletionEligible() &&
            currentAssessment.legalHolds.status == GovernanceLegalHoldResolutionStatus.CLEAR &&
            currentAssessment.legalHolds.complete &&
            context.deadlineEpochMilli <= currentAssessment.validUntilEpochMilli) {
            "Governance deletion execution requires a current eligible assessment and clear legal holds."
        }
        require(expectedPlanVersion >= 0L) { "Governance deletion expected plan version is invalid." }
        require(attempt >= 1) { "Governance deletion step attempt is invalid." }
        if (attempt == 1) {
            require(previousAttempt == null) { "First governance deletion attempt cannot carry prior attempt evidence." }
        } else {
            require(previousAttempt != null && previousAttempt.planDigest == plan.planDigest &&
                previousAttempt.stepDigest == step.stepDigest && previousAttempt.attempt == attempt - 1 &&
                previousAttempt.status == GovernanceDeletionStepStatus.RETRYABLE_FAILURE) {
                "Governance deletion retry requires the immediately preceding retryable failure; unknown outcomes reconcile."
            }
        }
        val expectedStep = plan.steps.getOrNull(this.previousReceipts.size)
        require(expectedStep?.stepDigest == step.stepDigest && step.sequence == this.previousReceipts.size + 1) {
            "Governance deletion execution must target the exact next plan step."
        }
        require(this.previousReceipts.map { it.stepDigest }.toSet().size == this.previousReceipts.size &&
            this.previousReceipts.indices.all { index ->
                val receipt = this.previousReceipts[index]
                receipt.planDigest == plan.planDigest &&
                    receipt.stepDigest == plan.steps[index].stepDigest && receipt.isSuccessful()
            }) {
            "Governance deletion execution requires contiguous successful receipts for all prior steps."
        }
        val writer = GovernanceContractSupport.digest("flowweft-governance-api-deletion-execution-request-v1")
            .text(context.contextDigest)
            .text(plan.planDigest)
            .text(step.stepDigest)
            .text(currentAssessment.assessmentDigest)
            .integer(attempt)
            .optionalText(previousAttempt?.receiptDigest)
            .longValue(expectedPlanVersion)
            .integer(this.previousReceipts.size)
        this.previousReceipts.forEach { receipt -> writer.text(receipt.receiptDigest) }
        requestDigest = writer.finish()
    }

    override fun toString(): String = "GovernanceDeletionExecutionRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: GovernanceCallContext,
            plan: GovernanceDeletionPlan,
            step: GovernanceDeletionStep,
            currentAssessment: GovernanceRetentionAssessment,
            attempt: Int,
            previousAttempt: GovernanceDeletionStepReceipt?,
            previousReceipts: Collection<GovernanceDeletionStepReceipt>,
            expectedPlanVersion: Long,
        ): GovernanceDeletionExecutionRequest = GovernanceDeletionExecutionRequest(
            context,
            plan,
            step,
            currentAssessment,
            attempt,
            previousAttempt,
            previousReceipts,
            expectedPlanVersion,
        )
    }
}

/** Immutable receipt for one exact execution request or its later reconciliation. */
class GovernanceDeletionStepReceipt private constructor(
    val planDigest: String,
    val stepDigest: String,
    val executionRequestDigest: String,
    val attempt: Int,
    providerId: String,
    providerRevision: String,
    val status: GovernanceDeletionStepStatus,
    receiptReference: String?,
    resultDigest: String,
    val failure: GovernanceFailure?,
    val observedAtEpochMilli: Long,
    reconciliationRequestDigest: String?,
) {
    val providerId: String = GovernanceContractSupport.requireMachineCode(
        providerId, "Governance deletion receipt provider is invalid.",
    )
    val providerRevision: String = GovernanceContractSupport.requireText(
        providerRevision, GovernanceContractSupport.MAX_REVISION_UTF8_BYTES,
        "Governance deletion receipt provider revision is invalid.",
    )
    val receiptReference: String? = receiptReference?.let { reference ->
        GovernanceContractSupport.requireOpaqueReference(
            reference, "Governance deletion receipt reference is invalid.",
        )
    }
    val resultDigest: String = GovernanceContractSupport.requireSha256(
        resultDigest, "Governance deletion receipt result digest is invalid.",
    )
    val reconciliationRequestDigest: String? = reconciliationRequestDigest?.let { digest ->
        GovernanceContractSupport.requireSha256(
            digest, "Governance deletion receipt reconciliation digest is invalid.",
        )
    }
    val receiptDigest: String

    init {
        GovernanceContractSupport.requireSha256(planDigest, "Governance deletion receipt plan digest is invalid.")
        GovernanceContractSupport.requireSha256(stepDigest, "Governance deletion receipt step digest is invalid.")
        GovernanceContractSupport.requireSha256(
            executionRequestDigest, "Governance deletion receipt execution request digest is invalid.",
        )
        require(attempt >= 1) { "Governance deletion receipt attempt is invalid." }
        require(observedAtEpochMilli >= 0L) { "Governance deletion receipt observation time is invalid." }
        when (status) {
            GovernanceDeletionStepStatus.COMPLETED,
            GovernanceDeletionStepStatus.VERIFIED_ABSENT,
            -> require(failure == null && this.receiptReference != null) {
                "Successful governance deletion receipt requires immutable provider evidence."
            }
            GovernanceDeletionStepStatus.RETRYABLE_FAILURE -> require(
                failure?.retryable == true && !failure.reconciliationRequired,
            ) { "Retryable governance deletion receipt requires retryable failure classification." }
            GovernanceDeletionStepStatus.PERMANENT_FAILURE -> require(
                failure != null && !failure.retryable && !failure.reconciliationRequired,
            ) { "Permanent governance deletion receipt requires terminal failure classification." }
            GovernanceDeletionStepStatus.OUTCOME_UNKNOWN -> require(
                failure?.classification == GovernanceFailureClass.OUTCOME_UNKNOWN &&
                    this.receiptReference != null,
            ) { "Unknown governance deletion outcome requires an opaque reconciliation reference." }
            else -> require(false) { "Unknown governance deletion receipt status is fail-closed." }
        }
        receiptDigest = GovernanceContractSupport.digest("flowweft-governance-api-deletion-step-receipt-v1")
            .text(planDigest)
            .text(stepDigest)
            .text(executionRequestDigest)
            .integer(attempt)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(status.code)
            .optionalText(this.receiptReference)
            .text(this.resultDigest)
            .optionalText(failure?.failureDigest)
            .longValue(observedAtEpochMilli)
            .optionalText(this.reconciliationRequestDigest)
            .finish()
    }

    fun isSuccessful(): Boolean =
        status == GovernanceDeletionStepStatus.COMPLETED || status == GovernanceDeletionStepStatus.VERIFIED_ABSENT

    override fun toString(): String = "GovernanceDeletionStepReceipt(<redacted>)"

    companion object {
        /**
         * Restores one canonical durable receipt without retaining its original authorization
         * request. All status invariants are rerun and the independently persisted digest must
         * match the reconstructed fields.
         */
        @JvmStatic
        fun rehydrate(
            planDigest: String,
            stepDigest: String,
            executionRequestDigest: String,
            attempt: Int,
            providerId: String,
            providerRevision: String,
            status: GovernanceDeletionStepStatus,
            receiptReference: String?,
            resultDigest: String,
            failure: GovernanceFailure?,
            observedAtEpochMilli: Long,
            reconciliationRequestDigest: String?,
            expectedReceiptDigest: String,
        ): GovernanceDeletionStepReceipt {
            val restored = GovernanceDeletionStepReceipt(
                planDigest,
                stepDigest,
                executionRequestDigest,
                attempt,
                providerId,
                providerRevision,
                status,
                receiptReference,
                resultDigest,
                failure,
                observedAtEpochMilli,
                reconciliationRequestDigest,
            )
            val expected = GovernanceContractSupport.requireSha256(
                expectedReceiptDigest,
                "Governance persisted deletion receipt digest is invalid.",
            )
            require(restored.receiptDigest == expected) {
                "Governance persisted deletion receipt digest does not match its canonical fields."
            }
            return restored
        }

        @JvmStatic
        fun success(
            request: GovernanceDeletionExecutionRequest,
            providerId: String,
            providerRevision: String,
            status: GovernanceDeletionStepStatus,
            receiptReference: String,
            resultDigest: String,
            observedAtEpochMilli: Long,
        ): GovernanceDeletionStepReceipt {
            require(observedAtEpochMilli in
                request.context.requestedAtEpochMilli..request.context.deadlineEpochMilli) {
                "Governance deletion receipt is outside its execution call window."
            }
            require(successStatusFor(request.step) == status) {
                "Governance deletion success status does not match its surface."
            }
            return GovernanceDeletionStepReceipt(
                request.plan.planDigest,
                request.step.stepDigest,
                request.requestDigest,
                request.attempt,
                providerId,
                providerRevision,
                status,
                receiptReference,
                resultDigest,
                null,
                observedAtEpochMilli,
                null,
            )
        }

        @JvmStatic
        fun failure(
            request: GovernanceDeletionExecutionRequest,
            providerId: String,
            providerRevision: String,
            status: GovernanceDeletionStepStatus,
            receiptReference: String?,
            resultDigest: String,
            failure: GovernanceFailure,
            observedAtEpochMilli: Long,
        ): GovernanceDeletionStepReceipt {
            require(status != GovernanceDeletionStepStatus.COMPLETED &&
                status != GovernanceDeletionStepStatus.VERIFIED_ABSENT) {
                "Governance deletion failure factory cannot use a successful status."
            }
            require(observedAtEpochMilli in
                request.context.requestedAtEpochMilli..request.context.deadlineEpochMilli) {
                "Governance deletion failure receipt is outside its execution call window."
            }
            return GovernanceDeletionStepReceipt(
                request.plan.planDigest,
                request.step.stepDigest,
                request.requestDigest,
                request.attempt,
                providerId,
                providerRevision,
                status,
                receiptReference,
                resultDigest,
                failure,
                observedAtEpochMilli,
                null,
            )
        }

        @JvmStatic
        fun reconciled(
            request: GovernanceDeletionReconciliationRequest,
            status: GovernanceDeletionStepStatus,
            receiptReference: String,
            resultDigest: String,
            failure: GovernanceFailure?,
            observedAtEpochMilli: Long,
        ): GovernanceDeletionStepReceipt {
            require(status != GovernanceDeletionStepStatus.RETRYABLE_FAILURE) {
                "Governance reconciliation cannot turn an unknown mutation into a blind retry."
            }
            require(observedAtEpochMilli in
                request.context.requestedAtEpochMilli..request.context.deadlineEpochMilli) {
                "Governance deletion reconciliation receipt is outside its call window."
            }
            if (status == GovernanceDeletionStepStatus.COMPLETED ||
                status == GovernanceDeletionStepStatus.VERIFIED_ABSENT) {
                require(status == successStatusFor(request.step) && failure == null) {
                    "Governance deletion reconciliation success does not match its surface."
                }
            }
            return GovernanceDeletionStepReceipt(
                request.plan.planDigest,
                request.step.stepDigest,
                request.previousReceipt.executionRequestDigest,
                request.previousReceipt.attempt,
                request.previousReceipt.providerId,
                request.previousReceipt.providerRevision,
                status,
                receiptReference,
                resultDigest,
                failure,
                observedAtEpochMilli,
                request.requestDigest,
            )
        }

        private fun successStatusFor(step: GovernanceDeletionStep): GovernanceDeletionStepStatus = when (
            step.stage.surface
        ) {
            GovernanceDeletionSurface.INDEX,
            GovernanceDeletionSurface.OBJECT,
            -> GovernanceDeletionStepStatus.VERIFIED_ABSENT
            GovernanceDeletionSurface.METADATA,
            GovernanceDeletionSurface.OUTBOX,
            -> GovernanceDeletionStepStatus.COMPLETED
        }
    }
}

/** Fresh, read-only reconciliation of one exact outcome-unknown mutation. */
class GovernanceDeletionReconciliationRequest private constructor(
    val context: GovernanceCallContext,
    val plan: GovernanceDeletionPlan,
    val step: GovernanceDeletionStep,
    val previousReceipt: GovernanceDeletionStepReceipt,
    val currentAssessment: GovernanceRetentionAssessment,
    val expectedPlanVersion: Long,
) {
    val requestDigest: String

    init {
        require(context.purpose == GovernancePurpose.RECONCILE_SECURE_DELETION) {
            "Governance deletion reconciliation requires its exact purpose."
        }
        require(!plan.dryRun && context.tenantId == plan.tenantId &&
            context.authorization.resource == plan.resource && currentAssessment.resource == plan.resource &&
            currentAssessment.tenantId == plan.tenantId) {
            "Governance deletion reconciliation does not match the exact tenant and resource."
        }
        require(context.deadlineEpochMilli <= plan.expiresAtEpochMilli) {
            "Governance deletion plan is not valid for the complete reconciliation call window."
        }
        require(plan.steps.any { it.stepDigest == step.stepDigest } &&
            previousReceipt.planDigest == plan.planDigest && previousReceipt.stepDigest == step.stepDigest &&
            previousReceipt.status == GovernanceDeletionStepStatus.OUTCOME_UNKNOWN) {
            "Governance deletion reconciliation requires the exact outcome-unknown step receipt."
        }
        require(expectedPlanVersion >= 0L) { "Governance reconciliation expected plan version is invalid." }
        requestDigest = GovernanceContractSupport.digest(
            "flowweft-governance-api-deletion-reconciliation-request-v1",
        )
            .text(context.contextDigest)
            .text(plan.planDigest)
            .text(step.stepDigest)
            .text(previousReceipt.receiptDigest)
            .text(currentAssessment.assessmentDigest)
            .longValue(expectedPlanVersion)
            .finish()
    }

    override fun toString(): String = "GovernanceDeletionReconciliationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: GovernanceCallContext,
            plan: GovernanceDeletionPlan,
            step: GovernanceDeletionStep,
            previousReceipt: GovernanceDeletionStepReceipt,
            currentAssessment: GovernanceRetentionAssessment,
            expectedPlanVersion: Long,
        ): GovernanceDeletionReconciliationRequest = GovernanceDeletionReconciliationRequest(
            context,
            plan,
            step,
            previousReceipt,
            currentAssessment,
            expectedPlanVersion,
        )
    }
}
