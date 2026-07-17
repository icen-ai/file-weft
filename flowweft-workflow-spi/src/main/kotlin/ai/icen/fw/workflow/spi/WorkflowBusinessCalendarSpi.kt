package ai.icen.fw.workflow.spi

import java.util.concurrent.CompletionStage

class WorkflowBusinessCalendarRef private constructor(
    providerId: String,
    calendarId: String,
    version: String,
    digest: String,
) {
    val providerId: String = WorkflowSpiContractSupport.requireMachineCode(providerId, "Workflow calendar provider is invalid.")
    val calendarId: String = WorkflowSpiContractSupport.requireMachineCode(calendarId, "Workflow calendar identifier is invalid.")
    val version: String = WorkflowSpiContractSupport.requireText(
        version, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow calendar version is invalid.",
    )
    val digest: String = WorkflowSpiContractSupport.requireCanonicalSha256(digest, "Workflow calendar digest is invalid.")

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowBusinessCalendarRef && providerId == other.providerId && calendarId == other.calendarId &&
        version == other.version && digest == other.digest

    override fun hashCode(): Int {
        var result = providerId.hashCode()
        result = 31 * result + calendarId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + digest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowBusinessCalendarRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(providerId: String, calendarId: String, version: String, digest: String): WorkflowBusinessCalendarRef =
            WorkflowBusinessCalendarRef(providerId, calendarId, version, digest)
    }
}

class WorkflowBusinessTimeOperation private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow business-time operation is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowBusinessTimeOperation && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowBusinessTimeOperation(<redacted>)"

    companion object {
        @JvmField val IS_WORKING_INSTANT = WorkflowBusinessTimeOperation("is-working-instant")
        @JvmField val NEXT_WORKING_INSTANT = WorkflowBusinessTimeOperation("next-working-instant")
        @JvmField val ADD_WORKING_DURATION = WorkflowBusinessTimeOperation("add-working-duration")

        @JvmStatic
        fun of(code: String): WorkflowBusinessTimeOperation = when (code) {
            IS_WORKING_INSTANT.code -> IS_WORKING_INSTANT
            NEXT_WORKING_INSTANT.code -> NEXT_WORKING_INSTANT
            ADD_WORKING_DURATION.code -> ADD_WORKING_DURATION
            else -> WorkflowBusinessTimeOperation(code)
        }
    }
}

class WorkflowBusinessTimeRequest private constructor(
    val context: WorkflowProviderCallContext,
    val calendar: WorkflowBusinessCalendarRef,
    val operation: WorkflowBusinessTimeOperation,
    val instantEpochMilli: Long,
    val workingDurationMillis: Long?,
) {
    val requestDigest: String

    init {
        require(context.providerId == calendar.providerId) {
            "Workflow calendar reference does not match the provider context."
        }
        require(instantEpochMilli >= 0L) { "Workflow business-time instant is invalid." }
        when (operation) {
            WorkflowBusinessTimeOperation.IS_WORKING_INSTANT,
            WorkflowBusinessTimeOperation.NEXT_WORKING_INSTANT -> require(workingDurationMillis == null) {
                "This workflow business-time operation does not accept a duration."
            }
            WorkflowBusinessTimeOperation.ADD_WORKING_DURATION -> require(
                workingDurationMillis != null && workingDurationMillis > 0L,
            ) { "Adding workflow business time requires a positive duration." }
            else -> throw IllegalArgumentException("Unknown workflow business-time operations require future typed support.")
        }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-business-time-request-v1")
            .text(context.contextDigest)
            .text(calendar.providerId)
            .text(calendar.calendarId)
            .text(calendar.version)
            .text(calendar.digest)
            .text(operation.code)
            .longValue(instantEpochMilli)
            .booleanValue(workingDurationMillis != null)
            .also { writer -> workingDurationMillis?.let(writer::longValue) }
            .finish()
    }

    override fun toString(): String = "WorkflowBusinessTimeRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun isWorkingInstant(
            context: WorkflowProviderCallContext,
            calendar: WorkflowBusinessCalendarRef,
            instantEpochMilli: Long,
        ): WorkflowBusinessTimeRequest = WorkflowBusinessTimeRequest(
            context, calendar, WorkflowBusinessTimeOperation.IS_WORKING_INSTANT, instantEpochMilli, null,
        )

        @JvmStatic
        fun nextWorkingInstant(
            context: WorkflowProviderCallContext,
            calendar: WorkflowBusinessCalendarRef,
            instantEpochMilli: Long,
        ): WorkflowBusinessTimeRequest = WorkflowBusinessTimeRequest(
            context, calendar, WorkflowBusinessTimeOperation.NEXT_WORKING_INSTANT, instantEpochMilli, null,
        )

        @JvmStatic
        fun addWorkingDuration(
            context: WorkflowProviderCallContext,
            calendar: WorkflowBusinessCalendarRef,
            instantEpochMilli: Long,
            workingDurationMillis: Long,
        ): WorkflowBusinessTimeRequest = WorkflowBusinessTimeRequest(
            context,
            calendar,
            WorkflowBusinessTimeOperation.ADD_WORKING_DURATION,
            instantEpochMilli,
            workingDurationMillis,
        )
    }
}

class WorkflowBusinessTimeValue private constructor(
    val workingInstant: Boolean?,
    val resultingEpochMilli: Long?,
    calendarRevision: String,
) {
    val calendarRevision: String = WorkflowSpiContractSupport.requireText(
        calendarRevision, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow calendar revision is invalid.",
    )
    val valueDigest: String

    init {
        require((workingInstant == null) != (resultingEpochMilli == null)) {
            "Workflow business-time value requires exactly one result shape."
        }
        require(resultingEpochMilli == null || resultingEpochMilli >= 0L) {
            "Workflow business-time result instant is invalid."
        }
        valueDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-business-time-value-v1")
            .booleanValue(workingInstant != null)
            .booleanValue(workingInstant ?: false)
            .booleanValue(resultingEpochMilli != null)
            .longValue(resultingEpochMilli ?: 0L)
            .text(this.calendarRevision)
            .finish()
    }

    override fun toString(): String = "WorkflowBusinessTimeValue(<redacted>)"

    companion object {
        @JvmStatic
        fun working(working: Boolean, calendarRevision: String): WorkflowBusinessTimeValue =
            WorkflowBusinessTimeValue(working, null, calendarRevision)

        @JvmStatic
        fun instant(resultingEpochMilli: Long, calendarRevision: String): WorkflowBusinessTimeValue =
            WorkflowBusinessTimeValue(null, resultingEpochMilli, calendarRevision)
    }
}

class WorkflowBusinessTimeResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val value: WorkflowBusinessTimeValue?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (value != null)) {
            "Workflow business-time result content does not match its outcome."
        }
    }

    override fun toString(): String = "WorkflowBusinessTimeResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowBusinessTimeRequest,
            value: WorkflowBusinessTimeValue,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowBusinessTimeResult {
            when (request.operation) {
                WorkflowBusinessTimeOperation.IS_WORKING_INSTANT -> require(value.workingInstant != null) {
                    "Working-instant evaluation requires a boolean result."
                }
                WorkflowBusinessTimeOperation.NEXT_WORKING_INSTANT -> require(
                    value.resultingEpochMilli != null && value.resultingEpochMilli >= request.instantEpochMilli,
                ) { "Next working instant cannot precede the requested instant." }
                WorkflowBusinessTimeOperation.ADD_WORKING_DURATION -> require(
                    value.resultingEpochMilli != null && value.resultingEpochMilli > request.instantEpochMilli,
                ) { "Adding working duration requires a later instant result." }
                else -> throw IllegalArgumentException(
                    "Unknown workflow business-time operations require future typed support.",
                )
            }
            return WorkflowBusinessTimeResult(
                WorkflowProviderReceipt.success(
                    request.context,
                    request.requestDigest,
                    value.valueDigest,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
                value,
            )
        }

        @JvmStatic
        fun failure(
            request: WorkflowBusinessTimeRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowBusinessTimeResult = WorkflowBusinessTimeResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-business-time-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

fun interface WorkflowBusinessCalendar {
    fun evaluate(request: WorkflowBusinessTimeRequest): CompletionStage<WorkflowBusinessTimeResult>
}
