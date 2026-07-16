package ai.icen.fw.reliability.runtime

import ai.icen.fw.reliability.api.ReliabilityAlertSeverity
import ai.icen.fw.reliability.api.ReliabilityBurnRateAlert
import ai.icen.fw.reliability.api.ReliabilityBurnRatePolicy
import ai.icen.fw.reliability.api.ReliabilityErrorBudgetEvaluation
import ai.icen.fw.reliability.api.ReliabilityPurpose
import ai.icen.fw.reliability.api.ReliabilitySliObservation
import ai.icen.fw.reliability.api.ReliabilitySloEvaluationRequest
import ai.icen.fw.reliability.api.ReliabilitySloObjective

class ReliabilitySloPolicySnapshot private constructor(
    val objective: ReliabilitySloObjective,
    val burnRatePolicy: ReliabilityBurnRatePolicy,
    sourceRevision: String,
    sourceDigest: String,
    val observedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val sourceRevision: String = ReliabilityRuntimeSupport.text(
        sourceRevision, ReliabilityRuntimeSupport.MAX_REVISION_BYTES, "Reliability SLO policy revision is invalid.",
    )
    val sourceDigest: String = ReliabilityRuntimeSupport.sha256(
        sourceDigest, "Reliability SLO policy source digest is invalid.",
    )
    val policyBindingDigest: String

    init {
        require(burnRatePolicy.objectiveDigest == objective.objectiveDigest &&
            observedAtEpochMilli >= 0L && expiresAtEpochMilli > observedAtEpochMilli
        ) { "Reliability SLO policy snapshot is inconsistent." }
        policyBindingDigest = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-slo-policy-binding-v1")
            .text(objective.objectiveDigest)
            .text(burnRatePolicy.policyDigest)
            .text(this.sourceRevision)
            .text(this.sourceDigest)
            .finish()
    }

    fun isFreshAt(atEpochMilli: Long): Boolean =
        observedAtEpochMilli <= atEpochMilli && atEpochMilli < expiresAtEpochMilli

    companion object {
        @JvmStatic
        fun of(
            objective: ReliabilitySloObjective,
            burnRatePolicy: ReliabilityBurnRatePolicy,
            sourceRevision: String,
            sourceDigest: String,
            observedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): ReliabilitySloPolicySnapshot = ReliabilitySloPolicySnapshot(
            objective,
            burnRatePolicy,
            sourceRevision,
            sourceDigest,
            observedAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

class ReliabilitySloPolicyRequest private constructor(
    val context: ai.icen.fw.reliability.api.ReliabilityCallContext,
    scheduleId: String,
) {
    val scheduleId: String = ReliabilityRuntimeSupport.opaque(
        scheduleId, "Reliability SLO schedule id is invalid.",
    )
    val requestDigest: String = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-slo-policy-request-v1")
        .text(context.contextDigest)
        .text(this.scheduleId)
        .finish()

    companion object {
        @JvmStatic
        fun of(
            context: ai.icen.fw.reliability.api.ReliabilityCallContext,
            scheduleId: String,
        ): ReliabilitySloPolicyRequest = ReliabilitySloPolicyRequest(context, scheduleId)
    }
}

fun interface ReliabilitySloPolicySource {
    fun load(request: ReliabilitySloPolicyRequest): ReliabilitySloPolicySnapshot
}

class ReliabilitySliObservationRequest private constructor(
    val context: ai.icen.fw.reliability.api.ReliabilityCallContext,
    val objective: ReliabilitySloObjective,
    val windowStartEpochMilli: Long,
    val windowEndEpochMilli: Long,
    val observedAtEpochMilli: Long,
) {
    val requestDigest: String

    init {
        require(context.purpose == ReliabilityPurpose.EVALUATE_SLO && context.resource == objective.resource &&
            windowStartEpochMilli >= objective.effectiveFromEpochMilli &&
            windowEndEpochMilli - windowStartEpochMilli == objective.windowMillis &&
            observedAtEpochMilli >= windowEndEpochMilli && context.isFreshAt(observedAtEpochMilli)
        ) { "Reliability SLI observation request is outside the exact authorized window." }
        requestDigest = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-sli-observation-request-v1")
            .text(context.contextDigest)
            .text(objective.objectiveDigest)
            .longValue(windowStartEpochMilli)
            .longValue(windowEndEpochMilli)
            .longValue(observedAtEpochMilli)
            .finish()
    }

    companion object {
        @JvmStatic
        fun of(
            context: ai.icen.fw.reliability.api.ReliabilityCallContext,
            objective: ReliabilitySloObjective,
            windowStartEpochMilli: Long,
            windowEndEpochMilli: Long,
            observedAtEpochMilli: Long,
        ): ReliabilitySliObservationRequest = ReliabilitySliObservationRequest(
            context, objective, windowStartEpochMilli, windowEndEpochMilli, observedAtEpochMilli,
        )
    }
}

fun interface ReliabilitySliObservationProvider {
    /** Null means explicit missing data and therefore fails closed. */
    fun observe(request: ReliabilitySliObservationRequest): ReliabilitySliObservation?
}

class ReliabilitySloEvaluationRecord private constructor(
    val evaluation: ReliabilityErrorBudgetEvaluation,
    val alert: ReliabilityBurnRateAlert,
) {
    val recordDigest: String

    init {
        require(alert.evaluationDigest == evaluation.evaluationDigest) {
            "Reliability SLO alert does not bind its exact evaluation."
        }
        recordDigest = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-slo-evaluation-record-v1")
            .text(evaluation.evaluationDigest)
            .text(alert.alertDigest)
            .finish()
    }

    companion object {
        @JvmStatic
        fun of(
            evaluation: ReliabilityErrorBudgetEvaluation,
            alert: ReliabilityBurnRateAlert,
        ): ReliabilitySloEvaluationRecord = ReliabilitySloEvaluationRecord(evaluation, alert)
    }
}

class ReliabilitySloSchedule private constructor(
    scheduleId: String,
    tenantId: String,
    policyBindingDigest: String,
    val objectiveResource: ai.icen.fw.reliability.api.ReliabilityResourceRef,
    val cadenceMillis: Long,
    val nextEvaluationAtEpochMilli: Long,
    val version: Long,
    val lease: ReliabilityRunLease?,
    val lastRecord: ReliabilitySloEvaluationRecord?,
    val updatedAtEpochMilli: Long,
) {
    val scheduleId: String = ReliabilityRuntimeSupport.opaque(scheduleId, "Reliability SLO schedule id is invalid.")
    val tenantId: String = ReliabilityRuntimeSupport.text(
        tenantId, ReliabilityRuntimeSupport.MAX_ID_BYTES, "Reliability SLO schedule tenant is invalid.",
    )
    val policyBindingDigest: String = ReliabilityRuntimeSupport.sha256(
        policyBindingDigest, "Reliability SLO policy binding is invalid.",
    )
    val stateDigest: String

    init {
        require(cadenceMillis in MIN_CADENCE_MILLIS..MAX_CADENCE_MILLIS &&
            nextEvaluationAtEpochMilli >= 0L && version >= 0L && updatedAtEpochMilli >= 0L
        ) { "Reliability SLO schedule cadence or state is invalid." }
        stateDigest = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-slo-schedule-v1")
            .text(this.scheduleId)
            .text(this.tenantId)
            .text(this.policyBindingDigest)
            .text(objectiveResource.referenceDigest)
            .longValue(cadenceMillis)
            .longValue(nextEvaluationAtEpochMilli)
            .longValue(version)
            .optionalText(lease?.leaseDigest)
            .optionalText(lastRecord?.recordDigest)
            .longValue(updatedAtEpochMilli)
            .finish()
    }

    companion object {
        const val MIN_CADENCE_MILLIS: Long = 10_000L
        const val MAX_CADENCE_MILLIS: Long = 24L * 60L * 60L * 1000L

        @JvmStatic
        fun of(
            scheduleId: String,
            tenantId: String,
            policyBindingDigest: String,
            objectiveResource: ai.icen.fw.reliability.api.ReliabilityResourceRef,
            cadenceMillis: Long,
            nextEvaluationAtEpochMilli: Long,
            version: Long,
            lease: ReliabilityRunLease?,
            lastRecord: ReliabilitySloEvaluationRecord?,
            updatedAtEpochMilli: Long,
        ): ReliabilitySloSchedule = ReliabilitySloSchedule(
            scheduleId,
            tenantId,
            policyBindingDigest,
            objectiveResource,
            cadenceMillis,
            nextEvaluationAtEpochMilli,
            version,
            lease,
            lastRecord,
            updatedAtEpochMilli,
        )

        @JvmStatic
        fun claimed(
            current: ReliabilitySloSchedule,
            ownerId: String,
            nowEpochMilli: Long,
            leaseUntilEpochMilli: Long,
            fencingToken: Long,
        ): ReliabilitySloSchedule = ReliabilitySloSchedule(
            current.scheduleId,
            current.tenantId,
            current.policyBindingDigest,
            current.objectiveResource,
            current.cadenceMillis,
            current.nextEvaluationAtEpochMilli,
            current.version + 1L,
            ReliabilityRunLease.of(ownerId, fencingToken, nowEpochMilli, leaseUntilEpochMilli),
            current.lastRecord,
            nowEpochMilli,
        )

        internal fun evaluated(
            current: ReliabilitySloSchedule,
            record: ReliabilitySloEvaluationRecord,
            evaluatedAtEpochMilli: Long,
        ): ReliabilitySloSchedule {
            require(current.lease != null && evaluatedAtEpochMilli >= current.nextEvaluationAtEpochMilli) {
                "Reliability SLO evaluation requires a due claimed schedule."
            }
            val next = if (Long.MAX_VALUE - evaluatedAtEpochMilli < current.cadenceMillis) {
                Long.MAX_VALUE
            } else {
                evaluatedAtEpochMilli + current.cadenceMillis
            }
            return ReliabilitySloSchedule(
                current.scheduleId,
                current.tenantId,
                current.policyBindingDigest,
                current.objectiveResource,
                current.cadenceMillis,
                next,
                current.version + 1L,
                current.lease,
                record,
                evaluatedAtEpochMilli,
            )
        }
    }
}

interface ReliabilitySloScheduleRepository {
    fun load(tenantId: String, scheduleId: String): ReliabilitySloSchedule?

    fun claimDue(
        tenantId: String,
        scheduleId: String,
        expectedVersion: Long,
        ownerId: String,
        nowEpochMilli: Long,
        leaseUntilEpochMilli: Long,
    ): ReliabilitySloSchedule?

    /** Stores the exact schedule CAS transition and outbox in one local transaction. */
    fun compareAndSet(
        tenantId: String,
        scheduleId: String,
        expectedVersion: Long,
        expectedFencingToken: Long,
        candidate: ReliabilitySloSchedule,
        outbox: ReliabilityOutboxRecord,
    ): ReliabilityStoreCode
}

enum class ReliabilitySloWorkerStatus { EVALUATED, ALERTED, NOT_DUE, CONFLICT, NOT_FOUND, FAILED }

class ReliabilitySloWorkerResult private constructor(
    val status: ReliabilitySloWorkerStatus,
    val schedule: ReliabilitySloSchedule?,
) {
    companion object {
        @JvmStatic
        fun of(status: ReliabilitySloWorkerStatus, schedule: ReliabilitySloSchedule?): ReliabilitySloWorkerResult =
            ReliabilitySloWorkerResult(status, schedule)
    }
}

class ReliabilitySloWorker(
    private val calls: ReliabilityAuthorizedCallFactory,
    private val identifiers: ReliabilityRuntimeIdPort,
    private val clock: ReliabilityRuntimeClock,
    private val policies: ReliabilitySloPolicySource,
    private val observations: ReliabilitySliObservationProvider,
    private val repository: ReliabilitySloScheduleRepository,
    private val metrics: ReliabilityRuntimeMetrics = ReliabilityRuntimeMetrics.NOOP,
) {
    fun evaluateOne(
        invocation: ReliabilityTrustedInvocation,
        scheduleId: String,
        ownerId: String,
        leaseMillis: Long,
    ): ReliabilitySloWorkerResult {
        val exactScheduleId = try {
            ReliabilityRuntimeSupport.opaque(scheduleId, "Reliability SLO schedule id is invalid.")
        } catch (_: RuntimeException) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.FAILED, null)
        }
        val exactOwnerId = try {
            ReliabilityRuntimeSupport.opaque(ownerId, "Reliability SLO worker owner is invalid.")
        } catch (_: RuntimeException) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.FAILED, null)
        }
        if (leaseMillis !in 1L..ReliabilityWorkerCommand.MAX_LEASE_MILLIS) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.FAILED, null)
        }
        if (invocation.purpose != ReliabilityPurpose.EVALUATE_SLO ||
            invocation.action != ai.icen.fw.reliability.api.ReliabilityAction.EVALUATE_SLO
        ) return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.FAILED, null)
        val loaded = try {
            repository.load(invocation.tenantId, exactScheduleId)
        } catch (_: RuntimeException) {
            null
        }
            ?: return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.NOT_FOUND, null)
        if (loaded.objectiveResource != invocation.resource) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.NOT_FOUND, null)
        }
        val now = try {
            clock.nowEpochMilli()
        } catch (_: RuntimeException) {
            invocation.requestedAtEpochMilli
        }.coerceAtLeast(invocation.requestedAtEpochMilli).coerceAtLeast(loaded.updatedAtEpochMilli)
        if (now >= invocation.deadlineEpochMilli) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.FAILED, loaded)
        }
        if (now < loaded.nextEvaluationAtEpochMilli) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.NOT_DUE, loaded)
        }
        val claimed = try {
            repository.claimDue(
                loaded.tenantId,
                loaded.scheduleId,
                loaded.version,
                exactOwnerId,
                now,
                safeAdd(now, leaseMillis),
            )
        } catch (_: RuntimeException) {
            null
        } ?: return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.CONFLICT, loaded)
        val lease = claimed.lease
            ?: return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.CONFLICT, loaded)
        if (claimed.tenantId != loaded.tenantId || claimed.scheduleId != loaded.scheduleId ||
            claimed.objectiveResource != loaded.objectiveResource ||
            claimed.policyBindingDigest != loaded.policyBindingDigest ||
            claimed.cadenceMillis != loaded.cadenceMillis ||
            claimed.nextEvaluationAtEpochMilli != loaded.nextEvaluationAtEpochMilli ||
            claimed.version <= loaded.version || !lease.isCurrent(exactOwnerId, now)
        ) return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.CONFLICT, loaded)
        val provisional = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-slo-evaluate-arguments-v1")
            .text(claimed.policyBindingDigest)
            .longValue(now)
            .finish()
        val context = try {
            calls.create(invocation, "evaluate-slo-schedule", provisional)
        } catch (_: RuntimeException) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.FAILED, claimed)
        }
        val policy = try {
            policies.load(ReliabilitySloPolicyRequest.of(context, claimed.scheduleId))
        } catch (_: RuntimeException) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.FAILED, claimed)
        }
        if (policy.policyBindingDigest != claimed.policyBindingDigest || !policy.isFreshAt(now) ||
            policy.objective.resource != claimed.objectiveResource
        ) return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.FAILED, claimed)
        val windowEnd = now
        if (windowEnd < policy.objective.windowMillis ||
            windowEnd - policy.objective.windowMillis < policy.objective.effectiveFromEpochMilli
        ) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.FAILED, claimed)
        }
        val windowStart = windowEnd - policy.objective.windowMillis
        val observationRequest = try {
            ReliabilitySliObservationRequest.of(context, policy.objective, windowStart, windowEnd, now)
        } catch (_: RuntimeException) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.FAILED, claimed)
        }
        // claimDue transaction has returned before the observation provider is called.
        val observation = try {
            observations.observe(observationRequest)
        } catch (_: RuntimeException) {
            null
        }
        val evaluated = try {
            val evaluationRequest = ReliabilitySloEvaluationRequest.of(
                context, policy.objective, observation, windowStart, windowEnd, now,
            )
            val evaluation = ReliabilityErrorBudgetEvaluation.evaluate(evaluationRequest)
            val alert = ReliabilityBurnRateAlert.evaluate(policy.burnRatePolicy, evaluation, now)
            Triple(evaluation, alert, ReliabilitySloEvaluationRecord.of(evaluation, alert))
        } catch (_: RuntimeException) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.FAILED, claimed)
        }
        val alert = evaluated.second
        val candidate = try {
            ReliabilitySloSchedule.evaluated(claimed, evaluated.third, now)
        } catch (_: RuntimeException) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.FAILED, claimed)
        }
        val type = if (alert.severity == ReliabilityAlertSeverity.NONE) {
            ReliabilityOutboxType.SLO_EVALUATED
        } else {
            ReliabilityOutboxType.SLO_ALERTED
        }
        val stored = try {
            val outbox = outbox(candidate, type, now)
            repository.compareAndSet(
                candidate.tenantId,
                candidate.scheduleId,
                claimed.version,
                lease.fencingToken,
                candidate,
                outbox,
            )
        } catch (_: RuntimeException) {
            ReliabilityStoreCode.OUTCOME_UNKNOWN
        }
        if (stored != ReliabilityStoreCode.STORED) {
            return ReliabilitySloWorkerResult.of(ReliabilitySloWorkerStatus.CONFLICT, claimed)
        }
        try {
            metrics.record(ReliabilityRuntimeMetric.of(ReliabilityRuntimeMetricCode.SLO_EVALUATED))
            if (alert.severity != ReliabilityAlertSeverity.NONE) {
                metrics.record(ReliabilityRuntimeMetric.of(ReliabilityRuntimeMetricCode.SLO_ALERTED))
            }
        } catch (_: RuntimeException) {
            // Metrics never affect scheduling.
        }
        return ReliabilitySloWorkerResult.of(
            if (alert.severity == ReliabilityAlertSeverity.NONE) {
                ReliabilitySloWorkerStatus.EVALUATED
            } else {
                ReliabilitySloWorkerStatus.ALERTED
            },
            candidate,
        )
    }

    private fun outbox(
        schedule: ReliabilitySloSchedule,
        type: ReliabilityOutboxType,
        now: Long,
    ): ReliabilityOutboxRecord {
        val seed = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-slo-outbox-seed-v1")
            .text(schedule.stateDigest).text(type.name).finish()
        val id = ReliabilityRuntimeSupport.opaque(
            identifiers.nextId(
                ReliabilityRuntimeIdRequest.of(
                    ReliabilityRuntimeIdKind.OUTBOX,
                    schedule.tenantId,
                    seed,
                    (schedule.version % Int.MAX_VALUE.toLong()).toInt(),
                ),
            ),
            "Reliability runtime id provider returned an invalid SLO outbox id.",
        )
        return ReliabilityOutboxRecord.forAggregate(
            id,
            type,
            schedule.tenantId,
            schedule.scheduleId,
            schedule.stateDigest,
            schedule.version,
            now,
        )
    }

    private fun safeAdd(value: Long, delta: Long): Long {
        require(delta in 1L..ReliabilityWorkerCommand.MAX_LEASE_MILLIS) {
            "Reliability SLO worker lease is invalid."
        }
        return if (Long.MAX_VALUE - value < delta) Long.MAX_VALUE else value + delta
    }
}
