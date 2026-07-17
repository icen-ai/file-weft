package ai.icen.fw.workflow.api

/**
 * One non-empty, ordered participant tier produced for an exact request selector.
 *
 * [originAndDelegationDigest] is the resolver's canonical SHA-256 commitment to the ordered mapping
 * from effective principals to original candidates and delegation hops. Its schema is owned by the
 * named organization authority and pinned by the resolution authority revision. It is evidence for
 * audit comparison, not authorization proof.
 */
class WorkflowParticipantTier private constructor(
    selector: WorkflowParticipantSelector,
    val tierIndex: Int,
    val managerLevel: Int?,
    principals: List<WorkflowPrincipalRef>,
    originAndDelegationDigest: String,
) {
    val selectorDigest: String = selector.digest
    val principals: List<WorkflowPrincipalRef> = WorkflowContractSupport.immutableList(
        principals,
        WorkflowContractSupport.MAX_PRINCIPALS,
        "Workflow participant tier principals are invalid or exceed the limit.",
    )
    val originAndDelegationDigest: String = WorkflowContractSupport.requireCanonicalSha256(
        originAndDelegationDigest,
        "Workflow participant origin and delegation digest is invalid.",
    )
    val digest: String

    init {
        require(tierIndex in 0 until WorkflowContractSupport.MAX_TIERS) {
            "Workflow participant tier index is invalid."
        }
        require(this.principals.isNotEmpty()) { "Resolved workflow participant tiers must not be empty." }
        require(this.principals.toSet().size == this.principals.size) {
            "Workflow participant tier principals must be unique."
        }

        if (isManagerChain(selector.kind)) {
            require(
                managerLevel != null &&
                    managerLevel in selector.minimumManagerLevel!!..selector.maximumManagerLevel!!,
            ) { "Workflow manager tier level is outside its selector range." }
        } else {
            require(managerLevel == null) { "Only manager-chain selectors may declare a manager level." }
        }

        val writer = WorkflowContractSupport.digest(WorkflowContractSupport.TIER_DIGEST_DOMAIN)
            .text(selectorDigest)
            .integer(tierIndex)
            .booleanValue(managerLevel != null)
        managerLevel?.let(writer::integer)
        writer.integer(this.principals.size)
        this.principals.forEach { principal ->
            writer.text(principal.type).text(principal.id)
        }
        writer.text(this.originAndDelegationDigest)
        digest = writer.finish()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowParticipantTier &&
            selectorDigest == other.selectorDigest &&
            tierIndex == other.tierIndex &&
            managerLevel == other.managerLevel &&
            principals == other.principals &&
            originAndDelegationDigest == other.originAndDelegationDigest

    override fun hashCode(): Int {
        var result = selectorDigest.hashCode()
        result = 31 * result + tierIndex
        result = 31 * result + (managerLevel ?: 0)
        result = 31 * result + principals.hashCode()
        result = 31 * result + originAndDelegationDigest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowParticipantTier(<redacted>)"

    companion object {
        /** Direct tiers may repeat for one selector to represent an ordered responsibility matrix. */
        @JvmStatic
        fun direct(
            selector: WorkflowParticipantSelector,
            tierIndex: Int,
            principals: List<WorkflowPrincipalRef>,
            originAndDelegationDigest: String,
        ): WorkflowParticipantTier = WorkflowParticipantTier(
            selector,
            tierIndex,
            null,
            principals,
            originAndDelegationDigest,
        )

        @JvmStatic
        fun manager(
            selector: WorkflowParticipantSelector,
            tierIndex: Int,
            managerLevel: Int,
            principals: List<WorkflowPrincipalRef>,
            originAndDelegationDigest: String,
        ): WorkflowParticipantTier = WorkflowParticipantTier(
            selector,
            tierIndex,
            managerLevel,
            principals,
            originAndDelegationDigest,
        )

        private fun isManagerChain(kind: WorkflowParticipantSelectorKind): Boolean =
            kind == WorkflowParticipantSelectorKind.INITIATOR_MANAGER_CHAIN ||
                kind == WorkflowParticipantSelectorKind.CURRENT_ACTOR_MANAGER_CHAIN
    }
}
