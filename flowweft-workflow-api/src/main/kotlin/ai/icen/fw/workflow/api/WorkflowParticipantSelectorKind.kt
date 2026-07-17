package ai.icen.fw.workflow.api

/** Extensible, case-sensitive machine code for participant selector semantics. */
class WorkflowParticipantSelectorKind private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow participant selector kind is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowParticipantSelectorKind && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "WorkflowParticipantSelectorKind(<redacted>)"

    companion object {
        @JvmField
        val EXACT_USER = WorkflowParticipantSelectorKind("exact-user")

        /** Canonical 1.0 direct-user selector. [EXACT_USER] remains source compatible. */
        @JvmField
        val USER = WorkflowParticipantSelectorKind("user")

        @JvmField
        val GROUP = WorkflowParticipantSelectorKind("group")

        @JvmField
        val ROLE = WorkflowParticipantSelectorKind("role")

        @JvmField
        val POSITION = WorkflowParticipantSelectorKind("position")

        @JvmField
        val PERMISSION = WorkflowParticipantSelectorKind("permission")

        @JvmField
        val ORG_UNIT_MEMBER = WorkflowParticipantSelectorKind("org-unit-member")

        @JvmField
        val ORG_UNIT_MANAGER = WorkflowParticipantSelectorKind("org-unit-manager")

        @JvmField
        val DEPARTMENT_LEADERS = WorkflowParticipantSelectorKind("department-leaders")

        @JvmField
        val INITIATOR_MANAGER_CHAIN = WorkflowParticipantSelectorKind("initiator-manager-chain")

        @JvmField
        val CURRENT_ACTOR_MANAGER_CHAIN = WorkflowParticipantSelectorKind("current-actor-manager-chain")

        @JvmField
        val INITIATOR = WorkflowParticipantSelectorKind("initiator")

        @JvmField
        val VARIABLE_USER = WorkflowParticipantSelectorKind("variable-user")

        @JvmField
        val CUSTOM = WorkflowParticipantSelectorKind("custom")

        /** Custom values should use a host-owned namespace; no value carries executable text. */
        @JvmStatic
        fun of(code: String): WorkflowParticipantSelectorKind = when (code) {
            EXACT_USER.code -> EXACT_USER
            USER.code -> USER
            GROUP.code -> GROUP
            ROLE.code -> ROLE
            POSITION.code -> POSITION
            PERMISSION.code -> PERMISSION
            ORG_UNIT_MEMBER.code -> ORG_UNIT_MEMBER
            ORG_UNIT_MANAGER.code -> ORG_UNIT_MANAGER
            DEPARTMENT_LEADERS.code -> DEPARTMENT_LEADERS
            INITIATOR_MANAGER_CHAIN.code -> INITIATOR_MANAGER_CHAIN
            CURRENT_ACTOR_MANAGER_CHAIN.code -> CURRENT_ACTOR_MANAGER_CHAIN
            INITIATOR.code -> INITIATOR
            VARIABLE_USER.code -> VARIABLE_USER
            CUSTOM.code -> CUSTOM
            else -> WorkflowParticipantSelectorKind(code)
        }
    }
}
