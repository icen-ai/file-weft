package ai.icen.fw.governance.runtime

import ai.icen.fw.governance.api.GovernanceDeletionExecutionRequest
import ai.icen.fw.governance.api.GovernanceDeletionReconciliationRequest
import ai.icen.fw.governance.api.GovernanceDeletionStage
import ai.icen.fw.governance.api.GovernanceFailure

/**
 * Provider-neutral kind of one opaque deletion target item. Values describe the governed surface,
 * never a vendor, bucket, object key, URL, index name, or credential.
 */
class GovernanceDeletionTargetItemKind private constructor(code: String) {
    val code: String = GovernanceRuntimeSupport.code(
        code, "Governance deletion target item kind is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceDeletionTargetItemKind && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceDeletionTargetItemKind(code=$code)"

    companion object {
        @JvmField val METADATA = GovernanceDeletionTargetItemKind("metadata")
        @JvmField val AUDIT_RECORD = GovernanceDeletionTargetItemKind("audit-record")
        @JvmField val OUTBOX_RECORD = GovernanceDeletionTargetItemKind("outbox-record")
        @JvmField val INDEX_PROJECTION = GovernanceDeletionTargetItemKind("index-projection")
        @JvmField val OBJECT_CONTENT = GovernanceDeletionTargetItemKind("object-content")

        @JvmStatic
        fun of(code: String): GovernanceDeletionTargetItemKind = when (code) {
            METADATA.code -> METADATA
            AUDIT_RECORD.code -> AUDIT_RECORD
            OUTBOX_RECORD.code -> OUTBOX_RECORD
            INDEX_PROJECTION.code -> INDEX_PROJECTION
            OBJECT_CONTENT.code -> OBJECT_CONTENT
            else -> GovernanceDeletionTargetItemKind(code)
        }
    }
}

/** One immutable item in a target manifest. [itemRef] is an opaque internal reference only. */
class GovernanceDeletionTargetItem private constructor(
    val ordinal: Int,
    val kind: GovernanceDeletionTargetItemKind,
    itemRef: String,
    itemRevision: String,
    itemDigest: String,
    providerId: String,
    providerRevision: String,
) {
    val itemRef: String = GovernanceRuntimeSupport.opaque(
        itemRef, "Governance deletion target item reference is invalid.",
    )
    val itemRevision: String = GovernanceRuntimeSupport.text(
        itemRevision,
        GovernanceRuntimeSupport.MAX_REVISION_BYTES,
        "Governance deletion target item revision is invalid.",
    )
    val itemDigest: String = GovernanceRuntimeSupport.sha256(
        itemDigest, "Governance deletion target item digest is invalid.",
    )
    val providerId: String = GovernanceRuntimeSupport.code(
        providerId, "Governance deletion target item provider is invalid.",
    )
    val providerRevision: String = GovernanceRuntimeSupport.text(
        providerRevision,
        GovernanceRuntimeSupport.MAX_REVISION_BYTES,
        "Governance deletion target item provider revision is invalid.",
    )
    /** Stable semantic identity; unlike [itemBindingDigest], canonical ordering is deliberately excluded. */
    val itemIdentityDigest: String
    val itemBindingDigest: String

    init {
        require(ordinal in 1..GovernanceRuntimeSupport.MAX_ITEMS) {
            "Governance deletion target item ordinal is invalid."
        }
        itemIdentityDigest = GovernanceRuntimeSupport.digest(
            "flowweft-governance-runtime-target-item-identity-v1",
        )
            .text(kind.code)
            .text(this.itemRef)
            .text(this.itemRevision)
            .text(this.itemDigest)
            .text(this.providerId)
            .text(this.providerRevision)
            .finish()
        itemBindingDigest = GovernanceRuntimeSupport.digest(
            "flowweft-governance-runtime-target-item-v1",
        )
            .integer(ordinal)
            .text(kind.code)
            .text(this.itemRef)
            .text(this.itemRevision)
            .text(this.itemDigest)
            .text(this.providerId)
            .text(this.providerRevision)
            .finish()
    }

    override fun toString(): String = "GovernanceDeletionTargetItem(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            ordinal: Int,
            kind: GovernanceDeletionTargetItemKind,
            itemRef: String,
            itemRevision: String,
            itemDigest: String,
            providerId: String,
            providerRevision: String,
        ): GovernanceDeletionTargetItem = GovernanceDeletionTargetItem(
            ordinal,
            kind,
            itemRef,
            itemRevision,
            itemDigest,
            providerId,
            providerRevision,
        )
    }
}

/**
 * Durable target snapshot created while planning, before a plan identifier exists. Its stable
 * identity is the exact target request plus stage. It never invents or predicts a plan identity.
 */
class GovernanceDeletionTargetManifest private constructor(
    tenantId: String,
    planningRequestDigest: String,
    planningIdentityDigest: String,
    resourceReferenceDigest: String,
    assessmentDigest: String,
    val stage: GovernanceDeletionStage,
    targetRef: String,
    targetRevision: String,
    targetDigest: String,
    items: Collection<GovernanceDeletionTargetItem>,
    val createdAtEpochMilli: Long,
) {
    val tenantId: String = GovernanceRuntimeSupport.text(
        tenantId, GovernanceRuntimeSupport.MAX_ID_BYTES, "Governance target manifest tenant is invalid.",
    )
    val planningRequestDigest: String = GovernanceRuntimeSupport.sha256(
        planningRequestDigest, "Governance target manifest planning request digest is invalid.",
    )
    val planningIdentityDigest: String = GovernanceRuntimeSupport.sha256(
        planningIdentityDigest, "Governance target manifest planning identity digest is invalid.",
    )
    val resourceReferenceDigest: String = GovernanceRuntimeSupport.sha256(
        resourceReferenceDigest, "Governance target manifest resource digest is invalid.",
    )
    val assessmentDigest: String = GovernanceRuntimeSupport.sha256(
        assessmentDigest, "Governance target manifest assessment digest is invalid.",
    )
    val targetRef: String = GovernanceRuntimeSupport.opaque(
        targetRef, "Governance target manifest reference is invalid.",
    )
    val targetRevision: String = GovernanceRuntimeSupport.text(
        targetRevision,
        GovernanceRuntimeSupport.MAX_REVISION_BYTES,
        "Governance target manifest revision is invalid.",
    )
    val targetDigest: String = GovernanceRuntimeSupport.sha256(
        targetDigest, "Governance target manifest target digest is invalid.",
    )
    val items: List<GovernanceDeletionTargetItem> = GovernanceRuntimeSupport.immutable(
        items, GovernanceRuntimeSupport.MAX_ITEMS, "Governance target manifest items are invalid.",
    )
    val preparationDigest: String
    val targetBindingDigest: String
    val manifestDigest: String

    init {
        require(createdAtEpochMilli >= 0L) { "Governance target manifest creation time is invalid." }
        require(this.items.isNotEmpty() && this.items.map { it.ordinal } == (1..this.items.size).toList()) {
            "Governance target manifest items must have a complete canonical order."
        }
        require(this.items.all { item -> itemKindMatchesStage(stage, item.kind) }) {
            "Governance target manifest item kind does not match its stage."
        }
        require(this.items.map { it.itemIdentityDigest }.toSet().size == this.items.size) {
            "Governance target manifest contains semantically duplicate items."
        }
        require(this.targetDigest == calculateTargetDigest(stage, this.targetRevision, this.items)) {
            "Governance target manifest target digest is not canonical."
        }
        preparationDigest = calculatePreparationDigest(this.planningIdentityDigest, stage)
        targetBindingDigest = GovernanceRuntimeSupport.digest(
            "flowweft-governance-runtime-target-manifest-target-v1",
        )
            .text(stage.name)
            .text(this.targetRef)
            .text(this.targetRevision)
            .text(this.targetDigest)
            .finish()
        val digest = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-target-manifest-v1")
            .text(this.tenantId)
            .text(this.planningRequestDigest)
            .text(this.planningIdentityDigest)
            .text(this.preparationDigest)
            .text(this.resourceReferenceDigest)
            .text(this.assessmentDigest)
            .text(this.targetBindingDigest)
            .longValue(createdAtEpochMilli)
            .integer(this.items.size)
        this.items.forEach { item -> digest.text(item.itemBindingDigest) }
        manifestDigest = digest.finish()
    }

    fun asTarget(): GovernanceDeletionTarget = GovernanceDeletionTarget.of(
        stage, targetRef, targetRevision, targetDigest,
    )

    /** Builds the final plan/step binding after planning assigned the real plan and step digests. */
    fun bind(request: GovernanceDeletionExecutionRequest): GovernanceDeletionTargetExecutionBinding =
        GovernanceDeletionTargetExecutionBinding.of(request, this)

    override fun toString(): String = "GovernanceDeletionTargetManifest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            request: GovernanceDeletionTargetRequest,
            target: GovernanceDeletionTarget,
            items: Collection<GovernanceDeletionTargetItem>,
        ): GovernanceDeletionTargetManifest = GovernanceDeletionTargetManifest(
            request.context.tenantId,
            request.requestDigest,
            calculatePlanningIdentityDigest(request),
            request.context.authorization.resource.referenceDigest,
            request.assessmentDigest,
            target.stage,
            target.targetRef,
            target.targetRevision,
            target.targetDigest,
            items,
            request.context.requestedAtEpochMilli,
        )

        @JvmStatic
        fun rehydrate(
            tenantId: String,
            planningRequestDigest: String,
            planningIdentityDigest: String,
            resourceReferenceDigest: String,
            assessmentDigest: String,
            stage: GovernanceDeletionStage,
            targetRef: String,
            targetRevision: String,
            targetDigest: String,
            items: Collection<GovernanceDeletionTargetItem>,
            createdAtEpochMilli: Long,
            expectedPreparationDigest: String,
            expectedTargetBindingDigest: String,
            expectedManifestDigest: String,
        ): GovernanceDeletionTargetManifest = GovernanceDeletionTargetManifest(
            tenantId,
            planningRequestDigest,
            planningIdentityDigest,
            resourceReferenceDigest,
            assessmentDigest,
            stage,
            targetRef,
            targetRevision,
            targetDigest,
            items,
            createdAtEpochMilli,
        ).also { value ->
            require(value.preparationDigest == GovernanceRuntimeSupport.sha256(
                expectedPreparationDigest, "Governance target manifest preparation digest is invalid.",
            ) && value.targetBindingDigest == GovernanceRuntimeSupport.sha256(
                expectedTargetBindingDigest, "Governance target manifest binding digest is invalid.",
            ) && value.manifestDigest == GovernanceRuntimeSupport.sha256(
                expectedManifestDigest, "Governance target manifest digest is invalid.",
            )) { "Governance target manifest canonical digest is invalid." }
        }

        @JvmStatic
        fun calculatePlanningIdentityDigest(request: GovernanceDeletionTargetRequest): String =
            GovernanceRuntimeSupport.digest("flowweft-governance-runtime-target-planning-identity-v1")
                .text(request.context.tenantId)
                .text(request.context.idempotencyKey)
                .text(request.context.authorization.resource.referenceDigest)
                .text(request.assessmentDigest)
                .finish()

        @JvmStatic
        fun calculatePreparationDigest(
            planningIdentityDigest: String,
            stage: GovernanceDeletionStage,
        ): String = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-target-preparation-v1")
            .text(GovernanceRuntimeSupport.sha256(
                planningIdentityDigest, "Governance target preparation identity digest is invalid.",
            ))
            .text(stage.name)
            .finish()

        @JvmStatic
        fun calculateTargetDigest(
            stage: GovernanceDeletionStage,
            targetRevision: String,
            items: Collection<GovernanceDeletionTargetItem>,
        ): String {
            val canonical = GovernanceRuntimeSupport.immutable(
                items, GovernanceRuntimeSupport.MAX_ITEMS, "Governance target digest items are invalid.",
            )
            require(canonical.isNotEmpty() && canonical.map { it.ordinal } == (1..canonical.size).toList()) {
                "Governance target digest items are not canonical."
            }
            require(canonical.all { item -> itemKindMatchesStage(stage, item.kind) }) {
                "Governance target digest item kind does not match its stage."
            }
            require(canonical.map { it.itemIdentityDigest }.toSet().size == canonical.size) {
                "Governance target digest contains semantically duplicate items."
            }
            val writer = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-target-content-v1")
                .text(stage.name)
                .text(GovernanceRuntimeSupport.text(
                    targetRevision,
                    GovernanceRuntimeSupport.MAX_REVISION_BYTES,
                    "Governance target revision is invalid.",
                ))
                .integer(canonical.size)
            canonical.forEach { writer.text(it.itemBindingDigest) }
            return writer.finish()
        }

        private fun itemKindMatchesStage(
            stage: GovernanceDeletionStage,
            kind: GovernanceDeletionTargetItemKind,
        ): Boolean = when (stage) {
            GovernanceDeletionStage.PERSIST_TOMBSTONE,
            GovernanceDeletionStage.FINALIZE_METADATA,
            -> kind == GovernanceDeletionTargetItemKind.METADATA
            GovernanceDeletionStage.APPEND_DECISION_AUDIT,
            GovernanceDeletionStage.APPEND_COMPLETION_AUDIT,
            -> kind == GovernanceDeletionTargetItemKind.AUDIT_RECORD
            GovernanceDeletionStage.ENQUEUE_PURGE_OUTBOX ->
                kind == GovernanceDeletionTargetItemKind.OUTBOX_RECORD
            GovernanceDeletionStage.PURGE_INDEX_PROJECTIONS ->
                kind == GovernanceDeletionTargetItemKind.INDEX_PROJECTION
            GovernanceDeletionStage.PURGE_OBJECT_CONTENT ->
                kind == GovernanceDeletionTargetItemKind.OBJECT_CONTENT
        }
    }
}

/** Exact post-planning binding; no plan identity is present in the pre-planning manifest. */
class GovernanceDeletionTargetExecutionBinding private constructor(
    tenantId: String,
    planDigest: String,
    stepDigest: String,
    planningRequestDigest: String,
    planningIdentityDigest: String,
    val stage: GovernanceDeletionStage,
    targetRef: String,
    targetRevision: String,
    targetDigest: String,
    manifestDigest: String,
) {
    val tenantId: String = GovernanceRuntimeSupport.text(
        tenantId, GovernanceRuntimeSupport.MAX_ID_BYTES, "Governance target binding tenant is invalid.",
    )
    val planDigest: String = GovernanceRuntimeSupport.sha256(
        planDigest, "Governance target binding plan digest is invalid.",
    )
    val stepDigest: String = GovernanceRuntimeSupport.sha256(
        stepDigest, "Governance target binding step digest is invalid.",
    )
    val planningRequestDigest: String = GovernanceRuntimeSupport.sha256(
        planningRequestDigest, "Governance target binding planning request digest is invalid.",
    )
    val planningIdentityDigest: String = GovernanceRuntimeSupport.sha256(
        planningIdentityDigest, "Governance target binding planning identity digest is invalid.",
    )
    val targetRef: String = GovernanceRuntimeSupport.opaque(
        targetRef, "Governance target binding reference is invalid.",
    )
    val targetRevision: String = GovernanceRuntimeSupport.text(
        targetRevision,
        GovernanceRuntimeSupport.MAX_REVISION_BYTES,
        "Governance target binding revision is invalid.",
    )
    val targetDigest: String = GovernanceRuntimeSupport.sha256(
        targetDigest, "Governance target binding target digest is invalid.",
    )
    val manifestDigest: String = GovernanceRuntimeSupport.sha256(
        manifestDigest, "Governance target binding manifest digest is invalid.",
    )
    val preparationDigest: String = GovernanceDeletionTargetManifest.calculatePreparationDigest(
        this.planningIdentityDigest, stage,
    )
    val bindingDigest: String = GovernanceRuntimeSupport.digest(
        "flowweft-governance-runtime-target-execution-binding-v1",
    )
        .text(this.tenantId)
        .text(this.planDigest)
        .text(this.stepDigest)
        .text(this.planningRequestDigest)
        .text(this.planningIdentityDigest)
        .text(this.preparationDigest)
        .text(stage.name)
        .text(this.targetRef)
        .text(this.targetRevision)
        .text(this.targetDigest)
        .text(this.manifestDigest)
        .finish()

    override fun toString(): String = "GovernanceDeletionTargetExecutionBinding(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            request: GovernanceDeletionExecutionRequest,
            manifest: GovernanceDeletionTargetManifest,
        ): GovernanceDeletionTargetExecutionBinding {
            val planningRequest = GovernanceDeletionTargetRequest.of(
                request.plan.context, request.plan.assessment.assessmentDigest,
            )
            val planningIdentityDigest = GovernanceDeletionTargetManifest.calculatePlanningIdentityDigest(
                planningRequest,
            )
            require(manifest.tenantId == request.plan.tenantId &&
                manifest.resourceReferenceDigest == request.plan.resource.referenceDigest &&
                manifest.assessmentDigest == request.plan.assessment.assessmentDigest &&
                manifest.planningRequestDigest == planningRequest.requestDigest &&
                manifest.planningIdentityDigest == planningIdentityDigest &&
                manifest.stage == request.step.stage &&
                manifest.targetRef == request.step.targetRef &&
                manifest.targetRevision == request.step.targetRevision &&
                manifest.targetDigest == request.step.targetDigest
            ) { "Governance target manifest does not match the exact planned deletion step." }
            return GovernanceDeletionTargetExecutionBinding(
                request.plan.tenantId,
                request.plan.planDigest,
                request.step.stepDigest,
                planningRequest.requestDigest,
                planningIdentityDigest,
                request.step.stage,
                request.step.targetRef,
                request.step.targetRevision,
                request.step.targetDigest,
                manifest.manifestDigest,
            )
        }

        @JvmStatic
        fun rehydrate(
            tenantId: String,
            planDigest: String,
            stepDigest: String,
            planningRequestDigest: String,
            planningIdentityDigest: String,
            stage: GovernanceDeletionStage,
            targetRef: String,
            targetRevision: String,
            targetDigest: String,
            manifestDigest: String,
            expectedPreparationDigest: String,
            expectedBindingDigest: String,
        ): GovernanceDeletionTargetExecutionBinding = GovernanceDeletionTargetExecutionBinding(
            tenantId,
            planDigest,
            stepDigest,
            planningRequestDigest,
            planningIdentityDigest,
            stage,
            targetRef,
            targetRevision,
            targetDigest,
            manifestDigest,
        ).also { value ->
            require(value.preparationDigest == GovernanceRuntimeSupport.sha256(
                expectedPreparationDigest, "Governance target execution preparation digest is invalid.",
            ) && value.bindingDigest == GovernanceRuntimeSupport.sha256(
                expectedBindingDigest, "Governance target execution binding digest is invalid.",
            )) { "Governance target execution binding canonical digest is invalid." }
        }
    }
}

class GovernanceDeletionTargetItemOperationStatus private constructor(code: String) {
    val code: String = GovernanceRuntimeSupport.code(
        code, "Governance target item operation status is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceDeletionTargetItemOperationStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceDeletionTargetItemOperationStatus(code=$code)"

    companion object {
        @JvmField val PREPARED = GovernanceDeletionTargetItemOperationStatus("prepared")
        @JvmField val STARTED = GovernanceDeletionTargetItemOperationStatus("started")
        @JvmField val VERIFIED_ABSENT = GovernanceDeletionTargetItemOperationStatus("verified-absent")
        @JvmField val OUTCOME_UNKNOWN = GovernanceDeletionTargetItemOperationStatus("outcome-unknown")
        @JvmField val PERMANENT_FAILURE = GovernanceDeletionTargetItemOperationStatus("permanent-failure")

        @JvmStatic
        fun of(code: String): GovernanceDeletionTargetItemOperationStatus = when (code) {
            PREPARED.code -> PREPARED
            STARTED.code -> STARTED
            VERIFIED_ABSENT.code -> VERIFIED_ABSENT
            OUTCOME_UNKNOWN.code -> OUTCOME_UNKNOWN
            PERMANENT_FAILURE.code -> PERMANENT_FAILURE
            else -> throw IllegalArgumentException("Governance target item operation status is unsupported.")
        }
    }
}

/** Immutable provider or read-only reconciliation observation for one target item. */
class GovernanceDeletionTargetItemOutcome private constructor(
    operationKeyDigest: String,
    sourceOperationStateDigest: String,
    val status: GovernanceDeletionTargetItemOperationStatus,
    receiptReference: String,
    resultDigest: String,
    val failure: GovernanceFailure?,
    val observedAtEpochMilli: Long,
) {
    val operationKeyDigest: String = GovernanceRuntimeSupport.sha256(
        operationKeyDigest, "Governance target item outcome operation key is invalid.",
    )
    val sourceOperationStateDigest: String = GovernanceRuntimeSupport.sha256(
        sourceOperationStateDigest, "Governance target item outcome source state is invalid.",
    )
    val receiptReference: String = GovernanceRuntimeSupport.opaque(
        receiptReference, "Governance target item outcome receipt is invalid.",
    )
    val resultDigest: String = GovernanceRuntimeSupport.sha256(
        resultDigest, "Governance target item outcome result digest is invalid.",
    )
    val outcomeDigest: String

    init {
        require(status == GovernanceDeletionTargetItemOperationStatus.VERIFIED_ABSENT ||
            status == GovernanceDeletionTargetItemOperationStatus.OUTCOME_UNKNOWN ||
            status == GovernanceDeletionTargetItemOperationStatus.PERMANENT_FAILURE
        ) { "Governance target item outcome status is invalid." }
        require(observedAtEpochMilli >= 0L) { "Governance target item outcome time is invalid." }
        when (status) {
            GovernanceDeletionTargetItemOperationStatus.VERIFIED_ABSENT -> require(failure == null) {
                "Verified-absent governance target item cannot carry a failure."
            }
            GovernanceDeletionTargetItemOperationStatus.OUTCOME_UNKNOWN -> require(
                failure != null && !failure.retryable && failure.reconciliationRequired,
            ) { "Unknown governance target item outcome requires reconciliation evidence." }
            GovernanceDeletionTargetItemOperationStatus.PERMANENT_FAILURE -> require(
                failure != null && !failure.retryable && !failure.reconciliationRequired,
            ) { "Permanent governance target item failure is invalid." }
            else -> throw IllegalArgumentException("Governance target item outcome status is unsupported.")
        }
        outcomeDigest = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-target-item-outcome-v1")
            .text(this.operationKeyDigest)
            .text(this.sourceOperationStateDigest)
            .text(status.code)
            .text(this.receiptReference)
            .text(this.resultDigest)
            .optionalText(failure?.failureDigest)
            .longValue(observedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "GovernanceDeletionTargetItemOutcome(<redacted>)"

    companion object {
        @JvmStatic
        fun verifiedAbsent(
            sourceOperation: GovernanceDeletionTargetItemOperation,
            receiptReference: String,
            resultDigest: String,
            observedAtEpochMilli: Long,
        ): GovernanceDeletionTargetItemOutcome {
            requireObservableSource(sourceOperation)
            return GovernanceDeletionTargetItemOutcome(
                sourceOperation.operationKeyDigest,
                sourceOperation.stateDigest,
                GovernanceDeletionTargetItemOperationStatus.VERIFIED_ABSENT,
                receiptReference,
                resultDigest,
                null,
                observedAtEpochMilli,
            )
        }

        @JvmStatic
        fun outcomeUnknown(
            sourceOperation: GovernanceDeletionTargetItemOperation,
            resultDigest: String,
            failure: GovernanceFailure,
            observedAtEpochMilli: Long,
        ): GovernanceDeletionTargetItemOutcome {
            requireObservableSource(sourceOperation)
            return GovernanceDeletionTargetItemOutcome(
                sourceOperation.operationKeyDigest,
                sourceOperation.stateDigest,
                GovernanceDeletionTargetItemOperationStatus.OUTCOME_UNKNOWN,
                sourceOperation.operationReference,
                resultDigest,
                failure,
                observedAtEpochMilli,
            )
        }

        @JvmStatic
        fun permanentFailure(
            sourceOperation: GovernanceDeletionTargetItemOperation,
            receiptReference: String,
            resultDigest: String,
            failure: GovernanceFailure,
            observedAtEpochMilli: Long,
        ): GovernanceDeletionTargetItemOutcome {
            requireObservableSource(sourceOperation)
            return GovernanceDeletionTargetItemOutcome(
                sourceOperation.operationKeyDigest,
                sourceOperation.stateDigest,
                GovernanceDeletionTargetItemOperationStatus.PERMANENT_FAILURE,
                receiptReference,
                resultDigest,
                failure,
                observedAtEpochMilli,
            )
        }

        @JvmStatic
        fun rehydrate(
            operationKeyDigest: String,
            sourceOperationStateDigest: String,
            status: GovernanceDeletionTargetItemOperationStatus,
            receiptReference: String,
            resultDigest: String,
            failure: GovernanceFailure?,
            observedAtEpochMilli: Long,
            expectedOutcomeDigest: String,
        ): GovernanceDeletionTargetItemOutcome = GovernanceDeletionTargetItemOutcome(
            operationKeyDigest,
            sourceOperationStateDigest,
            status,
            receiptReference,
            resultDigest,
            failure,
            observedAtEpochMilli,
        ).also { value ->
            require(value.outcomeDigest == GovernanceRuntimeSupport.sha256(
                expectedOutcomeDigest, "Governance target item outcome digest is invalid.",
            )) { "Governance target item outcome canonical digest is invalid." }
        }

        private fun requireObservableSource(source: GovernanceDeletionTargetItemOperation) {
            require(source.status == GovernanceDeletionTargetItemOperationStatus.STARTED ||
                source.status == GovernanceDeletionTargetItemOperationStatus.OUTCOME_UNKNOWN
            ) { "Governance target item outcome requires an exact started or unknown source operation." }
        }
    }
}

/** Durable per-item mutation checkpoint. Reconciliation transitions are observations only. */
class GovernanceDeletionTargetItemOperation private constructor(
    val binding: GovernanceDeletionTargetExecutionBinding,
    itemBindingDigest: String,
    providerId: String,
    providerRevision: String,
    operationReference: String,
    executionRequestDigest: String,
    val providerCallDeadlineEpochMilli: Long,
    val status: GovernanceDeletionTargetItemOperationStatus,
    val version: Long,
    val preparedAtEpochMilli: Long,
    val startedAtEpochMilli: Long?,
    val outcome: GovernanceDeletionTargetItemOutcome?,
    reconciliationRequestDigest: String?,
    val reconciliationRequestedAtEpochMilli: Long?,
    val reconciliationStartedAtEpochMilli: Long?,
    val reconciliationDeadlineEpochMilli: Long?,
    val updatedAtEpochMilli: Long,
) {
    val itemBindingDigest: String = GovernanceRuntimeSupport.sha256(
        itemBindingDigest, "Governance target item operation item digest is invalid.",
    )
    val providerId: String = GovernanceRuntimeSupport.code(
        providerId, "Governance target item operation provider is invalid.",
    )
    val providerRevision: String = GovernanceRuntimeSupport.text(
        providerRevision,
        GovernanceRuntimeSupport.MAX_REVISION_BYTES,
        "Governance target item operation provider revision is invalid.",
    )
    val operationReference: String = GovernanceRuntimeSupport.opaque(
        operationReference, "Governance target item operation reference is invalid.",
    )
    val executionRequestDigest: String = GovernanceRuntimeSupport.sha256(
        executionRequestDigest, "Governance target item execution request digest is invalid.",
    )
    val reconciliationRequestDigest: String? = reconciliationRequestDigest?.let {
        GovernanceRuntimeSupport.sha256(it, "Governance target item reconciliation digest is invalid.")
    }
    val operationKeyDigest: String = calculateOperationKeyDigest(binding, this.itemBindingDigest)
    val stateDigest: String

    init {
        require(version > 0L && preparedAtEpochMilli >= 0L &&
            providerCallDeadlineEpochMilli >= preparedAtEpochMilli && updatedAtEpochMilli >= preparedAtEpochMilli
        ) {
            "Governance target item operation version or time is invalid."
        }
        if (startedAtEpochMilli != null) {
            require(startedAtEpochMilli <= providerCallDeadlineEpochMilli) {
                "Governance target item operation started outside its authorized call window."
            }
        }
        when (status) {
            GovernanceDeletionTargetItemOperationStatus.PREPARED -> require(
                version == 1L && startedAtEpochMilli == null && outcome == null &&
                    this.reconciliationRequestDigest == null && reconciliationRequestedAtEpochMilli == null &&
                    reconciliationStartedAtEpochMilli == null && reconciliationDeadlineEpochMilli == null &&
                    updatedAtEpochMilli == preparedAtEpochMilli,
            ) { "Prepared governance target item operation is invalid." }
            GovernanceDeletionTargetItemOperationStatus.STARTED -> require(
                version == 2L && startedAtEpochMilli != null && startedAtEpochMilli >= preparedAtEpochMilli &&
                    outcome == null && this.reconciliationRequestDigest == null &&
                    reconciliationRequestedAtEpochMilli == null && reconciliationStartedAtEpochMilli == null &&
                    reconciliationDeadlineEpochMilli == null && updatedAtEpochMilli == startedAtEpochMilli,
            ) { "Started governance target item operation is invalid." }
            GovernanceDeletionTargetItemOperationStatus.VERIFIED_ABSENT,
            GovernanceDeletionTargetItemOperationStatus.OUTCOME_UNKNOWN,
            GovernanceDeletionTargetItemOperationStatus.PERMANENT_FAILURE,
            -> require(
                startedAtEpochMilli != null && outcome != null && outcome.status == status &&
                    outcome.operationKeyDigest == operationKeyDigest &&
                    outcome.observedAtEpochMilli >= startedAtEpochMilli &&
                    updatedAtEpochMilli == outcome.observedAtEpochMilli &&
                    if (this.reconciliationRequestDigest == null) {
                        version == 3L && reconciliationRequestedAtEpochMilli == null &&
                            reconciliationStartedAtEpochMilli == null && reconciliationDeadlineEpochMilli == null
                    } else {
                        version >= 4L && reconciliationRequestedAtEpochMilli != null &&
                            reconciliationStartedAtEpochMilli != null && reconciliationDeadlineEpochMilli != null &&
                            reconciliationRequestedAtEpochMilli >= 0L &&
                            reconciliationDeadlineEpochMilli > reconciliationRequestedAtEpochMilli &&
                            reconciliationStartedAtEpochMilli in
                            reconciliationRequestedAtEpochMilli..reconciliationDeadlineEpochMilli &&
                            outcome.observedAtEpochMilli >= reconciliationStartedAtEpochMilli
                    },
            ) { "Resolved governance target item operation is invalid." }
            else -> throw IllegalArgumentException("Governance target item operation status is unsupported.")
        }
        if (status == GovernanceDeletionTargetItemOperationStatus.OUTCOME_UNKNOWN) {
            require(outcome?.receiptReference == this.operationReference) {
                "Unknown governance target item outcome must retain its original operation reference."
            }
        }
        stateDigest = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-target-item-operation-v1")
            .text(operationKeyDigest)
            .text(binding.bindingDigest)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.operationReference)
            .text(this.executionRequestDigest)
            .longValue(providerCallDeadlineEpochMilli)
            .text(status.code)
            .longValue(version)
            .longValue(preparedAtEpochMilli)
            .optionalText(startedAtEpochMilli?.toString())
            .optionalText(outcome?.outcomeDigest)
            .optionalText(this.reconciliationRequestDigest)
            .optionalText(reconciliationRequestedAtEpochMilli?.toString())
            .optionalText(reconciliationStartedAtEpochMilli?.toString())
            .optionalText(reconciliationDeadlineEpochMilli?.toString())
            .longValue(updatedAtEpochMilli)
            .finish()
    }

    fun isValidSuccessorOf(previous: GovernanceDeletionTargetItemOperation): Boolean {
        if (!sameImmutableBinding(previous) || version != previous.version + 1L ||
            preparedAtEpochMilli != previous.preparedAtEpochMilli ||
            previous.startedAtEpochMilli != null && startedAtEpochMilli != previous.startedAtEpochMilli ||
            updatedAtEpochMilli < previous.updatedAtEpochMilli
        ) return false
        return when (previous.status) {
            GovernanceDeletionTargetItemOperationStatus.PREPARED ->
                status == GovernanceDeletionTargetItemOperationStatus.STARTED
            GovernanceDeletionTargetItemOperationStatus.STARTED ->
                outcomeBindsTo(previous) && reconciliationRequestDigest == null &&
                    reconciliationRequestedAtEpochMilli == null && reconciliationStartedAtEpochMilli == null &&
                    reconciliationDeadlineEpochMilli == null && (
                    status == GovernanceDeletionTargetItemOperationStatus.VERIFIED_ABSENT ||
                        status == GovernanceDeletionTargetItemOperationStatus.OUTCOME_UNKNOWN ||
                        status == GovernanceDeletionTargetItemOperationStatus.PERMANENT_FAILURE
                    )
            GovernanceDeletionTargetItemOperationStatus.OUTCOME_UNKNOWN ->
                outcomeBindsTo(previous) && reconciliationRequestDigest != null &&
                    reconciliationRequestedAtEpochMilli != null && reconciliationStartedAtEpochMilli != null &&
                    reconciliationDeadlineEpochMilli != null &&
                    reconciliationStartedAtEpochMilli >= previous.updatedAtEpochMilli && (
                    status == GovernanceDeletionTargetItemOperationStatus.VERIFIED_ABSENT ||
                        status == GovernanceDeletionTargetItemOperationStatus.OUTCOME_UNKNOWN ||
                        status == GovernanceDeletionTargetItemOperationStatus.PERMANENT_FAILURE
                    )
            GovernanceDeletionTargetItemOperationStatus.VERIFIED_ABSENT,
            GovernanceDeletionTargetItemOperationStatus.PERMANENT_FAILURE,
            -> false
            else -> false
        }
    }

    private fun sameImmutableBinding(other: GovernanceDeletionTargetItemOperation): Boolean =
        binding.bindingDigest == other.binding.bindingDigest &&
            itemBindingDigest == other.itemBindingDigest &&
            providerId == other.providerId &&
            providerRevision == other.providerRevision &&
            operationReference == other.operationReference &&
            executionRequestDigest == other.executionRequestDigest &&
            providerCallDeadlineEpochMilli == other.providerCallDeadlineEpochMilli &&
            operationKeyDigest == other.operationKeyDigest

    private fun outcomeBindsTo(source: GovernanceDeletionTargetItemOperation): Boolean {
        val value = outcome ?: return false
        return value.operationKeyDigest == source.operationKeyDigest &&
            value.sourceOperationStateDigest == source.stateDigest
    }

    override fun toString(): String = "GovernanceDeletionTargetItemOperation(<redacted>)"

    companion object {
        @JvmStatic
        fun calculateOperationKeyDigest(
            binding: GovernanceDeletionTargetExecutionBinding,
            itemBindingDigest: String,
        ): String = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-target-item-key-v1")
            .text(binding.tenantId)
            .text(binding.planDigest)
            .text(binding.stepDigest)
            .text(binding.manifestDigest)
            .text(GovernanceRuntimeSupport.sha256(
                itemBindingDigest, "Governance target item operation item digest is invalid.",
            ))
            .finish()

        @JvmStatic
        fun prepared(
            request: GovernanceDeletionExecutionRequest,
            manifest: GovernanceDeletionTargetManifest,
            item: GovernanceDeletionTargetItem,
            operationReference: String,
            preparedAtEpochMilli: Long,
        ): GovernanceDeletionTargetItemOperation {
            require(manifest.items.any { it.itemBindingDigest == item.itemBindingDigest }) {
                "Governance target item operation item is not in its exact manifest."
            }
            require(preparedAtEpochMilli in request.context.requestedAtEpochMilli..request.context.deadlineEpochMilli) {
                "Governance target item operation preparation is outside its authorized call window."
            }
            return GovernanceDeletionTargetItemOperation(
                manifest.bind(request),
                item.itemBindingDigest,
                item.providerId,
                item.providerRevision,
                operationReference,
                request.requestDigest,
                request.context.deadlineEpochMilli,
                GovernanceDeletionTargetItemOperationStatus.PREPARED,
                1L,
                preparedAtEpochMilli,
                null,
                null,
                null,
                null,
                null,
                null,
                preparedAtEpochMilli,
            )
        }

        @JvmStatic
        fun markStarted(
            current: GovernanceDeletionTargetItemOperation,
            startedAtEpochMilli: Long,
        ): GovernanceDeletionTargetItemOperation {
            require(current.status == GovernanceDeletionTargetItemOperationStatus.PREPARED &&
                startedAtEpochMilli in current.preparedAtEpochMilli..current.providerCallDeadlineEpochMilli
            ) { "Only a prepared governance target item operation can start." }
            return copy(
                current,
                GovernanceDeletionTargetItemOperationStatus.STARTED,
                startedAtEpochMilli,
                null,
                null,
                null,
                null,
                null,
                startedAtEpochMilli,
            )
        }

        @JvmStatic
        fun recordProviderOutcome(
            current: GovernanceDeletionTargetItemOperation,
            outcome: GovernanceDeletionTargetItemOutcome,
        ): GovernanceDeletionTargetItemOperation {
            require(current.status == GovernanceDeletionTargetItemOperationStatus.STARTED &&
                outcome.operationKeyDigest == current.operationKeyDigest &&
                outcome.sourceOperationStateDigest == current.stateDigest &&
                outcome.observedAtEpochMilli >= requireNotNull(current.startedAtEpochMilli)
            ) { "Governance target item provider outcome requires an exact started operation." }
            return copy(
                current,
                outcome.status,
                current.startedAtEpochMilli,
                outcome,
                null,
                null,
                null,
                null,
                outcome.observedAtEpochMilli,
            )
        }

        /** Records only a read-only observation for the exact unknown operation; it never dispatches mutation. */
        @JvmStatic
        fun recordReconciliation(
            current: GovernanceDeletionTargetItemOperation,
            request: GovernanceDeletionReconciliationRequest,
            outcome: GovernanceDeletionTargetItemOutcome,
            reconciliationStartedAtEpochMilli: Long,
        ): GovernanceDeletionTargetItemOperation {
            require(current.status == GovernanceDeletionTargetItemOperationStatus.OUTCOME_UNKNOWN) {
                "Governance target item reconciliation requires an outcome-unknown operation."
            }
            require(request.context.tenantId == current.binding.tenantId &&
                request.plan.planDigest == current.binding.planDigest &&
                request.step.stepDigest == current.binding.stepDigest &&
                request.previousReceipt.executionRequestDigest == current.executionRequestDigest &&
                outcome.operationKeyDigest == current.operationKeyDigest &&
                outcome.sourceOperationStateDigest == current.stateDigest &&
                reconciliationStartedAtEpochMilli >= current.updatedAtEpochMilli &&
                reconciliationStartedAtEpochMilli in
                request.context.requestedAtEpochMilli..request.context.deadlineEpochMilli &&
                outcome.observedAtEpochMilli >= reconciliationStartedAtEpochMilli
            ) { "Governance target item reconciliation requires an exact unknown operation." }
            return copy(
                current,
                outcome.status,
                current.startedAtEpochMilli,
                outcome,
                request.requestDigest,
                request.context.requestedAtEpochMilli,
                reconciliationStartedAtEpochMilli,
                request.context.deadlineEpochMilli,
                outcome.observedAtEpochMilli,
            )
        }

        @JvmStatic
        fun rehydrate(
            binding: GovernanceDeletionTargetExecutionBinding,
            itemBindingDigest: String,
            providerId: String,
            providerRevision: String,
            operationReference: String,
            executionRequestDigest: String,
            providerCallDeadlineEpochMilli: Long,
            status: GovernanceDeletionTargetItemOperationStatus,
            version: Long,
            preparedAtEpochMilli: Long,
            startedAtEpochMilli: Long?,
            outcome: GovernanceDeletionTargetItemOutcome?,
            reconciliationRequestDigest: String?,
            reconciliationRequestedAtEpochMilli: Long?,
            reconciliationStartedAtEpochMilli: Long?,
            reconciliationDeadlineEpochMilli: Long?,
            updatedAtEpochMilli: Long,
            expectedOperationKeyDigest: String,
            expectedStateDigest: String,
        ): GovernanceDeletionTargetItemOperation = GovernanceDeletionTargetItemOperation(
            binding,
            itemBindingDigest,
            providerId,
            providerRevision,
            operationReference,
            executionRequestDigest,
            providerCallDeadlineEpochMilli,
            status,
            version,
            preparedAtEpochMilli,
            startedAtEpochMilli,
            outcome,
            reconciliationRequestDigest,
            reconciliationRequestedAtEpochMilli,
            reconciliationStartedAtEpochMilli,
            reconciliationDeadlineEpochMilli,
            updatedAtEpochMilli,
        ).also { value ->
            require(value.operationKeyDigest == GovernanceRuntimeSupport.sha256(
                expectedOperationKeyDigest, "Governance target item operation key digest is invalid.",
            ) && value.stateDigest == GovernanceRuntimeSupport.sha256(
                expectedStateDigest, "Governance target item operation state digest is invalid.",
            )) { "Governance target item operation canonical digest is invalid." }
        }

        private fun copy(
            current: GovernanceDeletionTargetItemOperation,
            status: GovernanceDeletionTargetItemOperationStatus,
            startedAtEpochMilli: Long?,
            outcome: GovernanceDeletionTargetItemOutcome?,
            reconciliationRequestDigest: String?,
            reconciliationRequestedAtEpochMilli: Long?,
            reconciliationStartedAtEpochMilli: Long?,
            reconciliationDeadlineEpochMilli: Long?,
            updatedAtEpochMilli: Long,
        ): GovernanceDeletionTargetItemOperation = GovernanceDeletionTargetItemOperation(
            current.binding,
            current.itemBindingDigest,
            current.providerId,
            current.providerRevision,
            current.operationReference,
            current.executionRequestDigest,
            current.providerCallDeadlineEpochMilli,
            status,
            current.version + 1L,
            current.preparedAtEpochMilli,
            startedAtEpochMilli,
            outcome,
            reconciliationRequestDigest,
            reconciliationRequestedAtEpochMilli,
            reconciliationStartedAtEpochMilli,
            reconciliationDeadlineEpochMilli,
            updatedAtEpochMilli,
        )
    }
}

class GovernanceDeletionTargetManifestStoreResult private constructor(
    val code: GovernanceStoreCode,
    val manifest: GovernanceDeletionTargetManifest?,
) {
    init {
        require((code == GovernanceStoreCode.STORED || code == GovernanceStoreCode.REPLAYED) ==
            (manifest != null)
        ) { "Governance target manifest store result is inconsistent." }
    }

    override fun toString(): String = "GovernanceDeletionTargetManifestStoreResult(<redacted>)"

    companion object {
        @JvmStatic fun stored(value: GovernanceDeletionTargetManifest) =
            GovernanceDeletionTargetManifestStoreResult(GovernanceStoreCode.STORED, value)
        @JvmStatic fun replayed(value: GovernanceDeletionTargetManifest) =
            GovernanceDeletionTargetManifestStoreResult(GovernanceStoreCode.REPLAYED, value)
        @JvmStatic fun failed(code: GovernanceStoreCode): GovernanceDeletionTargetManifestStoreResult {
            require(code == GovernanceStoreCode.CONFLICT || code == GovernanceStoreCode.OUTCOME_UNKNOWN)
            return GovernanceDeletionTargetManifestStoreResult(code, null)
        }
    }
}

class GovernanceDeletionTargetItemOperationStoreResult private constructor(
    val code: GovernanceStoreCode,
    val operation: GovernanceDeletionTargetItemOperation?,
) {
    init {
        require((code == GovernanceStoreCode.STORED || code == GovernanceStoreCode.REPLAYED) ==
            (operation != null)
        ) { "Governance target item operation store result is inconsistent." }
    }

    override fun toString(): String = "GovernanceDeletionTargetItemOperationStoreResult(<redacted>)"

    companion object {
        @JvmStatic fun stored(value: GovernanceDeletionTargetItemOperation) =
            GovernanceDeletionTargetItemOperationStoreResult(GovernanceStoreCode.STORED, value)
        @JvmStatic fun replayed(value: GovernanceDeletionTargetItemOperation) =
            GovernanceDeletionTargetItemOperationStoreResult(GovernanceStoreCode.REPLAYED, value)
        @JvmStatic fun failed(code: GovernanceStoreCode): GovernanceDeletionTargetItemOperationStoreResult {
            require(code == GovernanceStoreCode.CONFLICT || code == GovernanceStoreCode.OUTCOME_UNKNOWN)
            return GovernanceDeletionTargetItemOperationStoreResult(code, null)
        }
    }
}

interface GovernanceDeletionTargetManifestRepository {
    fun findByPreparation(tenantId: String, preparationDigest: String): GovernanceDeletionTargetManifest?

    /** Returns only a manifest that binds exactly to the real plan step, or fails closed. */
    fun findExact(request: GovernanceDeletionExecutionRequest): GovernanceDeletionTargetManifest?

    fun createIfAbsent(
        manifest: GovernanceDeletionTargetManifest,
    ): GovernanceDeletionTargetManifestStoreResult
}

interface GovernanceDeletionTargetItemOperationRepository {
    fun load(
        binding: GovernanceDeletionTargetExecutionBinding,
        itemBindingDigest: String,
    ): GovernanceDeletionTargetItemOperation?

    fun prepare(
        candidate: GovernanceDeletionTargetItemOperation,
    ): GovernanceDeletionTargetItemOperationStoreResult

    fun compareAndSet(
        expected: GovernanceDeletionTargetItemOperation,
        candidate: GovernanceDeletionTargetItemOperation,
    ): GovernanceDeletionTargetItemOperationStoreResult
}

/** Deterministic in-memory implementation for focused host and contract tests. */
class InMemoryGovernanceDeletionTargetLedger :
    GovernanceDeletionTargetManifestRepository,
    GovernanceDeletionTargetItemOperationRepository {
    private val manifests = linkedMapOf<String, GovernanceDeletionTargetManifest>()
    private val operations = linkedMapOf<String, GovernanceDeletionTargetItemOperation>()

    @Synchronized
    override fun findByPreparation(
        tenantId: String,
        preparationDigest: String,
    ): GovernanceDeletionTargetManifest? = manifests[manifestKey(tenantId, preparationDigest)]

    @Synchronized
    override fun findExact(request: GovernanceDeletionExecutionRequest): GovernanceDeletionTargetManifest? {
        val planningRequest = GovernanceDeletionTargetRequest.of(
            request.plan.context, request.plan.assessment.assessmentDigest,
        )
        val preparationDigest = GovernanceDeletionTargetManifest.calculatePreparationDigest(
            GovernanceDeletionTargetManifest.calculatePlanningIdentityDigest(planningRequest),
            request.step.stage,
        )
        return findByPreparation(request.plan.tenantId, preparationDigest)?.also { it.bind(request) }
    }

    @Synchronized
    override fun createIfAbsent(
        manifest: GovernanceDeletionTargetManifest,
    ): GovernanceDeletionTargetManifestStoreResult {
        val key = manifestKey(manifest.tenantId, manifest.preparationDigest)
        val current = manifests[key]
        if (current != null) {
            return if (current.manifestDigest == manifest.manifestDigest) {
                GovernanceDeletionTargetManifestStoreResult.replayed(current)
            } else {
                GovernanceDeletionTargetManifestStoreResult.failed(GovernanceStoreCode.CONFLICT)
            }
        }
        if (manifests.values.any { value ->
                value.tenantId == manifest.tenantId && value.stage == manifest.stage &&
                    value.targetRef == manifest.targetRef
            }) {
            return GovernanceDeletionTargetManifestStoreResult.failed(GovernanceStoreCode.CONFLICT)
        }
        manifests[key] = manifest
        return GovernanceDeletionTargetManifestStoreResult.stored(manifest)
    }

    @Synchronized
    override fun load(
        binding: GovernanceDeletionTargetExecutionBinding,
        itemBindingDigest: String,
    ): GovernanceDeletionTargetItemOperation? = operations[operationKey(binding, itemBindingDigest)]

    @Synchronized
    override fun prepare(
        candidate: GovernanceDeletionTargetItemOperation,
    ): GovernanceDeletionTargetItemOperationStoreResult {
        require(candidate.status == GovernanceDeletionTargetItemOperationStatus.PREPARED && candidate.version == 1L) {
            "Governance target item repository can only prepare an initial operation."
        }
        val manifest = manifests[manifestKey(
            candidate.binding.tenantId, candidate.binding.preparationDigest,
        )]
        if (manifest == null || !manifestMatchesBinding(manifest, candidate.binding) ||
            manifest.items.none { it.itemBindingDigest == candidate.itemBindingDigest }
        ) {
            return GovernanceDeletionTargetItemOperationStoreResult.failed(GovernanceStoreCode.CONFLICT)
        }
        val key = operationKey(candidate.binding, candidate.itemBindingDigest)
        val current = operations[key]
        if (current != null) {
            return if (current.stateDigest == candidate.stateDigest) {
                GovernanceDeletionTargetItemOperationStoreResult.replayed(current)
            } else {
                GovernanceDeletionTargetItemOperationStoreResult.failed(GovernanceStoreCode.CONFLICT)
            }
        }
        if (operations.values.any { operation ->
                operation.binding.tenantId == candidate.binding.tenantId &&
                    operation.providerId == candidate.providerId &&
                    operation.providerRevision == candidate.providerRevision &&
                    operation.operationReference == candidate.operationReference
            }) {
            return GovernanceDeletionTargetItemOperationStoreResult.failed(GovernanceStoreCode.CONFLICT)
        }
        operations[key] = candidate
        return GovernanceDeletionTargetItemOperationStoreResult.stored(candidate)
    }

    @Synchronized
    override fun compareAndSet(
        expected: GovernanceDeletionTargetItemOperation,
        candidate: GovernanceDeletionTargetItemOperation,
    ): GovernanceDeletionTargetItemOperationStoreResult {
        require(candidate.isValidSuccessorOf(expected)) {
            "Governance target item repository transition is invalid."
        }
        val manifest = manifests[manifestKey(expected.binding.tenantId, expected.binding.preparationDigest)]
        if (manifest == null || !manifestMatchesBinding(manifest, expected.binding) ||
            manifest.items.none { it.itemBindingDigest == expected.itemBindingDigest }
        ) {
            return GovernanceDeletionTargetItemOperationStoreResult.failed(GovernanceStoreCode.CONFLICT)
        }
        val key = operationKey(expected.binding, expected.itemBindingDigest)
        val current = operations[key]
            ?: return GovernanceDeletionTargetItemOperationStoreResult.failed(GovernanceStoreCode.CONFLICT)
        if (current.stateDigest == candidate.stateDigest) {
            return GovernanceDeletionTargetItemOperationStoreResult.replayed(current)
        }
        if (current.version != expected.version || current.stateDigest != expected.stateDigest) {
            return GovernanceDeletionTargetItemOperationStoreResult.failed(GovernanceStoreCode.CONFLICT)
        }
        operations[key] = candidate
        return GovernanceDeletionTargetItemOperationStoreResult.stored(candidate)
    }

    override fun toString(): String = "InMemoryGovernanceDeletionTargetLedger(<redacted>)"

    private fun manifestKey(tenantId: String, preparationDigest: String): String =
        GovernanceRuntimeSupport.digest("flowweft-governance-runtime-manifest-map-key-v1")
            .text(GovernanceRuntimeSupport.text(
                tenantId, GovernanceRuntimeSupport.MAX_ID_BYTES, "Governance target manifest tenant is invalid.",
            ))
            .text(GovernanceRuntimeSupport.sha256(
                preparationDigest, "Governance target manifest preparation digest is invalid.",
            ))
            .finish()

    private fun operationKey(
        binding: GovernanceDeletionTargetExecutionBinding,
        itemBindingDigest: String,
    ): String = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-operation-map-key-v1")
        .text(binding.tenantId)
        .text(binding.bindingDigest)
        .text(GovernanceRuntimeSupport.sha256(
            itemBindingDigest, "Governance target item binding digest is invalid.",
        ))
        .finish()

    private fun manifestMatchesBinding(
        manifest: GovernanceDeletionTargetManifest,
        binding: GovernanceDeletionTargetExecutionBinding,
    ): Boolean = manifest.tenantId == binding.tenantId &&
        manifest.preparationDigest == binding.preparationDigest &&
        manifest.planningRequestDigest == binding.planningRequestDigest &&
        manifest.planningIdentityDigest == binding.planningIdentityDigest &&
        manifest.stage == binding.stage &&
        manifest.targetRef == binding.targetRef &&
        manifest.targetRevision == binding.targetRevision &&
        manifest.targetDigest == binding.targetDigest &&
        manifest.manifestDigest == binding.manifestDigest
}
