package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowApprovalMode
import ai.icen.fw.workflow.api.WorkflowPrincipalRef

/**
 * Immutable activation denominator for one ordered human-task rule.
 * Later organization changes never rewrite this snapshot. Current decision authorization is still
 * checked by a separate exact receipt.
 */
class WorkflowHumanRuleSnapshot private constructor(
    val ruleIndex: Int,
    ruleDigest: String,
    selectorDigest: String,
    val approvalMode: WorkflowApprovalMode,
    val denominator: Int,
    val requiredApprovals: Int,
    candidates: Collection<WorkflowPrincipalRef>,
    resolutionDigest: String,
    activationReceiptDigest: String,
    organizationAuthority: String?,
    organizationSnapshotRevision: String?,
    resolutionRequestDigest: String?,
    organizationProviderRevision: String?,
    organizationSnapshotDigest: String?,
    organizationSnapshotReceiptDigest: String?,
    organizationConfirmationRevision: String?,
    organizationConfirmationSnapshotDigest: String?,
    organizationConfirmationRequestDigest: String?,
    organizationConfirmationReceiptDigest: String?,
    activatedAt: Long,
) {
    val ruleDigest: String = sha(ruleDigest, "rule")
    val selectorDigest: String = sha(selectorDigest, "selector")
    val candidates: List<WorkflowPrincipalRef> = WorkflowDomainSupport.immutableList(
        candidates,
        WorkflowDomainSupport.MAX_CANDIDATES,
        "Workflow human-rule candidates are invalid or exceed the limit.",
    )
    val resolutionDigest: String = sha(resolutionDigest, "resolution")
    val activationReceiptDigest: String = sha(activationReceiptDigest, "activation receipt")
    val organizationAuthority: String? = organizationAuthority?.let { value ->
        WorkflowDomainSupport.requireCode(value, "Workflow organization authority is invalid.")
    }
    val organizationSnapshotRevision: String? = organizationSnapshotRevision?.let { value ->
        WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_REVISION_UTF8_BYTES,
            "Workflow organization snapshot revision is invalid.",
        )
    }
    val resolutionRequestDigest: String? = resolutionRequestDigest?.let { value ->
        sha(value, "participant resolution request")
    }
    val organizationProviderRevision: String? = organizationProviderRevision?.let { value ->
        WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_REVISION_UTF8_BYTES,
            "Workflow organization provider revision is invalid.",
        )
    }
    val organizationSnapshotDigest: String? = organizationSnapshotDigest?.let { value ->
        sha(value, "organization snapshot")
    }
    val organizationSnapshotReceiptDigest: String? = organizationSnapshotReceiptDigest?.let { value ->
        sha(value, "organization snapshot receipt")
    }
    val organizationConfirmationRevision: String? = organizationConfirmationRevision?.let { value ->
        WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_REVISION_UTF8_BYTES,
            "Workflow organization confirmation revision is invalid.",
        )
    }
    val organizationConfirmationSnapshotDigest: String? = organizationConfirmationSnapshotDigest?.let { value ->
        sha(value, "organization confirmation snapshot")
    }
    val organizationConfirmationRequestDigest: String? = organizationConfirmationRequestDigest?.let { value ->
        sha(value, "organization confirmation request")
    }
    val organizationConfirmationReceiptDigest: String? = organizationConfirmationReceiptDigest?.let { value ->
        sha(value, "organization confirmation receipt")
    }
    val evidenceVersion: Int
    val hasOrganizationEvidence: Boolean
    val activatedAt: Long = WorkflowDomainSupport.requireTime(activatedAt, "Workflow rule activation time is invalid.")
    val activationDigest: String

    init {
        require(ruleIndex >= 0 && denominator == this.candidates.size && denominator > 0) {
            "Workflow human-rule denominator is invalid."
        }
        require(this.candidates.toSet().size == this.candidates.size) {
            "Workflow human-rule candidates must be unique."
        }
        when (approvalMode) {
            WorkflowApprovalMode.ONE -> require(requiredApprovals == 1) {
                "ONE workflow approval requires exactly one approval."
            }

            WorkflowApprovalMode.ALL -> require(requiredApprovals == denominator) {
                "ALL workflow approval requires the fixed denominator."
            }

            WorkflowApprovalMode.QUORUM -> require(requiredApprovals in 1..denominator) {
                "Workflow quorum exceeds its activation denominator."
            }

            else -> throw IllegalArgumentException("Unknown workflow approval modes are unsupported.")
        }
        val organizationEvidence = listOf(
            this.organizationAuthority,
            this.organizationSnapshotRevision,
            this.resolutionRequestDigest,
        )
        require(organizationEvidence.all { value -> value == null } ||
            organizationEvidence.all { value -> value != null }
        ) {
            "Workflow human-rule organization evidence must be complete."
        }
        val confirmationEvidence = listOf(
            this.organizationProviderRevision,
            this.organizationSnapshotDigest,
            this.organizationSnapshotReceiptDigest,
            this.organizationConfirmationRevision,
            this.organizationConfirmationSnapshotDigest,
            this.organizationConfirmationRequestDigest,
            this.organizationConfirmationReceiptDigest,
        )
        require(confirmationEvidence.all { value -> value == null } ||
            confirmationEvidence.all { value -> value != null }
        ) {
            "Workflow human-rule organization confirmation evidence must be complete."
        }
        require(confirmationEvidence.first() == null || organizationEvidence.first() != null) {
            "Workflow organization confirmation evidence requires organization evidence."
        }
        require(confirmationEvidence.first() == null ||
            this.organizationSnapshotRevision == this.organizationConfirmationRevision
        ) { "Workflow organization revision changed during participant resolution." }
        hasOrganizationEvidence = organizationEvidence.first() != null
        evidenceVersion = when {
            confirmationEvidence.first() != null -> 3
            hasOrganizationEvidence -> 2
            else -> 1
        }
        val writer = WorkflowDomainSupport.digest(
            when (evidenceVersion) {
                3 -> "flowweft-workflow-domain-human-rule-snapshot-v3"
                2 -> "flowweft-workflow-domain-human-rule-snapshot-v2"
                else -> "flowweft-workflow-domain-human-rule-snapshot-v1"
            },
        )
            .integer(ruleIndex)
            .text(this.ruleDigest)
            .text(this.selectorDigest)
            .text(approvalMode.code)
            .integer(denominator)
            .integer(requiredApprovals)
            .integer(this.candidates.size)
        this.candidates.forEach { candidate -> writer.text(candidate.type).text(candidate.id) }
        writer.text(this.resolutionDigest)
            .text(this.activationReceiptDigest)
        if (hasOrganizationEvidence) {
            writer.text(requireNotNull(this.organizationAuthority))
                .text(requireNotNull(this.organizationSnapshotRevision))
                .text(requireNotNull(this.resolutionRequestDigest))
        }
        if (evidenceVersion >= 3) {
            writer.text(requireNotNull(this.organizationProviderRevision))
                .text(requireNotNull(this.organizationSnapshotDigest))
                .text(requireNotNull(this.organizationSnapshotReceiptDigest))
                .text(requireNotNull(this.organizationConfirmationRevision))
                .text(requireNotNull(this.organizationConfirmationSnapshotDigest))
                .text(requireNotNull(this.organizationConfirmationRequestDigest))
                .text(requireNotNull(this.organizationConfirmationReceiptDigest))
        }
        activationDigest = writer
            .longValue(this.activatedAt)
            .finish()
    }

    override fun toString(): String = "WorkflowHumanRuleSnapshot(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            ruleIndex: Int,
            ruleDigest: String,
            selectorDigest: String,
            approvalMode: WorkflowApprovalMode,
            denominator: Int,
            requiredApprovals: Int,
            candidates: Collection<WorkflowPrincipalRef>,
            resolutionDigest: String,
            activationReceiptDigest: String,
            activatedAt: Long,
        ): WorkflowHumanRuleSnapshot = WorkflowHumanRuleSnapshot(
            ruleIndex,
            ruleDigest,
            selectorDigest,
            approvalMode,
            denominator,
            requiredApprovals,
            candidates,
            resolutionDigest,
            activationReceiptDigest,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            activatedAt,
        )

        @JvmStatic
        fun organizationBound(
            ruleIndex: Int,
            ruleDigest: String,
            selectorDigest: String,
            approvalMode: WorkflowApprovalMode,
            denominator: Int,
            requiredApprovals: Int,
            candidates: Collection<WorkflowPrincipalRef>,
            resolutionDigest: String,
            activationReceiptDigest: String,
            organizationAuthority: String,
            organizationSnapshotRevision: String,
            resolutionRequestDigest: String,
            activatedAt: Long,
        ): WorkflowHumanRuleSnapshot = WorkflowHumanRuleSnapshot(
            ruleIndex,
            ruleDigest,
            selectorDigest,
            approvalMode,
            denominator,
            requiredApprovals,
            candidates,
            resolutionDigest,
            activationReceiptDigest,
            organizationAuthority,
            organizationSnapshotRevision,
            resolutionRequestDigest,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            activatedAt,
        )

        @JvmStatic
        fun organizationDoubleChecked(
            ruleIndex: Int,
            ruleDigest: String,
            selectorDigest: String,
            approvalMode: WorkflowApprovalMode,
            denominator: Int,
            requiredApprovals: Int,
            candidates: Collection<WorkflowPrincipalRef>,
            resolutionDigest: String,
            activationReceiptDigest: String,
            organizationAuthority: String,
            organizationSnapshotRevision: String,
            resolutionRequestDigest: String,
            organizationProviderRevision: String,
            organizationSnapshotDigest: String,
            organizationSnapshotReceiptDigest: String,
            organizationConfirmationRevision: String,
            organizationConfirmationSnapshotDigest: String,
            organizationConfirmationRequestDigest: String,
            organizationConfirmationReceiptDigest: String,
            activatedAt: Long,
        ): WorkflowHumanRuleSnapshot = WorkflowHumanRuleSnapshot(
            ruleIndex,
            ruleDigest,
            selectorDigest,
            approvalMode,
            denominator,
            requiredApprovals,
            candidates,
            resolutionDigest,
            activationReceiptDigest,
            organizationAuthority,
            organizationSnapshotRevision,
            resolutionRequestDigest,
            organizationProviderRevision,
            organizationSnapshotDigest,
            organizationSnapshotReceiptDigest,
            organizationConfirmationRevision,
            organizationConfirmationSnapshotDigest,
            organizationConfirmationRequestDigest,
            organizationConfirmationReceiptDigest,
            activatedAt,
        )

        private fun sha(value: String, label: String): String = WorkflowDomainSupport.requireSha256(
            value,
            "Workflow $label digest is invalid.",
        )
    }
}
