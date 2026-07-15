package ai.icen.fw.workflow.api

/**
 * Exact, time-bounded input to one organization participant resolution.
 *
 * [tenantId], actors and every workflow reference must be derived or revalidated by the trusted
 * runtime. [organizationAuthority] and [organizationSnapshotRevision] pin the host-owned directory
 * snapshot; a resolver must fail instead of silently using another revision. [stage] is part of
 * [requestDigest], preventing a result produced for activation from being replayed at claim or
 * decision re-resolution. [maximumPrincipals] bounds flattened principal occurrences rather than
 * distinct identities, preserving independent tier and selector facts.
 */
class WorkflowParticipantResolutionRequest private constructor(
    requestId: String,
    tenantId: String,
    val definition: WorkflowDefinitionRef,
    val instance: WorkflowInstanceRef,
    val workItem: WorkflowWorkItemRef,
    val stage: WorkflowParticipantResolutionStage,
    val subject: WorkflowSubjectSnapshot,
    val initiator: WorkflowPrincipalRef,
    val currentActor: WorkflowPrincipalRef,
    organizationAuthority: String,
    organizationSnapshotRevision: String,
    selectors: List<WorkflowParticipantSelector>,
    val delegationPolicy: WorkflowDelegationPolicy,
    val maximumPrincipals: Int,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val requestId: String = WorkflowContractSupport.requireText(
        requestId,
        WorkflowContractSupport.MAX_REFERENCE_ID_UTF8_BYTES,
        "Workflow participant resolution request identifier is invalid.",
    )
    val tenantId: String = WorkflowContractSupport.requireText(
        tenantId,
        WorkflowContractSupport.MAX_REFERENCE_ID_UTF8_BYTES,
        "Workflow participant resolution tenant identifier is invalid.",
    )
    val organizationAuthority: String = WorkflowContractSupport.requireMachineCode(
        organizationAuthority,
        "Workflow participant organization authority is invalid.",
    )
    val organizationSnapshotRevision: String = WorkflowContractSupport.requireText(
        organizationSnapshotRevision,
        WorkflowContractSupport.MAX_ORGANIZATION_REVISION_UTF8_BYTES,
        "Workflow participant organization snapshot revision is invalid.",
    )
    val selectors: List<WorkflowParticipantSelector> = WorkflowContractSupport.immutableList(
        selectors,
        WorkflowContractSupport.MAX_SELECTORS,
        "Workflow participant selectors are invalid or exceed the limit.",
    )
    val requestDigest: String

    init {
        require(this.selectors.isNotEmpty()) { "Workflow participant resolution requires selectors." }
        require(this.selectors.map { selector -> selector.digest }.toSet().size == this.selectors.size) {
            "Workflow participant resolution selectors must be unique."
        }
        require(maximumPrincipals in 1..WorkflowContractSupport.MAX_PRINCIPALS) {
            "Workflow participant resolution principal limit is invalid."
        }
        require(requestedAtEpochMilli >= 0L && deadlineEpochMilli > requestedAtEpochMilli) {
            "Workflow participant resolution deadline must follow its request time."
        }
        require(deadlineEpochMilli - requestedAtEpochMilli <= WorkflowContractSupport.MAX_RESOLUTION_WINDOW_MILLIS) {
            "Workflow participant resolution window exceeds the limit."
        }

        val writer = WorkflowContractSupport.digest(WorkflowContractSupport.REQUEST_DIGEST_DOMAIN)
            .text(this.requestId)
            .text(this.tenantId)
            .text(definition.key)
            .text(definition.version)
            .text(definition.digest)
            .text(instance.id)
            .longValue(instance.expectedVersion)
            .text(workItem.id)
            .longValue(workItem.expectedVersion)
            .text(stage.code)
            .text(subject.ref.type)
            .text(subject.ref.id)
            .text(subject.revision)
            .text(subject.digest)
            .text(initiator.type)
            .text(initiator.id)
            .text(currentActor.type)
            .text(currentActor.id)
            .text(this.organizationAuthority)
            .text(this.organizationSnapshotRevision)
            .integer(this.selectors.size)
        this.selectors.forEach { selector -> writer.text(selector.digest) }
        writer.text(delegationPolicy.mode.code)
            .integer(delegationPolicy.maximumHops)
            .integer(maximumPrincipals)
            .longValue(requestedAtEpochMilli)
            .longValue(deadlineEpochMilli)
        requestDigest = writer.finish()
    }

    override fun toString(): String = "WorkflowParticipantResolutionRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            requestId: String,
            tenantId: String,
            definition: WorkflowDefinitionRef,
            instance: WorkflowInstanceRef,
            workItem: WorkflowWorkItemRef,
            stage: WorkflowParticipantResolutionStage,
            subject: WorkflowSubjectSnapshot,
            initiator: WorkflowPrincipalRef,
            currentActor: WorkflowPrincipalRef,
            organizationAuthority: String,
            organizationSnapshotRevision: String,
            selectors: List<WorkflowParticipantSelector>,
            delegationPolicy: WorkflowDelegationPolicy,
            maximumPrincipals: Int,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): WorkflowParticipantResolutionRequest = WorkflowParticipantResolutionRequest(
            requestId,
            tenantId,
            definition,
            instance,
            workItem,
            stage,
            subject,
            initiator,
            currentActor,
            organizationAuthority,
            organizationSnapshotRevision,
            selectors,
            delegationPolicy,
            maximumPrincipals,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}
