package ai.icen.fw.workflow.runtime

import java.security.MessageDigest

/** Durable operational status of one workflow incident. */
class WorkflowIncidentStatus private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow incident status is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowIncidentStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowIncidentStatus(<redacted>)"

    companion object {
        @JvmField val OPEN = WorkflowIncidentStatus("open")
        @JvmField val RESOLVED = WorkflowIncidentStatus("resolved")

        @JvmStatic
        fun of(code: String): WorkflowIncidentStatus = when (code) {
            OPEN.code -> OPEN
            RESOLVED.code -> RESOLVED
            else -> WorkflowIncidentStatus(code)
        }
    }
}

/**
 * Tenant-bound incident projection. It exposes digests and lifecycle evidence only; provider
 * payloads remain in the encrypted/opaque effect-result store.
 */
class WorkflowEffectIncidentSnapshot private constructor(
    incidentId: String,
    tenantId: String,
    instanceId: String,
    incidentCode: String,
    val status: WorkflowIncidentStatus,
    evidenceDigest: String,
    repairDigest: String?,
    occurredAt: Long,
    resolvedAt: Long?,
    val effect: WorkflowEffectRecord,
) {
    val incidentId: String = incidentId(incidentId, "incident")
    val tenantId: String = incidentId(tenantId, "tenant")
    val instanceId: String = incidentId(instanceId, "instance")
    val incidentCode: String = WorkflowRuntimeSupport.code(incidentCode, "Workflow incident code is invalid.")
    val evidenceDigest: String = incidentSha(evidenceDigest, "evidence")
    val repairDigest: String? = repairDigest?.let { value -> incidentSha(value, "repair") }
    val occurredAt: Long = incidentTime(occurredAt, "occurrence")
    val resolvedAt: Long? = resolvedAt?.let { value -> incidentTime(value, "resolution") }
    val snapshotDigest: String

    init {
        require(effect.intent.tenantId == this.tenantId && effect.intent.instanceId == this.instanceId) {
            "Workflow incident and effect bindings are inconsistent."
        }
        when (status) {
            WorkflowIncidentStatus.OPEN -> require(
                this.repairDigest == null && this.resolvedAt == null &&
                    effect.status == WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT,
            ) { "Open workflow incidents require an unresolved reconciliation effect." }

            WorkflowIncidentStatus.RESOLVED -> require(
                this.repairDigest != null && this.resolvedAt != null && this.resolvedAt >= this.occurredAt,
            ) { "Resolved workflow incidents require repair evidence and a resolution time." }

            else -> throw IllegalArgumentException("Unknown workflow incident status is unsupported.")
        }
        snapshotDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-incident-snapshot-v1")
            .text(this.incidentId)
            .text(this.tenantId)
            .text(this.instanceId)
            .text(this.incidentCode)
            .text(status.code)
            .text(this.evidenceDigest)
            .optional(this.repairDigest)
            .longValue(this.occurredAt)
            .longValue(this.resolvedAt ?: -1L)
            .text(effect.intent.requestDigest)
            .longValue(effect.version)
            .text(effect.status.code)
            .optional(effect.outcomeDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowEffectIncidentSnapshot(<redacted>)"

    companion object {
        @JvmStatic
        fun restore(
            incidentId: String,
            tenantId: String,
            instanceId: String,
            incidentCode: String,
            status: WorkflowIncidentStatus,
            evidenceDigest: String,
            repairDigest: String?,
            occurredAt: Long,
            resolvedAt: Long?,
            effect: WorkflowEffectRecord,
        ): WorkflowEffectIncidentSnapshot = WorkflowEffectIncidentSnapshot(
            incidentId,
            tenantId,
            instanceId,
            incidentCode,
            status,
            evidenceDigest,
            repairDigest,
            occurredAt,
            resolvedAt,
            effect,
        )
    }
}

/** Fresh, authorized repair of an outcome-unknown effect. */
class WorkflowEffectIncidentResolution private constructor(
    tenantId: String,
    incidentId: String,
    effectId: String,
    expectedEffectVersion: Long,
    requestDigest: String,
    val authorization: WorkflowRuntimeAuthorizationDecision,
    val result: WorkflowEffectJobStoredResult,
    repairDigest: String,
    resolvedAt: Long,
) {
    val tenantId: String = incidentId(tenantId, "tenant")
    val incidentId: String = incidentId(incidentId, "incident")
    val effectId: String = incidentId(effectId, "effect")
    val expectedEffectVersion: Long = WorkflowRuntimeSupport.nonNegative(
        expectedEffectVersion,
        "Workflow incident expected effect version is invalid.",
    )
    val requestDigest: String = incidentSha(requestDigest, "request")
    val resultPayloadDigest: String = sha256(result.bytes())
    val repairDigest: String = incidentSha(repairDigest, "repair")
    val resolvedAt: Long = incidentTime(resolvedAt, "resolution")
    val resolutionDigest: String

    init {
        require(result.outcome == WorkflowEffectObservedOutcome.SUCCEEDED ||
            result.outcome == WorkflowEffectObservedOutcome.RETRYABLE_FAILURE ||
            result.outcome == WorkflowEffectObservedOutcome.TERMINAL_FAILURE
        ) { "Workflow incident resolution requires a known provider outcome." }
        require(result.completedAt <= this.resolvedAt &&
            (result.outcome != WorkflowEffectObservedOutcome.RETRYABLE_FAILURE ||
                requireNotNull(result.retryAt) > this.resolvedAt)
        ) { "Workflow incident resolution result time is invalid." }
        require(authorization.status == WorkflowRuntimeAuthorizationStatus.AUTHORIZED &&
            authorization.action == WorkflowRuntimeAction.RESOLVE_EFFECT_INCIDENT &&
            authorization.tenantId == this.tenantId &&
            authorization.requestDigest == this.requestDigest
        ) { "Workflow incident resolution authorization is invalid." }
        resolutionDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-incident-resolution-v1")
            .text(this.tenantId)
            .text(this.incidentId)
            .text(this.effectId)
            .longValue(this.expectedEffectVersion)
            .text(this.requestDigest)
            .text(result.outcome.code)
            .text(result.resultType)
            .text(result.resultDigest)
            .text(this.resultPayloadDigest)
            .longValue(result.retryAt ?: -1L)
            .longValue(result.completedAt)
            .text(this.repairDigest)
            .longValue(this.resolvedAt)
            .text(authorization.authorityDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowEffectIncidentResolution(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            incidentId: String,
            effectId: String,
            expectedEffectVersion: Long,
            requestDigest: String,
            authorization: WorkflowRuntimeAuthorizationDecision,
            result: WorkflowEffectJobStoredResult,
            repairDigest: String,
            resolvedAt: Long,
        ): WorkflowEffectIncidentResolution = WorkflowEffectIncidentResolution(
            tenantId,
            incidentId,
            effectId,
            expectedEffectVersion,
            requestDigest,
            authorization,
            result,
            repairDigest,
            resolvedAt,
        )
    }
}

class WorkflowIncidentOperationCode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow incident operation code is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowIncidentOperationCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowIncidentOperationCode(<redacted>)"

    companion object {
        @JvmField val RESOLVED = WorkflowIncidentOperationCode("resolved")
        @JvmField val REPLAYED = WorkflowIncidentOperationCode("replayed")
        @JvmField val AUTHORIZATION_DENIED = WorkflowIncidentOperationCode("authorization-denied")
        @JvmField val NOT_FOUND = WorkflowIncidentOperationCode("not-found")
        @JvmField val VERSION_CONFLICT = WorkflowIncidentOperationCode("version-conflict")
        @JvmField val NOT_ELIGIBLE = WorkflowIncidentOperationCode("not-eligible")
        @JvmField val STORE_OUTCOME_UNKNOWN = WorkflowIncidentOperationCode("store-outcome-unknown")
    }
}

class WorkflowIncidentOperationResult private constructor(
    val code: WorkflowIncidentOperationCode,
    val incident: WorkflowEffectIncidentSnapshot?,
) {
    init {
        require((code == WorkflowIncidentOperationCode.RESOLVED ||
            code == WorkflowIncidentOperationCode.REPLAYED) == (incident != null)
        ) { "Workflow incident operation result binding is invalid." }
    }

    override fun toString(): String = "WorkflowIncidentOperationResult(<redacted>)"

    companion object {
        @JvmStatic
        fun resolved(incident: WorkflowEffectIncidentSnapshot): WorkflowIncidentOperationResult =
            WorkflowIncidentOperationResult(WorkflowIncidentOperationCode.RESOLVED, incident)

        @JvmStatic
        fun replayed(incident: WorkflowEffectIncidentSnapshot): WorkflowIncidentOperationResult =
            WorkflowIncidentOperationResult(WorkflowIncidentOperationCode.REPLAYED, incident)

        @JvmStatic
        fun failed(code: WorkflowIncidentOperationCode): WorkflowIncidentOperationResult {
            require(code != WorkflowIncidentOperationCode.RESOLVED && code != WorkflowIncidentOperationCode.REPLAYED) {
                "Successful workflow incident codes require a snapshot."
            }
            return WorkflowIncidentOperationResult(code, null)
        }
    }
}

/** Short local transactions only; implementations never call authorization or providers. */
interface WorkflowIncidentPersistencePort {
    fun loadEffectIncident(
        tenantId: String,
        incidentId: String,
        readAt: Long,
    ): WorkflowEffectIncidentSnapshot?

    fun resolveEffectIncident(request: WorkflowEffectIncidentResolution): WorkflowIncidentOperationResult
}

private fun incidentId(value: String, label: String): String = WorkflowRuntimeSupport.text(
    value,
    WorkflowRuntimeSupport.MAX_ID_BYTES,
    "Workflow $label id is invalid.",
)

private fun incidentSha(value: String, label: String): String = WorkflowRuntimeSupport.sha256(
    value,
    "Workflow incident $label digest is invalid.",
)

private fun incidentTime(value: Long, label: String): Long = WorkflowRuntimeSupport.nonNegative(
    value,
    "Workflow incident $label time is invalid.",
)

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
