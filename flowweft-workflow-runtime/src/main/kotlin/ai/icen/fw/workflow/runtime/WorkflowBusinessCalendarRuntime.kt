package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.spi.WorkflowBusinessCalendar
import ai.icen.fw.workflow.spi.WorkflowBusinessCalendarRef
import ai.icen.fw.workflow.spi.WorkflowBusinessTimeOperation
import ai.icen.fw.workflow.spi.WorkflowBusinessTimeRequest
import ai.icen.fw.workflow.spi.WorkflowBusinessTimeResult
import ai.icen.fw.workflow.spi.WorkflowBusinessTimeValue
import ai.icen.fw.workflow.spi.WorkflowProviderCallContext
import ai.icen.fw.workflow.spi.WorkflowProviderOutcome
import ai.icen.fw.workflow.spi.WorkflowProviderReceipt
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/** Stable, content-free outcome of one authorized business-calendar evaluation. */
class WorkflowBusinessCalendarResultCode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow business-calendar result code is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowBusinessCalendarResultCode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowBusinessCalendarResultCode(<redacted>)"

    companion object {
        @JvmField val SUCCEEDED = WorkflowBusinessCalendarResultCode("succeeded")
        @JvmField val AUTHORIZATION_DENIED = WorkflowBusinessCalendarResultCode("authorization-denied")
        @JvmField val PROVIDER_UNAVAILABLE = WorkflowBusinessCalendarResultCode("provider-unavailable")
        @JvmField val PROVIDER_REJECTED = WorkflowBusinessCalendarResultCode("provider-rejected")
        @JvmField val RECEIPT_INVALID = WorkflowBusinessCalendarResultCode("receipt-invalid")
        @JvmField val INVALID = WorkflowBusinessCalendarResultCode("invalid")
    }
}

/** Sanitized runtime diagnostic. Provider exception text and calendar data are never retained. */
class WorkflowBusinessCalendarDiagnostic private constructor(
    code: String,
    val retryable: Boolean,
) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow business-calendar diagnostic is invalid.")
    val digest: String = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-calendar-diagnostic-v1")
        .text(this.code)
        .bool(retryable)
        .finish()

    override fun toString(): String = "WorkflowBusinessCalendarDiagnostic(<redacted>)"

    companion object {
        @JvmStatic
        fun of(code: String, retryable: Boolean): WorkflowBusinessCalendarDiagnostic =
            WorkflowBusinessCalendarDiagnostic(code, retryable)
    }
}

/** Trusted host-selected provider identity and hard invocation budgets. */
class WorkflowBusinessCalendarProfile private constructor(
    providerId: String,
    providerRevision: String,
    val callWindowMillis: Long,
    val maximumInputBytes: Int,
    val maximumOutputBytes: Int,
) {
    val providerId: String = WorkflowRuntimeSupport.code(providerId, "Workflow calendar provider id is invalid.")
    val providerRevision: String = WorkflowRuntimeSupport.text(
        providerRevision,
        WorkflowRuntimeSupport.MAX_TEXT_BYTES,
        "Workflow calendar provider revision is invalid.",
    )

    init {
        require(callWindowMillis in 1L..MAX_CALL_WINDOW_MILLIS) {
            "Workflow calendar provider call window is invalid."
        }
        require(maximumInputBytes in 1..MAX_BYTES && maximumOutputBytes in 1..MAX_BYTES) {
            "Workflow calendar provider byte budget is invalid."
        }
    }

    override fun toString(): String = "WorkflowBusinessCalendarProfile(<redacted>)"

    companion object {
        const val MAX_CALL_WINDOW_MILLIS: Long = 300_000L
        const val MAX_BYTES: Int = 64 * 1024

        @JvmStatic
        fun of(
            providerId: String,
            providerRevision: String,
            callWindowMillis: Long,
            maximumInputBytes: Int,
            maximumOutputBytes: Int,
        ): WorkflowBusinessCalendarProfile = WorkflowBusinessCalendarProfile(
            providerId,
            providerRevision,
            callWindowMillis,
            maximumInputBytes,
            maximumOutputBytes,
        )
    }
}

/** Exact authorization-bound calendar question. It contains no locale rules or provider credentials. */
class WorkflowBusinessCalendarCommand private constructor(
    val callContext: WorkflowTrustedCallContext,
    requestId: String,
    instanceId: String,
    val subject: WorkflowSubjectSnapshot,
    val calendar: WorkflowBusinessCalendarRef,
    val operation: WorkflowBusinessTimeOperation,
    instantEpochMilli: Long,
    workingDurationMillis: Long?,
) {
    val requestId: String = WorkflowRuntimeSupport.text(
        requestId,
        WorkflowRuntimeSupport.MAX_ID_BYTES,
        "Workflow calendar request id is invalid.",
    )
    val instanceId: String = WorkflowRuntimeSupport.text(
        instanceId,
        WorkflowRuntimeSupport.MAX_ID_BYTES,
        "Workflow calendar instance id is invalid.",
    )
    val instantEpochMilli: Long = WorkflowRuntimeSupport.nonNegative(
        instantEpochMilli,
        "Workflow calendar instant is invalid.",
    )
    val workingDurationMillis: Long? = workingDurationMillis
    val requestDigest: String

    init {
        when (operation) {
            WorkflowBusinessTimeOperation.IS_WORKING_INSTANT,
            WorkflowBusinessTimeOperation.NEXT_WORKING_INSTANT -> require(this.workingDurationMillis == null) {
                "Workflow calendar operation does not accept a duration."
            }
            WorkflowBusinessTimeOperation.ADD_WORKING_DURATION -> require(
                this.workingDurationMillis != null && this.workingDurationMillis > 0L,
            ) { "Workflow calendar duration must be positive." }
            else -> throw IllegalArgumentException("Unsupported workflow calendar operation.")
        }
        requestDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-calendar-command-v1")
            .text(callContext.contextDigest)
            .text(this.requestId)
            .text(this.instanceId)
            .text(subject.ref.type)
            .text(subject.ref.id)
            .text(subject.revision)
            .text(subject.digest)
            .text(calendar.providerId)
            .text(calendar.calendarId)
            .text(calendar.version)
            .text(calendar.digest)
            .text(operation.code)
            .longValue(this.instantEpochMilli)
            .bool(this.workingDurationMillis != null)
            .also { writer -> this.workingDurationMillis?.let(writer::longValue) }
            .finish()
    }

    override fun toString(): String = "WorkflowBusinessCalendarCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun isWorkingInstant(
            callContext: WorkflowTrustedCallContext,
            requestId: String,
            instanceId: String,
            subject: WorkflowSubjectSnapshot,
            calendar: WorkflowBusinessCalendarRef,
            instantEpochMilli: Long,
        ): WorkflowBusinessCalendarCommand = WorkflowBusinessCalendarCommand(
            callContext,
            requestId,
            instanceId,
            subject,
            calendar,
            WorkflowBusinessTimeOperation.IS_WORKING_INSTANT,
            instantEpochMilli,
            null,
        )

        @JvmStatic
        fun nextWorkingInstant(
            callContext: WorkflowTrustedCallContext,
            requestId: String,
            instanceId: String,
            subject: WorkflowSubjectSnapshot,
            calendar: WorkflowBusinessCalendarRef,
            instantEpochMilli: Long,
        ): WorkflowBusinessCalendarCommand = WorkflowBusinessCalendarCommand(
            callContext,
            requestId,
            instanceId,
            subject,
            calendar,
            WorkflowBusinessTimeOperation.NEXT_WORKING_INSTANT,
            instantEpochMilli,
            null,
        )

        @JvmStatic
        fun addWorkingDuration(
            callContext: WorkflowTrustedCallContext,
            requestId: String,
            instanceId: String,
            subject: WorkflowSubjectSnapshot,
            calendar: WorkflowBusinessCalendarRef,
            instantEpochMilli: Long,
            workingDurationMillis: Long,
        ): WorkflowBusinessCalendarCommand = WorkflowBusinessCalendarCommand(
            callContext,
            requestId,
            instanceId,
            subject,
            calendar,
            WorkflowBusinessTimeOperation.ADD_WORKING_DURATION,
            instantEpochMilli,
            workingDurationMillis,
        )
    }
}

/** Receipt-bound value safe to persist as timer/SLA scheduling evidence. */
class WorkflowBusinessCalendarEvaluation private constructor(
    val calendar: WorkflowBusinessCalendarRef,
    val operation: WorkflowBusinessTimeOperation,
    val value: WorkflowBusinessTimeValue,
    val providerReceipt: WorkflowProviderReceipt,
    authorizationDigest: String,
) {
    val authorizationDigest: String = WorkflowRuntimeSupport.sha256(
        authorizationDigest,
        "Workflow calendar authorization digest is invalid.",
    )
    val evaluationDigest: String

    init {
        require(value.calendarRevision == calendar.version) {
            "Workflow calendar result revision does not match the selected calendar."
        }
        require(providerReceipt.outcome == WorkflowProviderOutcome.SUCCESS &&
            providerReceipt.resultDigest == value.valueDigest
        ) { "Workflow calendar provider receipt does not match its value." }
        evaluationDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-calendar-evaluation-v1")
            .text(calendar.providerId)
            .text(calendar.calendarId)
            .text(calendar.version)
            .text(calendar.digest)
            .text(operation.code)
            .text(value.valueDigest)
            .text(providerReceipt.receiptDigest)
            .text(this.authorizationDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowBusinessCalendarEvaluation(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            calendar: WorkflowBusinessCalendarRef,
            operation: WorkflowBusinessTimeOperation,
            value: WorkflowBusinessTimeValue,
            providerReceipt: WorkflowProviderReceipt,
            authorizationDigest: String,
        ): WorkflowBusinessCalendarEvaluation = WorkflowBusinessCalendarEvaluation(
            calendar,
            operation,
            value,
            providerReceipt,
            authorizationDigest,
        )
    }
}

class WorkflowBusinessCalendarRuntimeResult private constructor(
    val code: WorkflowBusinessCalendarResultCode,
    val evaluation: WorkflowBusinessCalendarEvaluation?,
    val diagnostic: WorkflowBusinessCalendarDiagnostic?,
) {
    init {
        require((code == WorkflowBusinessCalendarResultCode.SUCCEEDED) == (evaluation != null)) {
            "Workflow calendar result and value are inconsistent."
        }
        require((evaluation == null) == (diagnostic != null)) {
            "Workflow calendar result requires exactly one payload shape."
        }
    }

    override fun toString(): String = "WorkflowBusinessCalendarRuntimeResult(code=${code.code})"

    companion object {
        @JvmStatic
        fun success(evaluation: WorkflowBusinessCalendarEvaluation): WorkflowBusinessCalendarRuntimeResult =
            WorkflowBusinessCalendarRuntimeResult(WorkflowBusinessCalendarResultCode.SUCCEEDED, evaluation, null)

        @JvmStatic
        fun failed(
            code: WorkflowBusinessCalendarResultCode,
            diagnostic: WorkflowBusinessCalendarDiagnostic,
        ): WorkflowBusinessCalendarRuntimeResult = WorkflowBusinessCalendarRuntimeResult(code, null, diagnostic)
    }
}

/**
 * Authorization-first, provider-neutral business-calendar orchestration.
 * Authorization is checked again after the external call so a mid-call revocation discards the result.
 */
class WorkflowBusinessCalendarRuntime(
    private val authorizationPort: WorkflowRuntimeAuthorizationPort,
    private val calendarProvider: WorkflowBusinessCalendar,
    private val profile: WorkflowBusinessCalendarProfile,
    private val clock: WorkflowWorkerClock,
) {
    fun evaluate(command: WorkflowBusinessCalendarCommand): WorkflowBusinessCalendarRuntimeResult {
        if (command.calendar.providerId != profile.providerId) {
            return failure(WorkflowBusinessCalendarResultCode.INVALID, "calendar-profile-mismatch", false)
        }
        val startedAt = safeTime()
            ?: return failure(WorkflowBusinessCalendarResultCode.PROVIDER_UNAVAILABLE, "clock-unavailable", true)
        val deadline = safeDeadline(startedAt, profile.callWindowMillis)
            ?: return failure(WorkflowBusinessCalendarResultCode.INVALID, "clock-overflow", false)
        val authorizationRequest = try {
            WorkflowRuntimeAuthorizationRequest.of(
                command.callContext,
                WorkflowRuntimeAction.EVALUATE_BUSINESS_TIME,
                command.instanceId,
                null,
                null,
                command.subject,
                command.requestDigest,
                startedAt,
            )
        } catch (_: RuntimeException) {
            return failure(WorkflowBusinessCalendarResultCode.INVALID, "authorization-request-invalid", false)
        }
        val initialAuthorization = authorize(authorizationRequest, startedAt)
            ?: return failure(WorkflowBusinessCalendarResultCode.AUTHORIZATION_DENIED, "authorization-denied", false)

        val providerContext = try {
            WorkflowProviderCallContext.of(
                command.requestId,
                command.callContext.tenantId,
                profile.providerId,
                profile.providerRevision,
                "business-time-${command.operation.code}",
                startedAt,
                deadline,
                profile.maximumInputBytes,
                profile.maximumOutputBytes,
                1,
            )
        } catch (_: RuntimeException) {
            return failure(WorkflowBusinessCalendarResultCode.INVALID, "provider-context-invalid", false)
        }
        val providerRequest = try {
            when (command.operation) {
                WorkflowBusinessTimeOperation.IS_WORKING_INSTANT -> WorkflowBusinessTimeRequest.isWorkingInstant(
                    providerContext,
                    command.calendar,
                    command.instantEpochMilli,
                )
                WorkflowBusinessTimeOperation.NEXT_WORKING_INSTANT -> WorkflowBusinessTimeRequest.nextWorkingInstant(
                    providerContext,
                    command.calendar,
                    command.instantEpochMilli,
                )
                WorkflowBusinessTimeOperation.ADD_WORKING_DURATION -> WorkflowBusinessTimeRequest.addWorkingDuration(
                    providerContext,
                    command.calendar,
                    command.instantEpochMilli,
                    command.workingDurationMillis!!,
                )
                else -> return failure(WorkflowBusinessCalendarResultCode.INVALID, "operation-unsupported", false)
            }
        } catch (_: RuntimeException) {
            return failure(WorkflowBusinessCalendarResultCode.INVALID, "provider-request-invalid", false)
        }
        val providerResult = try {
            await(calendarProvider.evaluate(providerRequest), deadline)
        } catch (failure: Exception) {
            restoreInterrupt(failure)
            return failure(WorkflowBusinessCalendarResultCode.PROVIDER_UNAVAILABLE, "provider-unavailable", true)
        }
        val completedAt = safeTime()
            ?: return failure(WorkflowBusinessCalendarResultCode.PROVIDER_UNAVAILABLE, "clock-unavailable", true)
        val finalAuthorization = authorize(authorizationRequest, completedAt)
            ?: return failure(WorkflowBusinessCalendarResultCode.AUTHORIZATION_DENIED, "authorization-revoked", false)
        if (!receiptMatches(providerResult, providerRequest, providerContext, completedAt)) {
            return failure(WorkflowBusinessCalendarResultCode.RECEIPT_INVALID, "provider-receipt-invalid", false)
        }
        if (providerResult.receipt.outcome != WorkflowProviderOutcome.SUCCESS) {
            val providerFailure = providerResult.receipt.failure
            val retryable = providerFailure?.retryable ?: false
            return failure(
                if (retryable || providerResult.receipt.outcome == WorkflowProviderOutcome.UNAVAILABLE) {
                    WorkflowBusinessCalendarResultCode.PROVIDER_UNAVAILABLE
                } else {
                    WorkflowBusinessCalendarResultCode.PROVIDER_REJECTED
                },
                providerFailure?.code ?: "provider-rejected",
                retryable,
            )
        }
        val value = providerResult.value
            ?: return failure(WorkflowBusinessCalendarResultCode.RECEIPT_INVALID, "provider-value-missing", false)
        val evaluation = try {
            WorkflowBusinessCalendarEvaluation.of(
                command.calendar,
                command.operation,
                value,
                providerResult.receipt,
                finalAuthorization.authorityDigest,
            )
        } catch (_: RuntimeException) {
            return failure(WorkflowBusinessCalendarResultCode.RECEIPT_INVALID, "provider-value-invalid", false)
        }
        // Bind the returned evidence to a currently-authorized decision, while accepting a legitimate
        // authority revision change only when the fresh decision still authorizes the exact request.
        require(initialAuthorization.action == finalAuthorization.action)
        return WorkflowBusinessCalendarRuntimeResult.success(evaluation)
    }

    private fun authorize(
        request: WorkflowRuntimeAuthorizationRequest,
        now: Long,
    ): WorkflowRuntimeAuthorizationDecision? {
        val decision = try {
            authorizationPort.authorize(request)
        } catch (_: RuntimeException) {
            return null
        }
        return decision.takeIf {
            it.status == WorkflowRuntimeAuthorizationStatus.AUTHORIZED && it.matches(request, now)
        }
    }

    private fun receiptMatches(
        result: WorkflowBusinessTimeResult,
        request: WorkflowBusinessTimeRequest,
        context: WorkflowProviderCallContext,
        now: Long,
    ): Boolean {
        val receipt = result.receipt
        return receipt.contextDigest == context.contextDigest &&
            receipt.requestId == context.requestId &&
            receipt.tenantId == context.tenantId &&
            receipt.providerId == context.providerId &&
            receipt.providerRevision == context.providerRevision &&
            receipt.purpose == context.purpose &&
            receipt.requestDigest == request.requestDigest &&
            receipt.completedAtEpochMilli in context.requestedAtEpochMilli..now &&
            receipt.expiresAtEpochMilli in now..context.deadlineEpochMilli &&
            ((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (result.value != null))
    }

    private fun <T> await(stage: CompletionStage<T>?, deadline: Long): T {
        val safeStage = requireNotNull(stage) { "Workflow calendar provider returned no completion stage." }
        val remaining = deadline - clock.currentTimeMillis()
        require(remaining > 0L) { "Workflow calendar provider deadline expired." }
        return requireNotNull(safeStage.toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS)) {
            "Workflow calendar provider returned no result."
        }
    }

    private fun safeTime(): Long? = try {
        clock.currentTimeMillis().takeIf { it >= 0L }
    } catch (_: RuntimeException) {
        null
    }

    private fun safeDeadline(now: Long, window: Long): Long? =
        if (now <= Long.MAX_VALUE - window) now + window else null

    private fun restoreInterrupt(failure: Exception) {
        if (failure is InterruptedException || failure.cause is InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun failure(
        code: WorkflowBusinessCalendarResultCode,
        diagnostic: String,
        retryable: Boolean,
    ): WorkflowBusinessCalendarRuntimeResult = WorkflowBusinessCalendarRuntimeResult.failed(
        code,
        WorkflowBusinessCalendarDiagnostic.of(diagnostic, retryable),
    )
}
