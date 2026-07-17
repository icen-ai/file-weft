package ai.icen.fw.workflow.sla

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarProfile
import ai.icen.fw.workflow.spi.WorkflowBusinessCalendarRef

private fun slaCode(value: String, label: String): String = WorkflowSlaSupport.code(
    value,
    "Workflow SLA $label is invalid.",
)

private fun slaId(value: String, label: String): String = WorkflowSlaSupport.text(
    value,
    WorkflowSlaSupport.MAX_ID_BYTES,
    "Workflow SLA $label is invalid.",
)

private fun slaText(value: String, label: String): String = WorkflowSlaSupport.text(
    value,
    WorkflowSlaSupport.MAX_TEXT_BYTES,
    "Workflow SLA $label is invalid.",
)

private fun slaSha(value: String, label: String): String = WorkflowSlaSupport.sha256(
    value,
    "Workflow SLA $label digest is invalid.",
)

/** Stable milestone identity. Adding a future milestone does not extend a public enum. */
class WorkflowSlaMilestoneKind private constructor(code: String) {
    val code: String = slaCode(code, "milestone kind")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowSlaMilestoneKind && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowSlaMilestoneKind(<redacted>)"

    companion object {
        @JvmField val REMINDER = WorkflowSlaMilestoneKind("reminder")
        @JvmField val DUE = WorkflowSlaMilestoneKind("due")
        @JvmField val ESCALATION = WorkflowSlaMilestoneKind("escalation")

        @JvmStatic
        fun of(code: String): WorkflowSlaMilestoneKind = builtIns.firstOrNull { it.code == code }
            ?: WorkflowSlaMilestoneKind(code)

        private val builtIns = listOf(REMINDER, DUE, ESCALATION)
    }
}

/**
 * Provider-neutral action. Deliberately absent are APPROVE, REJECT, CLAIM and every other task
 * decision: SLA expiry can never decide a human task.
 */
class WorkflowSlaActionKind private constructor(code: String) {
    val code: String = slaCode(code, "action kind")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowSlaActionKind && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowSlaActionKind(<redacted>)"

    companion object {
        @JvmField val REMINDER = WorkflowSlaActionKind("reminder")
        @JvmField val INCIDENT = WorkflowSlaActionKind("incident")
        @JvmField val ESCALATION = WorkflowSlaActionKind("escalation")

        @JvmStatic
        fun of(code: String): WorkflowSlaActionKind = when (code) {
            REMINDER.code -> REMINDER
            INCIDENT.code -> INCIDENT
            ESCALATION.code -> ESCALATION
            else -> throw IllegalArgumentException("Unknown Workflow SLA action kinds require typed runtime support.")
        }
    }
}

/** Exact registry and provider binding used for every business-calendar calculation. */
class WorkflowSlaCalendarBinding private constructor(
    profileId: String,
    profileVersion: String,
    profileDigest: String,
    profileBindingDigest: String,
    val calendar: WorkflowBusinessCalendarRef,
    val providerProfile: WorkflowBusinessCalendarProfile,
) {
    val profileId: String = slaCode(profileId, "calendar profile id")
    val profileVersion: String = slaText(profileVersion, "calendar profile version")
    val profileDigest: String = slaSha(profileDigest, "calendar profile")
    val profileBindingDigest: String = slaSha(profileBindingDigest, "calendar profile binding")
    val providerProfileDigest: String
    val bindingDigest: String

    init {
        require(calendar.providerId == providerProfile.providerId) {
            "Workflow SLA calendar provider does not match the runtime provider profile."
        }
        require(!this.profileVersion.equals("latest", ignoreCase = true) &&
            !calendar.version.equals("latest", ignoreCase = true) &&
            !providerProfile.providerRevision.equals("latest", ignoreCase = true)
        ) { "Workflow SLA calendar bindings require exact revisions." }
        providerProfileDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-calendar-provider-v1")
            .text(providerProfile.providerId)
            .text(providerProfile.providerRevision)
            .longValue(providerProfile.callWindowMillis)
            .integer(providerProfile.maximumInputBytes)
            .integer(providerProfile.maximumOutputBytes)
            .finish()
        bindingDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-calendar-binding-v1")
            .text(this.profileId)
            .text(this.profileVersion)
            .text(this.profileDigest)
            .text(this.profileBindingDigest)
            .text(calendar.providerId)
            .text(calendar.calendarId)
            .text(calendar.version)
            .text(calendar.digest)
            .text(providerProfileDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowSlaCalendarBinding(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            profileId: String,
            profileVersion: String,
            profileDigest: String,
            profileBindingDigest: String,
            calendar: WorkflowBusinessCalendarRef,
            providerProfile: WorkflowBusinessCalendarProfile,
        ): WorkflowSlaCalendarBinding = WorkflowSlaCalendarBinding(
            profileId,
            profileVersion,
            profileDigest,
            profileBindingDigest,
            calendar,
            providerProfile,
        )
    }
}

/** Exact host action implementation plus hard execution and retry budgets. */
class WorkflowSlaActionProfile private constructor(
    profileId: String,
    profileVersion: String,
    profileDigest: String,
    providerId: String,
    providerRevision: String,
    val callWindowMillis: Long,
    val maximumInputBytes: Int,
    val maximumOutputBytes: Int,
    val maximumAttempts: Int,
    val retryDelayMillis: Long,
) {
    val profileId: String = slaCode(profileId, "action profile id")
    val profileVersion: String = slaText(profileVersion, "action profile version")
    val profileDigest: String = slaSha(profileDigest, "action profile")
    val providerId: String = slaCode(providerId, "action provider id")
    val providerRevision: String = slaText(providerRevision, "action provider revision")
    val bindingDigest: String

    init {
        require(!this.profileVersion.equals("latest", ignoreCase = true) &&
            !this.providerRevision.equals("latest", ignoreCase = true)
        ) { "Workflow SLA action bindings require exact revisions." }
        require(callWindowMillis in 1L..MAX_CALL_WINDOW_MILLIS) {
            "Workflow SLA action call window is invalid."
        }
        require(maximumInputBytes in 1..MAX_BYTES && maximumOutputBytes in 1..MAX_BYTES) {
            "Workflow SLA action byte budget is invalid."
        }
        require(maximumAttempts in 1..MAX_ATTEMPTS && retryDelayMillis in 1L..MAX_RETRY_DELAY_MILLIS) {
            "Workflow SLA action retry budget is invalid."
        }
        bindingDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-action-profile-v1")
            .text(this.profileId)
            .text(this.profileVersion)
            .text(this.profileDigest)
            .text(this.providerId)
            .text(this.providerRevision)
            .longValue(callWindowMillis)
            .integer(maximumInputBytes)
            .integer(maximumOutputBytes)
            .integer(maximumAttempts)
            .longValue(retryDelayMillis)
            .finish()
    }

    override fun toString(): String = "WorkflowSlaActionProfile(<redacted>)"

    companion object {
        const val MAX_CALL_WINDOW_MILLIS: Long = 300_000L
        const val MAX_BYTES: Int = 4 * 1024 * 1024
        const val MAX_ATTEMPTS: Int = 100
        const val MAX_RETRY_DELAY_MILLIS: Long = 86_400_000L

        @JvmStatic
        fun of(
            profileId: String,
            profileVersion: String,
            profileDigest: String,
            providerId: String,
            providerRevision: String,
            callWindowMillis: Long,
            maximumInputBytes: Int,
            maximumOutputBytes: Int,
            maximumAttempts: Int,
            retryDelayMillis: Long,
        ): WorkflowSlaActionProfile = WorkflowSlaActionProfile(
            profileId,
            profileVersion,
            profileDigest,
            providerId,
            providerRevision,
            callWindowMillis,
            maximumInputBytes,
            maximumOutputBytes,
            maximumAttempts,
            retryDelayMillis,
        )
    }
}

class WorkflowSlaMilestonePolicy private constructor(
    val kind: WorkflowSlaMilestoneKind,
    val action: WorkflowSlaActionKind,
    val workingDurationMillis: Long,
) {
    val contentDigest: String

    init {
        require(workingDurationMillis > 0L) { "Workflow SLA working duration must be positive." }
        contentDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-milestone-policy-v1")
            .text(kind.code)
            .text(action.code)
            .longValue(workingDurationMillis)
            .finish()
    }

    override fun toString(): String = "WorkflowSlaMilestonePolicy(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            kind: WorkflowSlaMilestoneKind,
            action: WorkflowSlaActionKind,
            workingDurationMillis: Long,
        ): WorkflowSlaMilestonePolicy = WorkflowSlaMilestonePolicy(kind, action, workingDurationMillis)
    }
}

/** Exact, immutable policy executable by the SLA runtime. */
class WorkflowSlaPolicy private constructor(
    policyId: String,
    policyVersion: String,
    sourcePolicyDigest: String,
    val definitionRef: WorkflowDefinitionRef,
    nodeId: String,
    val calendarBinding: WorkflowSlaCalendarBinding,
    val actionProfile: WorkflowSlaActionProfile,
    milestones: Collection<WorkflowSlaMilestonePolicy>,
) {
    val policyId: String = slaCode(policyId, "policy id")
    val policyVersion: String = slaText(policyVersion, "policy version")
    val sourcePolicyDigest: String = slaSha(sourcePolicyDigest, "source policy")
    val nodeId: String = slaCode(nodeId, "policy node id")
    val milestones: List<WorkflowSlaMilestonePolicy> = WorkflowSlaSupport.immutable(
        milestones,
        8,
        "Workflow SLA milestone policies are invalid or exceed the limit.",
    )
    val policyDigest: String

    init {
        require(!this.policyVersion.equals("latest", ignoreCase = true) &&
            !definitionRef.version.equals("latest", ignoreCase = true)
        ) { "Workflow SLA policies require exact revisions." }
        require(this.milestones.map { it.kind }.toSet().size == this.milestones.size) {
            "Workflow SLA milestone kinds must be unique."
        }
        require(this.milestones.size == 3 &&
            this.milestones[0].kind == WorkflowSlaMilestoneKind.REMINDER &&
            this.milestones[1].kind == WorkflowSlaMilestoneKind.DUE &&
            this.milestones[2].kind == WorkflowSlaMilestoneKind.ESCALATION
        ) { "Workflow SLA 1.0 policies require reminder, due and escalation milestones in order." }
        require(this.milestones[0].action == WorkflowSlaActionKind.REMINDER &&
            this.milestones[1].action == WorkflowSlaActionKind.INCIDENT &&
            this.milestones[2].action == WorkflowSlaActionKind.ESCALATION
        ) { "Workflow SLA 1.0 milestone action mapping is invalid." }
        require(this.milestones[0].workingDurationMillis < this.milestones[1].workingDurationMillis &&
            this.milestones[1].workingDurationMillis <= this.milestones[2].workingDurationMillis
        ) { "Workflow SLA milestone durations must be ordered and reminder must precede due." }
        val writer = WorkflowSlaSupport.digest("flowweft-workflow-sla-policy-v1")
            .text(this.policyId)
            .text(this.policyVersion)
            .text(this.sourcePolicyDigest)
            .text(definitionRef.key)
            .text(definitionRef.version)
            .text(definitionRef.digest)
            .text(this.nodeId)
            .text(calendarBinding.bindingDigest)
            .text(actionProfile.bindingDigest)
            .integer(this.milestones.size)
        this.milestones.forEach { writer.text(it.contentDigest) }
        policyDigest = writer.finish()
    }

    override fun toString(): String = "WorkflowSlaPolicy(<redacted>)"

    companion object {
        @JvmStatic
        fun standard(
            policyId: String,
            policyVersion: String,
            sourcePolicyDigest: String,
            definitionRef: WorkflowDefinitionRef,
            nodeId: String,
            calendarBinding: WorkflowSlaCalendarBinding,
            actionProfile: WorkflowSlaActionProfile,
            reminderAfterWorkingMillis: Long,
            dueAfterWorkingMillis: Long,
            escalationAfterWorkingMillis: Long,
        ): WorkflowSlaPolicy = WorkflowSlaPolicy(
            policyId,
            policyVersion,
            sourcePolicyDigest,
            definitionRef,
            nodeId,
            calendarBinding,
            actionProfile,
            listOf(
                WorkflowSlaMilestonePolicy.of(
                    WorkflowSlaMilestoneKind.REMINDER,
                    WorkflowSlaActionKind.REMINDER,
                    reminderAfterWorkingMillis,
                ),
                WorkflowSlaMilestonePolicy.of(
                    WorkflowSlaMilestoneKind.DUE,
                    WorkflowSlaActionKind.INCIDENT,
                    dueAfterWorkingMillis,
                ),
                WorkflowSlaMilestonePolicy.of(
                    WorkflowSlaMilestoneKind.ESCALATION,
                    WorkflowSlaActionKind.ESCALATION,
                    escalationAfterWorkingMillis,
                ),
            ),
        )

        @JvmStatic
        fun of(
            policyId: String,
            policyVersion: String,
            sourcePolicyDigest: String,
            definitionRef: WorkflowDefinitionRef,
            nodeId: String,
            calendarBinding: WorkflowSlaCalendarBinding,
            actionProfile: WorkflowSlaActionProfile,
            milestones: Collection<WorkflowSlaMilestonePolicy>,
        ): WorkflowSlaPolicy = WorkflowSlaPolicy(
            policyId,
            policyVersion,
            sourcePolicyDigest,
            definitionRef,
            nodeId,
            calendarBinding,
            actionProfile,
            milestones,
        )
    }
}

internal fun slaIdentifier(value: String, label: String): String = slaId(value, label)
internal fun slaMachineCode(value: String, label: String): String = slaCode(value, label)
internal fun slaBoundedText(value: String, label: String): String = slaText(value, label)
internal fun slaDigest(value: String, label: String): String = slaSha(value, label)
