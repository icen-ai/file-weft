package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot

/** Trusted, bounded participant-resolution result for one exact human-task rule activation. */
class WorkflowParticipantActivationReceipt private constructor(
    receiptId: String,
    effectId: String,
    tenantId: String,
    instanceId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    val subject: WorkflowSubjectSnapshot,
    tokenId: String,
    nodeExecutionId: String,
    workItemId: String,
    nodeId: String,
    val ruleIndex: Int,
    ruleDigest: String,
    effectRequestDigest: String,
    candidates: Collection<WorkflowPrincipalRef>,
    resolutionDigest: String,
    organizationAuthority: String?,
    organizationSnapshotRevision: String?,
    resolutionRequestDigest: String?,
    selectorDigest: String?,
    organizationProviderRevision: String?,
    organizationSnapshotDigest: String?,
    organizationSnapshotReceiptDigest: String?,
    organizationConfirmationRevision: String?,
    organizationConfirmationSnapshotDigest: String?,
    organizationConfirmationRequestDigest: String?,
    organizationConfirmationReceiptDigest: String?,
    resolvedAt: Long,
    validUntil: Long,
) {
    val receiptId: String = text(receiptId, "receipt")
    val effectId: String = text(effectId, "effect")
    val tenantId: String = text(tenantId, "tenant")
    val instanceId: String = text(instanceId, "instance")
    val definitionId: String = text(definitionId, "definition")
    val tokenId: String = text(tokenId, "token")
    val nodeExecutionId: String = text(nodeExecutionId, "node execution")
    val workItemId: String = text(workItemId, "work item")
    val nodeId: String = WorkflowDomainSupport.requireCode(nodeId, "Workflow receipt node id is invalid.")
    val ruleDigest: String = WorkflowDomainSupport.requireSha256(ruleDigest, "Workflow rule digest is invalid.")
    val effectRequestDigest: String = WorkflowDomainSupport.requireSha256(
        effectRequestDigest,
        "Workflow participant effect request digest is invalid.",
    )
    val candidates: List<WorkflowPrincipalRef> = WorkflowDomainSupport.immutableList(
        candidates,
        WorkflowDomainSupport.MAX_CANDIDATES,
        "Workflow activation candidates are invalid or exceed the limit.",
    )
    val resolutionDigest: String = WorkflowDomainSupport.requireSha256(
        resolutionDigest,
        "Workflow participant resolution digest is invalid.",
    )
    val organizationAuthority: String? = organizationAuthority?.let { value ->
        WorkflowDomainSupport.requireCode(value, "Workflow participant organization authority is invalid.")
    }
    val organizationSnapshotRevision: String? = organizationSnapshotRevision?.let { value ->
        WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_REVISION_UTF8_BYTES,
            "Workflow participant organization revision is invalid.",
        )
    }
    val resolutionRequestDigest: String? = resolutionRequestDigest?.let { value ->
        WorkflowDomainSupport.requireSha256(value, "Workflow participant resolution request digest is invalid.")
    }
    val selectorDigest: String? = selectorDigest?.let { value ->
        WorkflowDomainSupport.requireSha256(value, "Workflow participant selector digest is invalid.")
    }
    val organizationProviderRevision: String? = organizationProviderRevision?.let { value ->
        WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_REVISION_UTF8_BYTES,
            "Workflow participant organization provider revision is invalid.",
        )
    }
    val organizationSnapshotDigest: String? = organizationSnapshotDigest?.let { value ->
        WorkflowDomainSupport.requireSha256(value, "Workflow participant organization snapshot digest is invalid.")
    }
    val organizationSnapshotReceiptDigest: String? = organizationSnapshotReceiptDigest?.let { value ->
        WorkflowDomainSupport.requireSha256(value, "Workflow participant organization snapshot receipt is invalid.")
    }
    val organizationConfirmationRevision: String? = organizationConfirmationRevision?.let { value ->
        WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_REVISION_UTF8_BYTES,
            "Workflow participant organization confirmation revision is invalid.",
        )
    }
    val organizationConfirmationSnapshotDigest: String? = organizationConfirmationSnapshotDigest?.let { value ->
        WorkflowDomainSupport.requireSha256(
            value,
            "Workflow participant organization confirmation snapshot is invalid.",
        )
    }
    val organizationConfirmationRequestDigest: String? = organizationConfirmationRequestDigest?.let { value ->
        WorkflowDomainSupport.requireSha256(
            value,
            "Workflow participant organization confirmation request is invalid.",
        )
    }
    val organizationConfirmationReceiptDigest: String? = organizationConfirmationReceiptDigest?.let { value ->
        WorkflowDomainSupport.requireSha256(
            value,
            "Workflow participant organization confirmation receipt is invalid.",
        )
    }
    val evidenceVersion: Int
    val hasOrganizationEvidence: Boolean
    val resolvedAt: Long = WorkflowDomainSupport.requireTime(resolvedAt, "Workflow resolution time is invalid.")
    val validUntil: Long = WorkflowDomainSupport.requireTime(validUntil, "Workflow resolution expiry is invalid.")
    val receiptDigest: String

    init {
        require(ruleIndex >= 0) { "Workflow participant rule index is invalid." }
        require(this.candidates.toSet().size == this.candidates.size) {
            "Workflow activation candidates must be unique."
        }
        require(this.validUntil >= this.resolvedAt) { "Workflow participant receipt window is invalid." }
        val organizationEvidence = listOf(
            this.organizationAuthority,
            this.organizationSnapshotRevision,
            this.resolutionRequestDigest,
            this.selectorDigest,
        )
        require(organizationEvidence.all { value -> value == null } ||
            organizationEvidence.all { value -> value != null }
        ) {
            "Workflow participant organization evidence must be complete."
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
            "Workflow participant organization confirmation evidence must be complete."
        }
        require(confirmationEvidence.first() == null || organizationEvidence.first() != null) {
            "Workflow participant confirmation evidence requires organization evidence."
        }
        require(confirmationEvidence.first() == null ||
            this.organizationSnapshotRevision == this.organizationConfirmationRevision
        ) { "Workflow participant organization revision changed during resolution." }
        hasOrganizationEvidence = organizationEvidence.first() != null
        evidenceVersion = when {
            confirmationEvidence.first() != null -> 3
            hasOrganizationEvidence -> 2
            else -> 1
        }
        val writer = WorkflowDomainSupport.digest(
            when (evidenceVersion) {
                3 -> "flowweft-workflow-domain-participant-receipt-v3"
                2 -> "flowweft-workflow-domain-participant-receipt-v2"
                else -> "flowweft-workflow-domain-participant-receipt-v1"
            },
        )
            .text(this.receiptId)
            .text(this.effectId)
            .text(this.tenantId)
            .text(this.instanceId)
            .text(this.definitionId)
            .text(definitionRef.key)
            .text(definitionRef.version)
            .text(definitionRef.digest)
            .text(subject.ref.type)
            .text(subject.ref.id)
            .text(subject.revision)
            .text(subject.digest)
            .text(this.tokenId)
            .text(this.nodeExecutionId)
            .text(this.workItemId)
            .text(this.nodeId)
            .integer(ruleIndex)
            .text(this.ruleDigest)
            .text(this.effectRequestDigest)
            .integer(this.candidates.size)
        this.candidates.forEach { candidate -> writer.text(candidate.type).text(candidate.id) }
        writer.text(this.resolutionDigest)
        if (hasOrganizationEvidence) {
            writer.text(requireNotNull(this.organizationAuthority))
                .text(requireNotNull(this.organizationSnapshotRevision))
                .text(requireNotNull(this.resolutionRequestDigest))
                .text(requireNotNull(this.selectorDigest))
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
        receiptDigest = writer
            .longValue(this.resolvedAt)
            .longValue(this.validUntil)
            .finish()
    }

    override fun toString(): String = "WorkflowParticipantActivationReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            receiptId: String,
            effectId: String,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            tokenId: String,
            nodeExecutionId: String,
            workItemId: String,
            nodeId: String,
            ruleIndex: Int,
            ruleDigest: String,
            effectRequestDigest: String,
            candidates: Collection<WorkflowPrincipalRef>,
            resolutionDigest: String,
            resolvedAt: Long,
            validUntil: Long,
        ): WorkflowParticipantActivationReceipt = WorkflowParticipantActivationReceipt(
            receiptId,
            effectId,
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            tokenId,
            nodeExecutionId,
            workItemId,
            nodeId,
            ruleIndex,
            ruleDigest,
            effectRequestDigest,
            candidates,
            resolutionDigest,
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
            null,
            resolvedAt,
            validUntil,
        )

        /**
         * Creates the 1.0 receipt form bound to the exact host directory snapshot and selector
         * request. The legacy [of] factory remains source/binary compatible for decoding 0.x/early
         * 1.0 snapshots, while production participant handlers must use this factory.
         */
        @JvmStatic
        fun organizationBound(
            receiptId: String,
            effectId: String,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            tokenId: String,
            nodeExecutionId: String,
            workItemId: String,
            nodeId: String,
            ruleIndex: Int,
            ruleDigest: String,
            effectRequestDigest: String,
            candidates: Collection<WorkflowPrincipalRef>,
            resolutionDigest: String,
            organizationAuthority: String,
            organizationSnapshotRevision: String,
            resolutionRequestDigest: String,
            selectorDigest: String,
            resolvedAt: Long,
            validUntil: Long,
        ): WorkflowParticipantActivationReceipt = WorkflowParticipantActivationReceipt(
            receiptId,
            effectId,
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            tokenId,
            nodeExecutionId,
            workItemId,
            nodeId,
            ruleIndex,
            ruleDigest,
            effectRequestDigest,
            candidates,
            resolutionDigest,
            organizationAuthority,
            organizationSnapshotRevision,
            resolutionRequestDigest,
            selectorDigest,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            resolvedAt,
            validUntil,
        )

        /**
         * Creates a receipt that durably proves the exact organization snapshot before and after
         * participant resolution. The two revisions must be identical; their snapshot and provider
         * receipt digests remain distinct so an audit can prove both calls actually occurred.
         */
        @JvmStatic
        fun organizationDoubleChecked(
            receiptId: String,
            effectId: String,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            tokenId: String,
            nodeExecutionId: String,
            workItemId: String,
            nodeId: String,
            ruleIndex: Int,
            ruleDigest: String,
            effectRequestDigest: String,
            candidates: Collection<WorkflowPrincipalRef>,
            resolutionDigest: String,
            organizationAuthority: String,
            organizationSnapshotRevision: String,
            resolutionRequestDigest: String,
            selectorDigest: String,
            organizationProviderRevision: String,
            organizationSnapshotDigest: String,
            organizationSnapshotReceiptDigest: String,
            organizationConfirmationRevision: String,
            organizationConfirmationSnapshotDigest: String,
            organizationConfirmationRequestDigest: String,
            organizationConfirmationReceiptDigest: String,
            resolvedAt: Long,
            validUntil: Long,
        ): WorkflowParticipantActivationReceipt = WorkflowParticipantActivationReceipt(
            receiptId,
            effectId,
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            tokenId,
            nodeExecutionId,
            workItemId,
            nodeId,
            ruleIndex,
            ruleDigest,
            effectRequestDigest,
            candidates,
            resolutionDigest,
            organizationAuthority,
            organizationSnapshotRevision,
            resolutionRequestDigest,
            selectorDigest,
            organizationProviderRevision,
            organizationSnapshotDigest,
            organizationSnapshotReceiptDigest,
            organizationConfirmationRevision,
            organizationConfirmationSnapshotDigest,
            organizationConfirmationRequestDigest,
            organizationConfirmationReceiptDigest,
            resolvedAt,
            validUntil,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow participant $label identifier is invalid.",
        )
    }
}
