package ai.icen.fw.workflow.api

/**
 * Bounded, non-executable participant selection instruction.
 *
 * Organization identifiers are tenant-relative references into host-owned HR data. This API owns
 * no organization CRUD and carries no script or expression language. Even [exactUser] resolves a
 * candidate only; it never grants that principal permission to view or act on the work item.
 */
class WorkflowParticipantSelector private constructor(
    val kind: WorkflowParticipantSelectorKind,
    val exactPrincipal: WorkflowPrincipalRef?,
    organizationId: String?,
    val minimumManagerLevel: Int?,
    val maximumManagerLevel: Int?,
) {
    val organizationId: String? = organizationId?.let {
        WorkflowContractSupport.requireText(
            it,
            WorkflowContractSupport.MAX_REFERENCE_ID_UTF8_BYTES,
            "Workflow participant organization identifier is invalid.",
        )
    }

    val digest: String

    init {
        when (kind) {
            WorkflowParticipantSelectorKind.EXACT_USER -> require(
                exactPrincipal != null && this.organizationId == null &&
                    minimumManagerLevel == null && maximumManagerLevel == null,
            ) { "Exact-user workflow selectors require only an exact principal." }

            WorkflowParticipantSelectorKind.GROUP,
            WorkflowParticipantSelectorKind.ROLE,
            WorkflowParticipantSelectorKind.POSITION,
            WorkflowParticipantSelectorKind.DEPARTMENT_LEADERS -> require(
                exactPrincipal == null && this.organizationId != null &&
                    minimumManagerLevel == null && maximumManagerLevel == null,
            ) { "Organization workflow selectors require only an organization identifier." }

            WorkflowParticipantSelectorKind.INITIATOR_MANAGER_CHAIN,
            WorkflowParticipantSelectorKind.CURRENT_ACTOR_MANAGER_CHAIN -> {
                require(exactPrincipal == null && this.organizationId == null) {
                    "Manager-chain workflow selectors derive their origin from request context."
                }
                require(
                    minimumManagerLevel != null && maximumManagerLevel != null &&
                        minimumManagerLevel in 1..WorkflowContractSupport.MAX_MANAGER_LEVEL &&
                        maximumManagerLevel in minimumManagerLevel..WorkflowContractSupport.MAX_MANAGER_LEVEL,
                ) { "Workflow manager-chain levels are invalid." }
            }

            else -> require(
                exactPrincipal == null && this.organizationId != null &&
                    minimumManagerLevel == null && maximumManagerLevel == null,
            ) { "Extension workflow selectors require one bounded opaque target." }
        }

        val writer = WorkflowContractSupport.digest(WorkflowContractSupport.SELECTOR_DIGEST_DOMAIN)
            .text(kind.code)
            .optionalText(exactPrincipal?.type)
            .optionalText(exactPrincipal?.id)
            .optionalText(this.organizationId)
            .booleanValue(minimumManagerLevel != null)
        minimumManagerLevel?.let(writer::integer)
        writer.booleanValue(maximumManagerLevel != null)
        maximumManagerLevel?.let(writer::integer)
        digest = writer.finish()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowParticipantSelector &&
            kind == other.kind &&
            exactPrincipal == other.exactPrincipal &&
            organizationId == other.organizationId &&
            minimumManagerLevel == other.minimumManagerLevel &&
            maximumManagerLevel == other.maximumManagerLevel

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + (exactPrincipal?.hashCode() ?: 0)
        result = 31 * result + (organizationId?.hashCode() ?: 0)
        result = 31 * result + (minimumManagerLevel ?: 0)
        result = 31 * result + (maximumManagerLevel ?: 0)
        return result
    }

    override fun toString(): String = "WorkflowParticipantSelector(<redacted>)"

    companion object {
        @JvmStatic
        fun exactUser(principal: WorkflowPrincipalRef): WorkflowParticipantSelector =
            WorkflowParticipantSelector(
                WorkflowParticipantSelectorKind.EXACT_USER,
                principal,
                null,
                null,
                null,
            )

        @JvmStatic
        fun group(groupId: String): WorkflowParticipantSelector =
            organization(WorkflowParticipantSelectorKind.GROUP, groupId)

        @JvmStatic
        fun role(roleId: String): WorkflowParticipantSelector =
            organization(WorkflowParticipantSelectorKind.ROLE, roleId)

        @JvmStatic
        fun position(positionId: String): WorkflowParticipantSelector =
            organization(WorkflowParticipantSelectorKind.POSITION, positionId)

        /** Resolves every active leader in the department; the request-level result cap is strict. */
        @JvmStatic
        fun departmentLeaders(departmentId: String): WorkflowParticipantSelector =
            organization(WorkflowParticipantSelectorKind.DEPARTMENT_LEADERS, departmentId)

        /** Resolves consecutive manager levels, nearest first, from the workflow initiator. */
        @JvmStatic
        fun initiatorManagerChain(minimumLevel: Int, maximumLevel: Int): WorkflowParticipantSelector =
            managerChain(WorkflowParticipantSelectorKind.INITIATOR_MANAGER_CHAIN, minimumLevel, maximumLevel)

        /** Resolves consecutive manager levels, nearest first, from the current trusted actor. */
        @JvmStatic
        fun currentActorManagerChain(minimumLevel: Int, maximumLevel: Int): WorkflowParticipantSelector =
            managerChain(WorkflowParticipantSelectorKind.CURRENT_ACTOR_MANAGER_CHAIN, minimumLevel, maximumLevel)

        /** Host extensions receive one opaque target; executable expressions are not accepted. */
        @JvmStatic
        fun extensionTarget(kind: WorkflowParticipantSelectorKind, targetId: String): WorkflowParticipantSelector {
            require(!isBuiltIn(kind)) { "Built-in workflow selector kinds require their dedicated factory." }
            return organization(kind, targetId)
        }

        private fun organization(
            kind: WorkflowParticipantSelectorKind,
            organizationId: String,
        ): WorkflowParticipantSelector = WorkflowParticipantSelector(
            kind,
            null,
            organizationId,
            null,
            null,
        )

        private fun managerChain(
            kind: WorkflowParticipantSelectorKind,
            minimumLevel: Int,
            maximumLevel: Int,
        ): WorkflowParticipantSelector = WorkflowParticipantSelector(
            kind,
            null,
            null,
            minimumLevel,
            maximumLevel,
        )

        private fun isManagerChain(kind: WorkflowParticipantSelectorKind): Boolean =
            kind == WorkflowParticipantSelectorKind.INITIATOR_MANAGER_CHAIN ||
                kind == WorkflowParticipantSelectorKind.CURRENT_ACTOR_MANAGER_CHAIN

        private fun isBuiltIn(kind: WorkflowParticipantSelectorKind): Boolean =
            kind == WorkflowParticipantSelectorKind.EXACT_USER ||
                kind == WorkflowParticipantSelectorKind.GROUP ||
                kind == WorkflowParticipantSelectorKind.ROLE ||
                kind == WorkflowParticipantSelectorKind.POSITION ||
                kind == WorkflowParticipantSelectorKind.DEPARTMENT_LEADERS ||
                isManagerChain(kind)
    }
}
