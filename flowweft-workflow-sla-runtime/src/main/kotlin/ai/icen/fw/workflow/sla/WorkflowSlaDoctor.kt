package ai.icen.fw.workflow.sla

import ai.icen.fw.workflow.runtime.WorkflowRuntimeAction
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationPort
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationRequest
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationStatus
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext

class WorkflowSlaDoctorStatus private constructor(code: String) {
    val code: String = slaMachineCode(code, "doctor status")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowSlaDoctorStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowSlaDoctorStatus(<redacted>)"

    companion object {
        @JvmField val HEALTHY = WorkflowSlaDoctorStatus("healthy")
        @JvmField val DEGRADED = WorkflowSlaDoctorStatus("degraded")
        @JvmField val CRITICAL = WorkflowSlaDoctorStatus("critical")
        @JvmField val NOT_AUTHORIZED = WorkflowSlaDoctorStatus("not-authorized")
        @JvmField val UNAVAILABLE = WorkflowSlaDoctorStatus("unavailable")
    }
}

class WorkflowSlaLagBucket private constructor(code: String) {
    val code: String = slaMachineCode(code, "lag bucket")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowSlaLagBucket && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowSlaLagBucket(<redacted>)"

    companion object {
        @JvmField val NONE = WorkflowSlaLagBucket("none")
        @JvmField val UNDER_ONE_MINUTE = WorkflowSlaLagBucket("under-one-minute")
        @JvmField val ONE_TO_FIVE_MINUTES = WorkflowSlaLagBucket("one-to-five-minutes")
        @JvmField val FIVE_MINUTES_TO_ONE_HOUR = WorkflowSlaLagBucket("five-minutes-to-one-hour")
        @JvmField val OVER_ONE_HOUR = WorkflowSlaLagBucket("over-one-hour")

        @JvmStatic
        fun forAge(ageMillis: Long?): WorkflowSlaLagBucket = when {
            ageMillis == null -> NONE
            ageMillis < 60_000L -> UNDER_ONE_MINUTE
            ageMillis < 300_000L -> ONE_TO_FIVE_MINUTES
            ageMillis < 3_600_000L -> FIVE_MINUTES_TO_ONE_HOUR
            else -> OVER_ONE_HOUR
        }
    }
}

class WorkflowSlaDoctorFinding private constructor(
    code: String,
    val count: Long,
    val lagBucket: WorkflowSlaLagBucket,
    repairAction: String,
) {
    val code: String = slaMachineCode(code, "doctor finding")
    val repairAction: String = slaMachineCode(repairAction, "doctor repair action")

    init {
        require(count >= 0L) { "Workflow SLA Doctor count is invalid." }
    }

    override fun toString(): String = "WorkflowSlaDoctorFinding(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            code: String,
            count: Long,
            lagBucket: WorkflowSlaLagBucket,
            repairAction: String,
        ): WorkflowSlaDoctorFinding = WorkflowSlaDoctorFinding(code, count, lagBucket, repairAction)
    }
}

/** Aggregate-only report. It never returns tenant, schedule, task, principal or provider ids. */
class WorkflowSlaDoctorReport private constructor(
    val status: WorkflowSlaDoctorStatus,
    findings: Collection<WorkflowSlaDoctorFinding>,
    val observedAt: Long,
) {
    val findings: List<WorkflowSlaDoctorFinding> = WorkflowSlaSupport.immutable(
        findings,
        16,
        "Workflow SLA Doctor findings are invalid or exceed the limit.",
    )
    val reportDigest: String

    init {
        require(observedAt >= 0L) { "Workflow SLA Doctor observation time is invalid." }
        val writer = WorkflowSlaSupport.digest("flowweft-workflow-sla-doctor-report-v1")
            .text(status.code)
            .integer(this.findings.size)
        this.findings.forEach { finding ->
            writer.text(finding.code)
                .longValue(finding.count)
                .text(finding.lagBucket.code)
                .text(finding.repairAction)
        }
        reportDigest = writer.longValue(observedAt).finish()
    }

    override fun toString(): String = "WorkflowSlaDoctorReport(status=${status.code})"

    companion object {
        @JvmStatic
        fun of(
            status: WorkflowSlaDoctorStatus,
            findings: Collection<WorkflowSlaDoctorFinding>,
            observedAt: Long,
        ): WorkflowSlaDoctorReport = WorkflowSlaDoctorReport(status, findings, observedAt)
    }
}

class WorkflowSlaDoctor(
    private val store: WorkflowSlaDurableStore,
    private val authorizationPort: WorkflowRuntimeAuthorizationPort,
) {
    fun inspect(context: WorkflowTrustedCallContext, observedAt: Long): WorkflowSlaDoctorReport {
        if (observedAt < 0L) return unavailable(0L)
        val requestDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-doctor-request-v1")
            .text(context.contextDigest)
            .longValue(observedAt)
            .finish()
        val request = try {
            WorkflowRuntimeAuthorizationRequest.of(
                context,
                ACTION_INSPECT,
                "sla-doctor",
                null,
                null,
                null,
                requestDigest,
                observedAt,
            )
        } catch (_: RuntimeException) {
            return unavailable(observedAt)
        }
        val authorization = try {
            authorizationPort.authorize(request)
        } catch (_: RuntimeException) {
            return unavailable(observedAt)
        }
        if (!authorization.matches(request, observedAt) ||
            authorization.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED
        ) return WorkflowSlaDoctorReport.of(
            WorkflowSlaDoctorStatus.NOT_AUTHORIZED,
            emptyList(),
            observedAt,
        )
        val snapshot = try {
            store.diagnosticSnapshot(context.tenantId, observedAt)
        } catch (_: RuntimeException) {
            return unavailable(observedAt)
        }
        if (snapshot.observedAt != observedAt) return unavailable(observedAt)
        val findings = ArrayList<WorkflowSlaDoctorFinding>()
        val age = snapshot.oldestDueAt?.let { due -> observedAt - due }
        if (snapshot.dueMilestones > 0L) {
            findings += WorkflowSlaDoctorFinding.of(
                "sla-due-backlog",
                snapshot.dueMilestones,
                WorkflowSlaLagBucket.forAge(age),
                "inspect-sla-workers",
            )
        }
        if (snapshot.expiredLeases > 0L) {
            findings += WorkflowSlaDoctorFinding.of(
                "sla-expired-leases",
                snapshot.expiredLeases,
                WorkflowSlaLagBucket.NONE,
                "reclaim-expired-sla-leases",
            )
        }
        if (snapshot.outcomeUnknown > 0L) {
            findings += WorkflowSlaDoctorFinding.of(
                "sla-outcome-unknown",
                snapshot.outcomeUnknown,
                WorkflowSlaLagBucket.NONE,
                "reconcile-sla-action-outcomes",
            )
        }
        if (snapshot.terminalFailures > 0L) {
            findings += WorkflowSlaDoctorFinding.of(
                "sla-terminal-failures",
                snapshot.terminalFailures,
                WorkflowSlaLagBucket.NONE,
                "inspect-sla-action-profile",
            )
        }
        val status = when {
            snapshot.outcomeUnknown > 0L || snapshot.terminalFailures > 0L -> WorkflowSlaDoctorStatus.CRITICAL
            snapshot.expiredLeases > 0L || WorkflowSlaLagBucket.forAge(age) == WorkflowSlaLagBucket.OVER_ONE_HOUR ->
                WorkflowSlaDoctorStatus.DEGRADED
            else -> WorkflowSlaDoctorStatus.HEALTHY
        }
        return WorkflowSlaDoctorReport.of(status, findings, observedAt)
    }

    private fun unavailable(observedAt: Long): WorkflowSlaDoctorReport = WorkflowSlaDoctorReport.of(
        WorkflowSlaDoctorStatus.UNAVAILABLE,
        listOf(
            WorkflowSlaDoctorFinding.of(
                "sla-store-unavailable",
                0L,
                WorkflowSlaLagBucket.NONE,
                "restore-sla-store-connectivity",
            ),
        ),
        observedAt,
    )

    companion object {
        @JvmField val ACTION_INSPECT = WorkflowRuntimeAction.of("inspect-sla-doctor")
    }
}
