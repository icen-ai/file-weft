package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.domain.WorkflowCommandCode
import ai.icen.fw.workflow.domain.WorkflowDefinitionExecutionReceipt
import ai.icen.fw.workflow.domain.WorkflowDefinitionIndex
import ai.icen.fw.workflow.domain.WorkflowDomainEvent
import ai.icen.fw.workflow.domain.WorkflowDomainResult
import ai.icen.fw.workflow.domain.WorkflowEffectIntent
import ai.icen.fw.workflow.domain.WorkflowEventCode
import ai.icen.fw.workflow.domain.WorkflowInstanceState
import ai.icen.fw.workflow.domain.WorkflowResultCode

class WorkflowRuntimeDefinitionRecord private constructor(
    val index: WorkflowDefinitionIndex,
    val executionReceipt: WorkflowDefinitionExecutionReceipt,
) {
    init {
        val definition = index.definition
        require(executionReceipt.tenantId == definition.tenantId &&
            executionReceipt.definitionId == definition.definitionId &&
            executionReceipt.definitionRef == definition.ref &&
            executionReceipt.schemaVersion == definition.schemaVersion
        ) { "Workflow runtime definition record binding is invalid." }
    }

    override fun toString(): String = "WorkflowRuntimeDefinitionRecord(<redacted>)"

    companion object {
        @JvmStatic fun of(
            index: WorkflowDefinitionIndex,
            executionReceipt: WorkflowDefinitionExecutionReceipt,
        ): WorkflowRuntimeDefinitionRecord = WorkflowRuntimeDefinitionRecord(index, executionReceipt)
    }
}

class WorkflowRuntimeIdempotencyRecord private constructor(
    tenantId: String,
    instanceId: String,
    idempotencyKey: String,
    logicalRequestDigest: String,
    val commandCode: WorkflowCommandCode,
    domainCommandDigest: String,
    resultVersion: Long,
    val effectCount: Int,
    val domainResultCode: WorkflowResultCode,
    committedAt: Long,
) {
    val tenantId: String = runtimeId(tenantId, "idempotency tenant")
    val instanceId: String = runtimeId(instanceId, "idempotency instance")
    val idempotencyKey: String = runtimeId(idempotencyKey, "idempotency key")
    val logicalRequestDigest: String = runtimeSha(logicalRequestDigest, "logical request")
    val domainCommandDigest: String = runtimeSha(domainCommandDigest, "domain command")
    val resultVersion: Long = WorkflowRuntimeSupport.nonNegative(
        resultVersion,
        "Workflow idempotency result version is invalid.",
    )
    val committedAt: Long = WorkflowRuntimeSupport.nonNegative(
        committedAt,
        "Workflow idempotency commit time is invalid.",
    )

    init {
        require(this.resultVersion > 0L && effectCount >= 0 &&
            (domainResultCode == WorkflowResultCode.APPLIED ||
                domainResultCode == WorkflowResultCode.BUDGET_EXHAUSTED ||
                domainResultCode == WorkflowResultCode.INCIDENT)
        ) { "Workflow idempotency record is invalid." }
    }

    fun matches(logicalRequestDigest: String, state: WorkflowInstanceState?): Boolean =
        this.logicalRequestDigest == logicalRequestDigest &&
            state != null && state.tenantId == tenantId && state.instanceId == instanceId &&
            resultVersion <= state.version

    override fun toString(): String = "WorkflowRuntimeIdempotencyRecord(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            instanceId: String,
            idempotencyKey: String,
            logicalRequestDigest: String,
            commandCode: WorkflowCommandCode,
            domainCommandDigest: String,
            resultVersion: Long,
            effectCount: Int,
            domainResultCode: WorkflowResultCode,
            committedAt: Long,
        ): WorkflowRuntimeIdempotencyRecord = WorkflowRuntimeIdempotencyRecord(
            tenantId,
            instanceId,
            idempotencyKey,
            logicalRequestDigest,
            commandCode,
            domainCommandDigest,
            resultVersion,
            effectCount,
            domainResultCode,
            committedAt,
        )
    }
}

/** One consistent read of instance and its idempotency key. */
class WorkflowRuntimeCommandSnapshot private constructor(
    tenantId: String,
    instanceId: String,
    idempotencyKey: String,
    val state: WorkflowInstanceState?,
    val idempotency: WorkflowRuntimeIdempotencyRecord?,
    readAt: Long,
) {
    val tenantId: String = runtimeId(tenantId, "snapshot tenant")
    val instanceId: String = runtimeId(instanceId, "snapshot instance")
    val idempotencyKey: String = runtimeId(idempotencyKey, "snapshot idempotency key")
    val readAt: Long = WorkflowRuntimeSupport.nonNegative(readAt, "Workflow snapshot time is invalid.")

    init {
        require(state == null || state.tenantId == this.tenantId && state.instanceId == this.instanceId) {
            "Workflow snapshot state binding is invalid."
        }
        require(idempotency == null ||
            idempotency.tenantId == this.tenantId &&
                idempotency.instanceId == this.instanceId &&
                idempotency.idempotencyKey == this.idempotencyKey && state != null
        ) { "Workflow snapshot idempotency binding is invalid." }
    }

    override fun toString(): String = "WorkflowRuntimeCommandSnapshot(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            instanceId: String,
            idempotencyKey: String,
            state: WorkflowInstanceState?,
            idempotency: WorkflowRuntimeIdempotencyRecord?,
            readAt: Long,
        ): WorkflowRuntimeCommandSnapshot = WorkflowRuntimeCommandSnapshot(
            tenantId,
            instanceId,
            idempotencyKey,
            state,
            idempotency,
            readAt,
        )
    }
}

class WorkflowEffectAcknowledgementKind private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow effect acknowledgement kind is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowEffectAcknowledgementKind && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowEffectAcknowledgementKind(<redacted>)"

    companion object {
        @JvmField val PARTICIPANT_RESOLUTION = WorkflowEffectAcknowledgementKind("participant-resolution")
        @JvmField val EXTERNAL_EFFECT = WorkflowEffectAcknowledgementKind("external-effect")
        @JvmField val CONTINUATION = WorkflowEffectAcknowledgementKind("continuation")
    }
}

/** Effect completion that must be consumed in the same commit as the new aggregate state. */
class WorkflowRuntimeEffectAcknowledgement private constructor(
    val kind: WorkflowEffectAcknowledgementKind,
    effectId: String,
    requestDigest: String,
    receiptDigest: String,
    leaseId: String?,
    fencingToken: Long?,
) {
    val effectId: String = runtimeId(effectId, "acknowledged effect")
    val requestDigest: String = runtimeSha(requestDigest, "acknowledged effect request")
    val receiptDigest: String = runtimeSha(receiptDigest, "acknowledged effect receipt")
    val leaseId: String? = leaseId?.let { value -> runtimeId(value, "acknowledgement job lease") }
    val fencingToken: Long? = fencingToken?.let { value ->
        WorkflowRuntimeSupport.nonNegative(value, "Workflow acknowledgement fencing token is invalid.")
    }

    init {
        require((this.leaseId == null) == (this.fencingToken == null) &&
            (this.fencingToken == null || this.fencingToken > 0L)
        ) { "Workflow acknowledgement lease evidence is incomplete." }
    }

    override fun toString(): String = "WorkflowRuntimeEffectAcknowledgement(<redacted>)"

    companion object {
        @JvmStatic fun of(
            kind: WorkflowEffectAcknowledgementKind,
            effectId: String,
            requestDigest: String,
            receiptDigest: String,
        ): WorkflowRuntimeEffectAcknowledgement = WorkflowRuntimeEffectAcknowledgement(
            kind,
            effectId,
            requestDigest,
            receiptDigest,
            null,
            null,
        )

        @JvmStatic fun fenced(
            kind: WorkflowEffectAcknowledgementKind,
            effectId: String,
            requestDigest: String,
            receiptDigest: String,
            leaseId: String,
            fencingToken: Long,
        ): WorkflowRuntimeEffectAcknowledgement = WorkflowRuntimeEffectAcknowledgement(
            kind,
            effectId,
            requestDigest,
            receiptDigest,
            leaseId,
            fencingToken,
        )
    }
}

/**
 * Atomic persistence unit. An implementation commits the state, events, new effects,
 * idempotency record and optional consumed-effect acknowledgement in one transaction/CAS.
 */
class WorkflowRuntimeAtomicCommit private constructor(
    tenantId: String,
    instanceId: String,
    expectedInstanceVersion: Long,
    expectedStateDigest: String?,
    val state: WorkflowInstanceState,
    events: Collection<WorkflowDomainEvent>,
    effects: Collection<WorkflowEffectIntent>,
    val idempotency: WorkflowRuntimeIdempotencyRecord,
    val effectAcknowledgement: WorkflowRuntimeEffectAcknowledgement?,
    val domainResultCode: WorkflowResultCode,
    committedAt: Long,
) {
    val tenantId: String = runtimeId(tenantId, "commit tenant")
    val instanceId: String = runtimeId(instanceId, "commit instance")
    val expectedInstanceVersion: Long = WorkflowRuntimeSupport.nonNegative(
        expectedInstanceVersion,
        "Workflow commit expected version is invalid.",
    )
    val expectedStateDigest: String? = expectedStateDigest?.let { value ->
        runtimeSha(value, "expected state")
    }
    val events: List<WorkflowDomainEvent> = WorkflowRuntimeSupport.immutable(
        events,
        WorkflowRuntimeSupport.MAX_ITEMS,
        "Workflow commit events are invalid.",
    )
    val effects: List<WorkflowEffectIntent> = WorkflowRuntimeSupport.immutable(
        effects,
        WorkflowRuntimeSupport.MAX_ITEMS,
        "Workflow commit effects are invalid.",
    )
    val committedAt: Long = WorkflowRuntimeSupport.nonNegative(
        committedAt,
        "Workflow commit time is invalid.",
    )

    init {
        require((this.expectedInstanceVersion == 0L) == (this.expectedStateDigest == null)) {
            "Workflow commit CAS binding is incomplete."
        }
        require(state.tenantId == this.tenantId && state.instanceId == this.instanceId &&
            state.version == this.expectedInstanceVersion + 1L
        ) { "Workflow commit state binding is invalid." }
        require(idempotency.tenantId == this.tenantId && idempotency.instanceId == this.instanceId &&
            idempotency.resultVersion == state.version && idempotency.effectCount == this.effects.size
        ) { "Workflow commit idempotency binding is invalid." }
        require(this.events.all { event ->
            event.tenantId == this.tenantId && event.instanceId == this.instanceId &&
                event.definitionId == state.definitionId && event.definitionRef == state.definitionRef &&
                event.subject == state.subject && event.instanceVersion == state.version &&
                event.occurredAt <= this.committedAt
        }) { "Workflow commit event binding is invalid." }
        require(this.effects.all { effect ->
            effect.tenantId == this.tenantId && effect.instanceId == this.instanceId &&
                effect.definitionId == state.definitionId && effect.definitionRef == state.definitionRef &&
                effect.subject == state.subject && effect.createdAt <= this.committedAt
        }) { "Workflow commit effect binding is invalid." }
        require(this.committedAt >= state.updatedAt) { "Workflow commit cannot predate aggregate state." }
        require(domainResultCode == WorkflowResultCode.APPLIED ||
            domainResultCode == WorkflowResultCode.BUDGET_EXHAUSTED ||
            domainResultCode == WorkflowResultCode.INCIDENT
        ) { "Only durable domain results can be committed." }
        val expectedAcknowledgementKind = when (idempotency.commandCode) {
            WorkflowCommandCode.ACTIVATE_HUMAN_RULE -> WorkflowEffectAcknowledgementKind.PARTICIPANT_RESOLUTION
            WorkflowCommandCode.COMPLETE_EFFECT -> WorkflowEffectAcknowledgementKind.EXTERNAL_EFFECT
            WorkflowCommandCode.CONTINUE_EXECUTION -> WorkflowEffectAcknowledgementKind.CONTINUATION
            else -> null
        }
        require(
            expectedAcknowledgementKind == null && effectAcknowledgement == null ||
                effectAcknowledgement?.kind == expectedAcknowledgementKind,
        ) { "Workflow command and effect acknowledgement kind do not match." }
        if (expectedAcknowledgementKind != null) {
            require(this.events.any { event -> event.code == WorkflowEventCode.EFFECT_COMPLETED }) {
                "Effect acknowledgement commits require a domain completion event."
            }
        }
    }

    override fun toString(): String = "WorkflowRuntimeAtomicCommit(<redacted>)"

    companion object {
        @JvmStatic fun fromDomain(
            domainResult: WorkflowDomainResult,
            logicalRequestDigest: String,
            expectedInstanceVersion: Long,
            expectedStateDigest: String?,
            effectAcknowledgement: WorkflowRuntimeEffectAcknowledgement?,
            committedAt: Long,
        ): WorkflowRuntimeAtomicCommit {
            val state = requireNotNull(domainResult.state) { "Durable domain result state is missing." }
            val idempotency = WorkflowRuntimeIdempotencyRecord.of(
                state.tenantId,
                state.instanceId,
                domainResult.idempotencyKey,
                logicalRequestDigest,
                domainResult.commandCode,
                domainResult.commandDigest,
                state.version,
                domainResult.effects.size,
                domainResult.code,
                committedAt,
            )
            return WorkflowRuntimeAtomicCommit(
                state.tenantId,
                state.instanceId,
                expectedInstanceVersion,
                expectedStateDigest,
                state,
                domainResult.events,
                domainResult.effects,
                idempotency,
                effectAcknowledgement,
                domainResult.code,
                committedAt,
            )
        }
    }
}

class WorkflowRuntimeCommitResult private constructor(
    val code: WorkflowRuntimeCommitCode,
    currentVersion: Long?,
) {
    val currentVersion: Long? = currentVersion?.let { value ->
        WorkflowRuntimeSupport.nonNegative(value, "Workflow commit current version is invalid.")
    }

    init {
        require((code == WorkflowRuntimeCommitCode.COMMITTED) == (this.currentVersion != null)) {
            "Workflow commit result binding is invalid."
        }
    }

    override fun toString(): String = "WorkflowRuntimeCommitResult(<redacted>)"

    companion object {
        @JvmStatic fun committed(version: Long): WorkflowRuntimeCommitResult =
            WorkflowRuntimeCommitResult(WorkflowRuntimeCommitCode.COMMITTED, version)

        @JvmStatic fun conflict(code: WorkflowRuntimeCommitCode): WorkflowRuntimeCommitResult {
            require(code != WorkflowRuntimeCommitCode.COMMITTED) { "Committed is not a conflict code." }
            return WorkflowRuntimeCommitResult(code, null)
        }
    }
}

/**
 * Neutral durable store port. `commit` is one atomic database operation and must never call an
 * authorization, provider, queue or dispatcher. Effect mutation methods are independent CAS
 * transactions; workers call providers only between checkpoint and outcome operations.
 */
interface WorkflowRuntimePersistencePort {
    fun loadDefinition(
        tenantId: String,
        definitionId: String,
        definitionRef: WorkflowDefinitionRef,
    ): WorkflowRuntimeDefinitionRecord?

    fun loadCommandSnapshot(
        tenantId: String,
        instanceId: String,
        idempotencyKey: String,
        readAt: Long,
    ): WorkflowRuntimeCommandSnapshot

    fun commit(request: WorkflowRuntimeAtomicCommit): WorkflowRuntimeCommitResult

    fun loadEffect(tenantId: String, effectId: String, readAt: Long): WorkflowEffectRecord?

    fun claimEffect(request: WorkflowEffectClaim): WorkflowEffectOperationResult

    fun checkpointEffect(request: WorkflowEffectCheckpoint): WorkflowEffectOperationResult

    fun recordEffectOutcome(request: WorkflowEffectOutcome): WorkflowEffectOperationResult

    fun scheduleEffectRetry(request: WorkflowEffectRetry): WorkflowEffectOperationResult

    fun raiseEffectReconciliationIncident(
        request: WorkflowEffectReconciliationIncident,
    ): WorkflowEffectOperationResult
}

private fun runtimeId(value: String, label: String): String = WorkflowRuntimeSupport.text(
    value,
    WorkflowRuntimeSupport.MAX_ID_BYTES,
    "Workflow $label id is invalid.",
)

private fun runtimeSha(value: String, label: String): String = WorkflowRuntimeSupport.sha256(
    value,
    "Workflow $label digest is invalid.",
)
