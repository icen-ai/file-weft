package ai.icen.fw.governance.runtime

import ai.icen.fw.governance.api.GovernanceDeletionExecutionRequest
import ai.icen.fw.governance.api.GovernanceDeletionPlan
import ai.icen.fw.governance.api.GovernanceDeletionStepReceipt
import ai.icen.fw.governance.api.GovernanceDeletionStepStatus
import ai.icen.fw.governance.api.GovernanceFailure
import ai.icen.fw.governance.api.GovernanceFailureClass

class GovernanceDeletionRunStatus private constructor(code: String) {
    val code: String = GovernanceRuntimeSupport.code(code, "Governance deletion run status is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceDeletionRunStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceDeletionRunStatus(<redacted>)"

    companion object {
        @JvmField val READY = GovernanceDeletionRunStatus("ready")
        @JvmField val DISPATCH_PREPARED = GovernanceDeletionRunStatus("dispatch-prepared")
        @JvmField val DISPATCH_STARTED = GovernanceDeletionRunStatus("dispatch-started")
        @JvmField val RETRY_WAIT = GovernanceDeletionRunStatus("retry-wait")
        @JvmField val RECONCILIATION_REQUIRED = GovernanceDeletionRunStatus("reconciliation-required")
        @JvmField val BLOCKED = GovernanceDeletionRunStatus("blocked")
        @JvmField val COMPLETED = GovernanceDeletionRunStatus("completed")
        @JvmField val FAILED = GovernanceDeletionRunStatus("failed")
    }
}

class GovernanceDeletionDispatchPhase private constructor(code: String) {
    val code: String = GovernanceRuntimeSupport.code(code, "Governance deletion dispatch phase is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceDeletionDispatchPhase && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceDeletionDispatchPhase(<redacted>)"

    companion object {
        @JvmField val PREPARED = GovernanceDeletionDispatchPhase("prepared")
        @JvmField val PROVIDER_CALL_STARTED = GovernanceDeletionDispatchPhase("provider-call-started")
    }
}

/** Durable pre-call checkpoint. No executor is retained; provider selection is revalidated by id/revision. */
class GovernanceDeletionDispatch private constructor(
    val request: GovernanceDeletionExecutionRequest,
    providerId: String,
    providerRevision: String,
    operationReference: String,
    val phase: GovernanceDeletionDispatchPhase,
    val preparedAtEpochMilli: Long,
    val startedAtEpochMilli: Long?,
) {
    val providerId: String = GovernanceRuntimeSupport.code(
        providerId, "Governance deletion dispatch provider is invalid.",
    )
    val providerRevision: String = GovernanceRuntimeSupport.text(
        providerRevision,
        GovernanceRuntimeSupport.MAX_REVISION_BYTES,
        "Governance deletion dispatch provider revision is invalid.",
    )
    val operationReference: String = GovernanceRuntimeSupport.opaque(
        operationReference, "Governance deletion operation reference is invalid.",
    )
    val dispatchDigest: String

    init {
        require(operationReference == request.context.idempotencyKey) {
            "Governance deletion operation reference must be the exact provider idempotency key."
        }
        require(preparedAtEpochMilli in
            request.context.requestedAtEpochMilli..request.context.deadlineEpochMilli) {
            "Governance deletion dispatch preparation is outside its call window."
        }
        when (phase) {
            GovernanceDeletionDispatchPhase.PREPARED -> require(startedAtEpochMilli == null) {
                "Prepared governance deletion dispatch cannot claim the provider call started."
            }
            GovernanceDeletionDispatchPhase.PROVIDER_CALL_STARTED -> require(
                startedAtEpochMilli != null && startedAtEpochMilli in
                    preparedAtEpochMilli..request.context.deadlineEpochMilli,
            ) { "Started governance deletion dispatch requires an in-window checkpoint." }
            else -> require(false) { "Unknown governance deletion dispatch phase is unsupported." }
        }
        dispatchDigest = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-dispatch-v1")
            .text(request.requestDigest)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.operationReference)
            .text(phase.code)
            .longValue(preparedAtEpochMilli)
            .longValue(startedAtEpochMilli ?: -1L)
            .finish()
    }

    override fun toString(): String = "GovernanceDeletionDispatch(<redacted>)"

    companion object {
        @JvmStatic
        fun prepared(
            request: GovernanceDeletionExecutionRequest,
            providerId: String,
            providerRevision: String,
            operationReference: String,
            preparedAtEpochMilli: Long,
        ): GovernanceDeletionDispatch = GovernanceDeletionDispatch(
            request,
            providerId,
            providerRevision,
            operationReference,
            GovernanceDeletionDispatchPhase.PREPARED,
            preparedAtEpochMilli,
            null,
        )

        @JvmStatic
        fun started(prepared: GovernanceDeletionDispatch, startedAtEpochMilli: Long): GovernanceDeletionDispatch =
            GovernanceDeletionDispatch(
                prepared.request,
                prepared.providerId,
                prepared.providerRevision,
                prepared.operationReference,
                GovernanceDeletionDispatchPhase.PROVIDER_CALL_STARTED,
                prepared.preparedAtEpochMilli,
                startedAtEpochMilli,
            )
    }
}

/** Immutable runtime aggregate; it stores API plans/receipts instead of redefining governance models. */
class GovernanceDeletionRun private constructor(
    val plan: GovernanceDeletionPlan,
    commandDigest: String,
    idempotencyKey: String,
    val status: GovernanceDeletionRunStatus,
    successfulReceipts: Collection<GovernanceDeletionStepReceipt>,
    val pendingReceipt: GovernanceDeletionStepReceipt?,
    val dispatch: GovernanceDeletionDispatch?,
    val failure: GovernanceFailure?,
    val nextActionAtEpochMilli: Long?,
    val version: Long,
    val updatedAtEpochMilli: Long,
) {
    val tenantId: String = plan.tenantId
    val planId: String = plan.planId
    val commandDigest: String = GovernanceRuntimeSupport.sha256(
        commandDigest, "Governance deletion command digest is invalid.",
    )
    val idempotencyKey: String = GovernanceRuntimeSupport.text(
        idempotencyKey, GovernanceRuntimeSupport.MAX_ID_BYTES, "Governance deletion idempotency key is invalid.",
    )
    val successfulReceipts: List<GovernanceDeletionStepReceipt> = GovernanceRuntimeSupport.immutable(
        successfulReceipts,
        GovernanceDeletionPlan.REQUIRED_STAGE_ORDER.size,
        "Governance deletion successful receipts are invalid.",
    )
    val currentStepIndex: Int = this.successfulReceipts.size
    val stateDigest: String

    init {
        require(!plan.dryRun) { "Dry-run governance plans cannot become durable deletion runs." }
        require(version > 0L && updatedAtEpochMilli >= plan.createdAtEpochMilli) {
            "Governance deletion run version or update time is invalid."
        }
        require(this.successfulReceipts.indices.all { index ->
            val receipt = this.successfulReceipts[index]
            receipt.isSuccessful() && receipt.planDigest == plan.planDigest &&
                receipt.stepDigest == plan.steps[index].stepDigest
        }) { "Governance deletion run receipts must be a contiguous successful prefix." }
        val currentStep = plan.steps.getOrNull(currentStepIndex)
        pendingReceipt?.let { receipt ->
            require(currentStep != null && receipt.planDigest == plan.planDigest &&
                receipt.stepDigest == currentStep.stepDigest && !receipt.isSuccessful()
            ) { "Governance deletion pending receipt is not for the exact next step." }
        }
        dispatch?.let { current ->
            require(currentStep != null && current.request.plan.planDigest == plan.planDigest &&
                current.request.step.stepDigest == currentStep.stepDigest &&
                current.request.previousReceipts.map { it.receiptDigest } ==
                this.successfulReceipts.map { it.receiptDigest }
            ) { "Governance deletion dispatch is not for the exact next step and receipt prefix." }
        }
        when (status) {
            GovernanceDeletionRunStatus.READY -> require(
                currentStep != null && dispatch == null && pendingReceipt == null && failure == null &&
                    nextActionAtEpochMilli == null,
            ) { "Ready governance deletion run has inconsistent execution state." }

            GovernanceDeletionRunStatus.DISPATCH_PREPARED -> require(
                dispatch?.phase == GovernanceDeletionDispatchPhase.PREPARED && pendingReceipt == null &&
                    failure == null && nextActionAtEpochMilli == null,
            ) { "Prepared governance deletion run requires one prepared dispatch." }

            GovernanceDeletionRunStatus.DISPATCH_STARTED -> require(
                dispatch?.phase == GovernanceDeletionDispatchPhase.PROVIDER_CALL_STARTED && pendingReceipt == null &&
                    failure == null && nextActionAtEpochMilli == null,
            ) { "Started governance deletion run requires one started dispatch." }

            GovernanceDeletionRunStatus.RETRY_WAIT -> require(
                dispatch == null && pendingReceipt?.status == GovernanceDeletionStepStatus.RETRYABLE_FAILURE &&
                    pendingReceipt.failure?.retryable == true && failure == null &&
                    nextActionAtEpochMilli != null && nextActionAtEpochMilli > updatedAtEpochMilli,
            ) { "Governance deletion retry wait requires exact retryable attempt evidence." }

            GovernanceDeletionRunStatus.RECONCILIATION_REQUIRED -> require(
                dispatch == null && pendingReceipt?.status == GovernanceDeletionStepStatus.OUTCOME_UNKNOWN &&
                    pendingReceipt.failure?.reconciliationRequired == true && failure == null &&
                    nextActionAtEpochMilli == null,
            ) { "Governance deletion reconciliation requires exact outcome-unknown evidence." }

            GovernanceDeletionRunStatus.BLOCKED -> require(
                dispatch == null && failure != null && nextActionAtEpochMilli == null,
            ) { "Blocked governance deletion run requires value-free blocking evidence." }

            GovernanceDeletionRunStatus.COMPLETED -> require(
                currentStep == null && this.successfulReceipts.size == plan.steps.size &&
                    pendingReceipt == null && dispatch == null && failure == null && nextActionAtEpochMilli == null,
            ) { "Completed governance deletion run requires all seven successful receipts." }

            GovernanceDeletionRunStatus.FAILED -> require(
                dispatch == null && failure != null && nextActionAtEpochMilli == null,
            ) { "Failed governance deletion run requires value-free failure evidence." }

            else -> require(false) { "Unknown governance deletion run status is unsupported." }
        }
        val writer = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-run-v1")
            .text(plan.planDigest)
            .text(this.commandDigest)
            .text(this.idempotencyKey)
            .text(status.code)
            .longValue(version)
            .longValue(updatedAtEpochMilli)
            .longValue(nextActionAtEpochMilli ?: -1L)
            .optionalText(pendingReceipt?.receiptDigest)
            .optionalText(dispatch?.dispatchDigest)
            .optionalText(failure?.failureDigest)
            .integer(this.successfulReceipts.size)
        this.successfulReceipts.forEach { receipt -> writer.text(receipt.receiptDigest) }
        stateDigest = writer.finish()
    }

    fun nextStep() = plan.steps.getOrNull(currentStepIndex)

    override fun toString(): String = "GovernanceDeletionRun(<redacted>)"

    companion object {
        /**
         * Canonical restart boundary for JDBC and other durable stores. Construction reruns the
         * complete aggregate invariant set and the independently persisted digest must match.
         */
        @JvmStatic
        fun rehydrate(
            plan: GovernanceDeletionPlan,
            commandDigest: String,
            idempotencyKey: String,
            status: GovernanceDeletionRunStatus,
            successfulReceipts: Collection<GovernanceDeletionStepReceipt>,
            pendingReceipt: GovernanceDeletionStepReceipt?,
            dispatch: GovernanceDeletionDispatch?,
            failure: GovernanceFailure?,
            nextActionAtEpochMilli: Long?,
            version: Long,
            updatedAtEpochMilli: Long,
            expectedStateDigest: String,
        ): GovernanceDeletionRun {
            val restored = GovernanceDeletionRun(
                plan,
                commandDigest,
                idempotencyKey,
                status,
                successfulReceipts,
                pendingReceipt,
                dispatch,
                failure,
                nextActionAtEpochMilli,
                version,
                updatedAtEpochMilli,
            )
            val expected = GovernanceRuntimeSupport.sha256(
                expectedStateDigest,
                "Governance persisted deletion run digest is invalid.",
            )
            require(restored.stateDigest == expected) {
                "Governance persisted deletion run digest does not match its canonical fields."
            }
            return restored
        }

        @JvmStatic
        fun ready(
            plan: GovernanceDeletionPlan,
            commandDigest: String,
            idempotencyKey: String,
            createdAtEpochMilli: Long,
        ): GovernanceDeletionRun = GovernanceDeletionRun(
            plan,
            commandDigest,
            idempotencyKey,
            GovernanceDeletionRunStatus.READY,
            emptyList(),
            null,
            null,
            null,
            null,
            1L,
            createdAtEpochMilli,
        )

        @JvmStatic
        fun prepare(
            current: GovernanceDeletionRun,
            dispatch: GovernanceDeletionDispatch,
            preparedAtEpochMilli: Long,
        ): GovernanceDeletionRun {
            require(current.status == GovernanceDeletionRunStatus.READY ||
                current.status == GovernanceDeletionRunStatus.RETRY_WAIT &&
                requireNotNull(current.nextActionAtEpochMilli) <= preparedAtEpochMilli
            ) { "Governance deletion run is not ready to prepare its exact next dispatch." }
            require(dispatch.request.expectedPlanVersion == current.version) {
                "Governance deletion dispatch expected version does not match the current CAS version."
            }
            if (current.status == GovernanceDeletionRunStatus.RETRY_WAIT) {
                require(dispatch.request.previousAttempt?.receiptDigest == current.pendingReceipt?.receiptDigest) {
                    "Governance deletion retry dispatch does not carry the exact previous attempt."
                }
            }
            return GovernanceDeletionRun(
                current.plan,
                current.commandDigest,
                current.idempotencyKey,
                GovernanceDeletionRunStatus.DISPATCH_PREPARED,
                current.successfulReceipts,
                null,
                dispatch,
                null,
                null,
                current.version + 1L,
                preparedAtEpochMilli,
            )
        }

        @JvmStatic
        fun markProviderCallStarted(
            current: GovernanceDeletionRun,
            startedAtEpochMilli: Long,
        ): GovernanceDeletionRun {
            require(current.status == GovernanceDeletionRunStatus.DISPATCH_PREPARED) {
                "Only a prepared governance deletion dispatch can start a provider call."
            }
            return GovernanceDeletionRun(
                current.plan,
                current.commandDigest,
                current.idempotencyKey,
                GovernanceDeletionRunStatus.DISPATCH_STARTED,
                current.successfulReceipts,
                null,
                GovernanceDeletionDispatch.started(requireNotNull(current.dispatch), startedAtEpochMilli),
                null,
                null,
                current.version + 1L,
                startedAtEpochMilli,
            )
        }

        /**
         * Safe only before PROVIDER_CALL_STARTED. A later worker uses this transition before it
         * rebuilds evidence and authorization; it never converts an uncertain provider call into a retry.
         */
        @JvmStatic
        fun resetPreparedDispatch(
            current: GovernanceDeletionRun,
            resetAtEpochMilli: Long,
        ): GovernanceDeletionRun {
            require(current.status == GovernanceDeletionRunStatus.DISPATCH_PREPARED) {
                "Only a prepared governance deletion dispatch can be reset without reconciliation."
            }
            val previous = requireNotNull(current.dispatch).request.previousAttempt
            val status = if (previous == null) {
                GovernanceDeletionRunStatus.READY
            } else {
                GovernanceDeletionRunStatus.RETRY_WAIT
            }
            return GovernanceDeletionRun(
                current.plan,
                current.commandDigest,
                current.idempotencyKey,
                status,
                current.successfulReceipts,
                previous,
                null,
                null,
                if (previous == null) null else resetAtEpochMilli + 1L,
                current.version + 1L,
                resetAtEpochMilli,
            )
        }

        @JvmStatic
        @JvmOverloads
        fun recordExecution(
            current: GovernanceDeletionRun,
            receipt: GovernanceDeletionStepReceipt,
            recordedAtEpochMilli: Long,
            retryAtEpochMilli: Long? = null,
        ): GovernanceDeletionRun {
            require(current.status == GovernanceDeletionRunStatus.DISPATCH_STARTED) {
                "Governance deletion execution receipt requires a started provider-call checkpoint."
            }
            val dispatch = requireNotNull(current.dispatch)
            requireReceipt(dispatch, receipt)
            return transitionFromReceipt(current, receipt, recordedAtEpochMilli, retryAtEpochMilli)
        }

        @JvmStatic
        fun recordReconciliation(
            current: GovernanceDeletionRun,
            receipt: GovernanceDeletionStepReceipt,
            reconciliationRequestDigest: String,
            recordedAtEpochMilli: Long,
        ): GovernanceDeletionRun {
            require(current.status == GovernanceDeletionRunStatus.RECONCILIATION_REQUIRED) {
                "Governance deletion reconciliation requires an outcome-unknown run."
            }
            val previous = requireNotNull(current.pendingReceipt)
            require(receipt.planDigest == current.plan.planDigest &&
                receipt.stepDigest == previous.stepDigest &&
                receipt.executionRequestDigest == previous.executionRequestDigest &&
                receipt.attempt == previous.attempt &&
                receipt.providerId == previous.providerId &&
                receipt.providerRevision == previous.providerRevision &&
                receipt.reconciliationRequestDigest == reconciliationRequestDigest &&
                receipt.status != GovernanceDeletionStepStatus.RETRYABLE_FAILURE
            ) { "Governance deletion reconciliation receipt is not bound to the exact unknown operation." }
            return transitionFromReceipt(current, receipt, recordedAtEpochMilli, null)
        }

        @JvmStatic
        fun blocked(
            current: GovernanceDeletionRun,
            failure: GovernanceFailure,
            blockedAtEpochMilli: Long,
        ): GovernanceDeletionRun {
            require(current.status == GovernanceDeletionRunStatus.READY ||
                current.status == GovernanceDeletionRunStatus.RETRY_WAIT ||
                current.status == GovernanceDeletionRunStatus.BLOCKED
            ) { "An in-flight or terminal governance deletion run cannot be blocked retroactively." }
            require(failure.classification == GovernanceFailureClass.LEGAL_HOLD_ACTIVE ||
                failure.classification == GovernanceFailureClass.STALE_EVIDENCE ||
                failure.classification == GovernanceFailureClass.TEMPORARY_UNAVAILABLE
            ) { "Governance deletion blocking failure has an invalid classification." }
            return GovernanceDeletionRun(
                current.plan,
                current.commandDigest,
                current.idempotencyKey,
                GovernanceDeletionRunStatus.BLOCKED,
                current.successfulReceipts,
                current.pendingReceipt,
                null,
                failure,
                null,
                current.version + 1L,
                blockedAtEpochMilli,
            )
        }

        @JvmStatic
        fun resume(current: GovernanceDeletionRun, resumedAtEpochMilli: Long): GovernanceDeletionRun {
            require(current.status == GovernanceDeletionRunStatus.BLOCKED) {
                "Only a blocked governance deletion run can resume after fresh clear evidence."
            }
            val pending = current.pendingReceipt
            val status = if (pending?.status == GovernanceDeletionStepStatus.RETRYABLE_FAILURE) {
                GovernanceDeletionRunStatus.RETRY_WAIT
            } else {
                GovernanceDeletionRunStatus.READY
            }
            val retryAt = if (status == GovernanceDeletionRunStatus.RETRY_WAIT) resumedAtEpochMilli + 1L else null
            return GovernanceDeletionRun(
                current.plan,
                current.commandDigest,
                current.idempotencyKey,
                status,
                current.successfulReceipts,
                pending,
                null,
                null,
                retryAt,
                current.version + 1L,
                resumedAtEpochMilli,
            )
        }

        @JvmStatic
        fun failed(
            current: GovernanceDeletionRun,
            failure: GovernanceFailure,
            failedAtEpochMilli: Long,
        ): GovernanceDeletionRun {
            require(current.status != GovernanceDeletionRunStatus.COMPLETED) {
                "Completed governance deletion cannot become failed."
            }
            return GovernanceDeletionRun(
                current.plan,
                current.commandDigest,
                current.idempotencyKey,
                GovernanceDeletionRunStatus.FAILED,
                current.successfulReceipts,
                current.pendingReceipt,
                null,
                failure,
                null,
                current.version + 1L,
                failedAtEpochMilli,
            )
        }

        private fun requireReceipt(
            dispatch: GovernanceDeletionDispatch,
            receipt: GovernanceDeletionStepReceipt,
        ) {
            require(receipt.planDigest == dispatch.request.plan.planDigest &&
                receipt.stepDigest == dispatch.request.step.stepDigest &&
                receipt.executionRequestDigest == dispatch.request.requestDigest &&
                receipt.attempt == dispatch.request.attempt &&
                receipt.providerId == dispatch.providerId &&
                receipt.providerRevision == dispatch.providerRevision
            ) { "Governance deletion provider receipt is not bound to the exact dispatch." }
            if (receipt.status == GovernanceDeletionStepStatus.OUTCOME_UNKNOWN) {
                require(receipt.receiptReference == dispatch.operationReference) {
                    "Unknown governance deletion outcome must retain the exact original operation reference."
                }
            }
        }

        private fun transitionFromReceipt(
            current: GovernanceDeletionRun,
            receipt: GovernanceDeletionStepReceipt,
            recordedAtEpochMilli: Long,
            retryAtEpochMilli: Long?,
        ): GovernanceDeletionRun {
            val successful = receipt.isSuccessful()
            val receipts = if (successful) current.successfulReceipts + receipt else current.successfulReceipts
            val status: GovernanceDeletionRunStatus
            val pending: GovernanceDeletionStepReceipt?
            val failure: GovernanceFailure?
            val nextAction: Long?
            when {
                successful && receipts.size == current.plan.steps.size -> {
                    status = GovernanceDeletionRunStatus.COMPLETED
                    pending = null
                    failure = null
                    nextAction = null
                }
                successful -> {
                    status = GovernanceDeletionRunStatus.READY
                    pending = null
                    failure = null
                    nextAction = null
                }
                receipt.status == GovernanceDeletionStepStatus.RETRYABLE_FAILURE -> {
                    require(retryAtEpochMilli != null && retryAtEpochMilli > recordedAtEpochMilli) {
                        "Retryable governance deletion receipt requires a future retry time."
                    }
                    status = GovernanceDeletionRunStatus.RETRY_WAIT
                    pending = receipt
                    failure = null
                    nextAction = retryAtEpochMilli
                }
                receipt.status == GovernanceDeletionStepStatus.OUTCOME_UNKNOWN -> {
                    require(retryAtEpochMilli == null) {
                        "Unknown governance deletion outcome cannot schedule a blind retry."
                    }
                    status = GovernanceDeletionRunStatus.RECONCILIATION_REQUIRED
                    pending = receipt
                    failure = null
                    nextAction = null
                }
                else -> {
                    require(receipt.status == GovernanceDeletionStepStatus.PERMANENT_FAILURE &&
                        receipt.failure != null && retryAtEpochMilli == null
                    ) { "Governance deletion receipt has an unsupported terminal state." }
                    status = GovernanceDeletionRunStatus.FAILED
                    pending = receipt
                    failure = receipt.failure
                    nextAction = null
                }
            }
            return GovernanceDeletionRun(
                current.plan,
                current.commandDigest,
                current.idempotencyKey,
                status,
                receipts,
                pending,
                null,
                failure,
                nextAction,
                current.version + 1L,
                recordedAtEpochMilli,
            )
        }
    }
}

class GovernanceOutboxType private constructor(code: String) {
    val code: String = GovernanceRuntimeSupport.code(code, "Governance outbox type is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is GovernanceOutboxType && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceOutboxType(<redacted>)"

    companion object {
        @JvmField val RUN_READY = GovernanceOutboxType("run-ready")
        @JvmField val STEP_READY = GovernanceOutboxType("step-ready")
        @JvmField val RETRY_READY = GovernanceOutboxType("retry-ready")
        @JvmField val RECONCILIATION_REQUIRED = GovernanceOutboxType("reconciliation-required")
        @JvmField val RUN_BLOCKED = GovernanceOutboxType("run-blocked")
        @JvmField val RUN_COMPLETED = GovernanceOutboxType("run-completed")
        @JvmField val RUN_FAILED = GovernanceOutboxType("run-failed")
        @JvmField val STATE_CHECKPOINTED = GovernanceOutboxType("state-checkpointed")
    }
}

/** Durable wake-up/audit signal stored atomically with one exact CAS state transition. */
class GovernanceOutboxRecord private constructor(
    recordId: String,
    val type: GovernanceOutboxType,
    val run: GovernanceDeletionRun,
    val createdAtEpochMilli: Long,
) {
    val recordId: String = GovernanceRuntimeSupport.opaque(recordId, "Governance outbox id is invalid.")
    val tenantId: String = run.tenantId
    val planId: String = run.planId
    val runVersion: Long = run.version
    val stateDigest: String = run.stateDigest
    val recordDigest: String

    init {
        require(createdAtEpochMilli >= run.updatedAtEpochMilli) {
            "Governance outbox record predates its state transition."
        }
        recordDigest = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-outbox-v1")
            .text(this.recordId)
            .text(type.code)
            .text(tenantId)
            .text(planId)
            .longValue(runVersion)
            .text(stateDigest)
            .longValue(createdAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "GovernanceOutboxRecord(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            recordId: String,
            type: GovernanceOutboxType,
            run: GovernanceDeletionRun,
            createdAtEpochMilli: Long,
        ): GovernanceOutboxRecord = GovernanceOutboxRecord(recordId, type, run, createdAtEpochMilli)
    }
}

class GovernanceStoreCode private constructor(code: String) {
    val code: String = GovernanceRuntimeSupport.code(code, "Governance store result code is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is GovernanceStoreCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceStoreCode(<redacted>)"

    companion object {
        @JvmField val STORED = GovernanceStoreCode("stored")
        @JvmField val REPLAYED = GovernanceStoreCode("replayed")
        @JvmField val CONFLICT = GovernanceStoreCode("conflict")
        @JvmField val OUTCOME_UNKNOWN = GovernanceStoreCode("outcome-unknown")
    }
}

class GovernanceStoreResult private constructor(
    val code: GovernanceStoreCode,
    val run: GovernanceDeletionRun?,
) {
    init {
        require((code == GovernanceStoreCode.STORED || code == GovernanceStoreCode.REPLAYED) == (run != null)) {
            "Governance store result content is inconsistent."
        }
    }

    override fun toString(): String = "GovernanceStoreResult(<redacted>)"

    companion object {
        @JvmStatic fun stored(run: GovernanceDeletionRun): GovernanceStoreResult =
            GovernanceStoreResult(GovernanceStoreCode.STORED, run)
        @JvmStatic fun replayed(run: GovernanceDeletionRun): GovernanceStoreResult =
            GovernanceStoreResult(GovernanceStoreCode.REPLAYED, run)
        @JvmStatic fun failed(code: GovernanceStoreCode): GovernanceStoreResult {
            require(code == GovernanceStoreCode.CONFLICT || code == GovernanceStoreCode.OUTCOME_UNKNOWN)
            return GovernanceStoreResult(code, null)
        }
    }
}

/**
 * Short local transactions only. [compareAndSet] atomically stores the candidate and outbox row,
 * closes the transaction, and only then returns. It must never call authorization, providers, or workers.
 */
interface GovernanceDeletionRepository {
    fun findByIdempotency(tenantId: String, idempotencyKey: String): GovernanceDeletionRun?

    fun load(tenantId: String, planId: String): GovernanceDeletionRun?

    fun compareAndSet(
        tenantId: String,
        planId: String,
        expectedVersion: Long?,
        candidate: GovernanceDeletionRun,
        outbox: GovernanceOutboxRecord,
    ): GovernanceStoreResult
}

class GovernanceOutboxClaimRequest private constructor(
    tenantId: String,
    workerId: String,
    claimId: String,
    val nowEpochMilli: Long,
    val leaseExpiresAtEpochMilli: Long,
    val maximumRecords: Int,
) {
    val tenantId: String = GovernanceRuntimeSupport.text(
        tenantId, GovernanceRuntimeSupport.MAX_ID_BYTES, "Governance outbox claim tenant is invalid.",
    )
    val workerId: String = GovernanceRuntimeSupport.opaque(workerId, "Governance outbox worker id is invalid.")
    val claimId: String = GovernanceRuntimeSupport.opaque(claimId, "Governance outbox claim id is invalid.")

    init {
        require(nowEpochMilli >= 0L && leaseExpiresAtEpochMilli > nowEpochMilli && maximumRecords in 1..128) {
            "Governance outbox claim window or size is invalid."
        }
    }

    override fun toString(): String = "GovernanceOutboxClaimRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            workerId: String,
            claimId: String,
            nowEpochMilli: Long,
            leaseExpiresAtEpochMilli: Long,
            maximumRecords: Int,
        ): GovernanceOutboxClaimRequest = GovernanceOutboxClaimRequest(
            tenantId, workerId, claimId, nowEpochMilli, leaseExpiresAtEpochMilli, maximumRecords,
        )
    }
}

class GovernanceClaimedOutboxRecord private constructor(
    val record: GovernanceOutboxRecord,
    claimId: String,
    workerId: String,
    val fencingToken: Long,
    val leaseExpiresAtEpochMilli: Long,
) {
    val claimId: String = GovernanceRuntimeSupport.opaque(claimId, "Governance outbox claim id is invalid.")
    val workerId: String = GovernanceRuntimeSupport.opaque(workerId, "Governance outbox worker id is invalid.")

    init {
        require(fencingToken > 0L && leaseExpiresAtEpochMilli > record.createdAtEpochMilli) {
            "Governance outbox claim fence or lease is invalid."
        }
    }

    override fun toString(): String = "GovernanceClaimedOutboxRecord(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            record: GovernanceOutboxRecord,
            claimId: String,
            workerId: String,
            fencingToken: Long,
            leaseExpiresAtEpochMilli: Long,
        ): GovernanceClaimedOutboxRecord = GovernanceClaimedOutboxRecord(
            record, claimId, workerId, fencingToken, leaseExpiresAtEpochMilli,
        )
    }
}

/** Durable outbox repository. Claim/ack methods are short transactions and never call a worker. */
interface GovernanceOutboxRepository {
    fun claimReady(request: GovernanceOutboxClaimRequest): List<GovernanceClaimedOutboxRecord>

    fun acknowledge(claim: GovernanceClaimedOutboxRecord, acknowledgedAtEpochMilli: Long): Boolean
}
