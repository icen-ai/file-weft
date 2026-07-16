package ai.icen.fw.governance.runtime

import ai.icen.fw.governance.api.GovernanceDeletionPlan
import ai.icen.fw.governance.api.GovernanceDeletionStage
import ai.icen.fw.governance.api.GovernanceDeletionStep
import ai.icen.fw.governance.api.GovernanceEffectiveClock
import ai.icen.fw.governance.api.GovernanceFailure
import ai.icen.fw.governance.api.GovernanceFailureClass
import ai.icen.fw.governance.api.GovernanceLegalHoldResolution
import ai.icen.fw.governance.api.GovernanceLegalHoldResolutionRequest
import ai.icen.fw.governance.api.GovernanceLegalHoldResolutionStatus
import ai.icen.fw.governance.api.GovernanceLegalHoldResolver
import ai.icen.fw.governance.api.GovernancePurpose
import ai.icen.fw.governance.api.GovernanceRetentionAssessment
import ai.icen.fw.governance.api.GovernanceRetentionEvaluationRequest
import ai.icen.fw.governance.api.GovernanceRetentionEvaluator
import ai.icen.fw.governance.api.GovernanceRetentionOutcome
import ai.icen.fw.governance.api.GovernanceVersionFence
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class GovernanceDeletionPlanCommand private constructor(
    val invocation: GovernanceTrustedInvocation,
    val fence: GovernanceVersionFence,
    val dryRun: Boolean,
) {
    val commandDigest: String

    init {
        require(invocation.purpose == GovernancePurpose.PLAN_SECURE_DELETION) {
            "Governance deletion planning command requires its exact trusted purpose."
        }
        require(invocation.resource == fence.resource) {
            "Governance deletion planning fence does not match the exact resource snapshot."
        }
        commandDigest = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-plan-command-v1")
            .text(invocation.tenantId)
            .text(invocation.principal.type)
            .text(invocation.principal.id)
            .text(invocation.resource.referenceDigest)
            .text(invocation.idempotencyKey)
            .text(fence.fenceDigest)
            .bool(dryRun)
            .finish()
    }

    override fun toString(): String = "GovernanceDeletionPlanCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            invocation: GovernanceTrustedInvocation,
            fence: GovernanceVersionFence,
            dryRun: Boolean,
        ): GovernanceDeletionPlanCommand = GovernanceDeletionPlanCommand(invocation, fence, dryRun)
    }
}

class GovernancePlanningStatus private constructor(code: String) {
    val code: String = GovernanceRuntimeSupport.code(code, "Governance planning status is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is GovernancePlanningStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernancePlanningStatus(<redacted>)"

    companion object {
        @JvmField val CREATED = GovernancePlanningStatus("created")
        @JvmField val REPLAYED = GovernancePlanningStatus("replayed")
        @JvmField val DRY_RUN = GovernancePlanningStatus("dry-run")
        @JvmField val BLOCKED = GovernancePlanningStatus("blocked")
        @JvmField val CONFLICT = GovernancePlanningStatus("conflict")
        @JvmField val FAILED = GovernancePlanningStatus("failed")
        @JvmField val STORE_OUTCOME_UNKNOWN = GovernancePlanningStatus("store-outcome-unknown")
    }
}

class GovernancePlanningResult private constructor(
    val status: GovernancePlanningStatus,
    val plan: GovernanceDeletionPlan?,
    val run: GovernanceDeletionRun?,
    val failure: GovernanceFailure?,
) {
    init {
        when (status) {
            GovernancePlanningStatus.CREATED,
            GovernancePlanningStatus.REPLAYED -> require(plan != null && run != null && failure == null) {
                "Created or replayed governance planning result requires a durable run."
            }
            GovernancePlanningStatus.DRY_RUN -> require(plan?.dryRun == true && run == null && failure == null) {
                "Governance dry-run result requires a non-executable plan only."
            }
            GovernancePlanningStatus.BLOCKED,
            GovernancePlanningStatus.FAILED -> require(plan == null && run == null && failure != null) {
                "Blocked or failed governance planning result requires value-free failure evidence."
            }
            GovernancePlanningStatus.CONFLICT,
            GovernancePlanningStatus.STORE_OUTCOME_UNKNOWN -> require(plan == null && run == null && failure == null) {
                "Governance planning conflict/store-unknown result cannot invent a plan."
            }
            else -> require(false) { "Unknown governance planning result is unsupported." }
        }
    }

    override fun toString(): String = "GovernancePlanningResult(<redacted>)"

    companion object {
        @JvmStatic fun created(run: GovernanceDeletionRun): GovernancePlanningResult =
            GovernancePlanningResult(GovernancePlanningStatus.CREATED, run.plan, run, null)
        @JvmStatic fun replayed(run: GovernanceDeletionRun): GovernancePlanningResult =
            GovernancePlanningResult(GovernancePlanningStatus.REPLAYED, run.plan, run, null)
        @JvmStatic fun dryRun(plan: GovernanceDeletionPlan): GovernancePlanningResult =
            GovernancePlanningResult(GovernancePlanningStatus.DRY_RUN, plan, null, null)
        @JvmStatic fun blocked(failure: GovernanceFailure): GovernancePlanningResult =
            GovernancePlanningResult(GovernancePlanningStatus.BLOCKED, null, null, failure)
        @JvmStatic fun failed(failure: GovernanceFailure): GovernancePlanningResult =
            GovernancePlanningResult(GovernancePlanningStatus.FAILED, null, null, failure)
        @JvmStatic fun empty(status: GovernancePlanningStatus): GovernancePlanningResult {
            require(status == GovernancePlanningStatus.CONFLICT ||
                status == GovernancePlanningStatus.STORE_OUTCOME_UNKNOWN)
            return GovernancePlanningResult(status, null, null, null)
        }
    }
}

class GovernanceEvidenceStatus private constructor(code: String) {
    val code: String = GovernanceRuntimeSupport.code(code, "Governance evidence status is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is GovernanceEvidenceStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()

    companion object {
        @JvmField val AVAILABLE = GovernanceEvidenceStatus("available")
        @JvmField val BLOCKED = GovernanceEvidenceStatus("blocked")
        @JvmField val FAILED = GovernanceEvidenceStatus("failed")
    }
}

class GovernanceDeletionEvidence private constructor(
    val status: GovernanceEvidenceStatus,
    val assessment: GovernanceRetentionAssessment?,
    val failure: GovernanceFailure?,
) {
    init {
        require((status == GovernanceEvidenceStatus.AVAILABLE) == (assessment != null)) {
            "Governance deletion evidence availability is inconsistent."
        }
        require((status == GovernanceEvidenceStatus.AVAILABLE) == (failure == null)) {
            "Governance deletion evidence failure is inconsistent."
        }
    }

    override fun toString(): String = "GovernanceDeletionEvidence(<redacted>)"

    companion object {
        @JvmStatic fun available(assessment: GovernanceRetentionAssessment): GovernanceDeletionEvidence =
            GovernanceDeletionEvidence(GovernanceEvidenceStatus.AVAILABLE, assessment, null)
        @JvmStatic fun blocked(failure: GovernanceFailure): GovernanceDeletionEvidence =
            GovernanceDeletionEvidence(GovernanceEvidenceStatus.BLOCKED, null, failure)
        @JvmStatic fun failed(failure: GovernanceFailure): GovernanceDeletionEvidence =
            GovernanceDeletionEvidence(GovernanceEvidenceStatus.FAILED, null, failure)
    }
}

/** Resolves hold first. Policy/evaluator work is skipped when a destructive call is already fail-closed. */
class GovernanceDeletionEvidenceResolver(
    private val calls: GovernanceAuthorizedCallFactory,
    private val clock: GovernanceRuntimeClockPort,
    private val holds: GovernanceLegalHoldResolver,
    private val policies: GovernanceRetentionPolicyPort,
    private val evaluator: GovernanceRetentionEvaluator,
) {
    @JvmOverloads
    fun resolve(
        invocation: GovernanceTrustedInvocation,
        fence: GovernanceVersionFence,
        allowBlockedAssessment: Boolean = false,
    ): CompletionStage<GovernanceDeletionEvidence> {
        if (invocation.resource != fence.resource) {
            return completed(
                GovernanceDeletionEvidence.failed(terminalFailure("resource-fence-mismatch")),
            )
        }
        val observedAt = try {
            clock.nowEpochMilli().also { now ->
                require(now in invocation.requestedAtEpochMilli..invocation.deadlineEpochMilli) {
                    "Governance runtime clock is outside the trusted call window."
                }
            }
        } catch (_: RuntimeException) {
            return completed(GovernanceDeletionEvidence.failed(unavailable("clock-unavailable")))
        }
        val effectiveClock = try {
            clock.observe(
                GovernanceClockObservationRequest.of(
                    invocation.tenantId, invocation.resource, observedAt, invocation.deadlineEpochMilli,
                ),
            ).also { snapshot ->
                require(snapshot.observedAtEpochMilli == observedAt &&
                    snapshot.expiresAtEpochMilli >= invocation.deadlineEpochMilli
                ) { "Governance runtime effective clock is stale or mismatched." }
            }
        } catch (_: RuntimeException) {
            return completed(GovernanceDeletionEvidence.failed(unavailable("effective-clock-unavailable")))
        }
        val holdContext = try {
            calls.create(invocation, GovernancePurpose.RESOLVE_LEGAL_HOLD, "resolve-legal-hold")
        } catch (_: RuntimeException) {
            return completed(GovernanceDeletionEvidence.failed(denied("legal-hold-authorization-denied")))
        }
        val holdRequest = GovernanceLegalHoldResolutionRequest.of(holdContext, invocation.resource, effectiveClock)
        val stage = try {
            holds.resolve(holdRequest)
        } catch (_: RuntimeException) {
            return completed(GovernanceDeletionEvidence.failed(unavailable("legal-hold-resolver-unavailable")))
        }
        return stage.thenCompose { resolution ->
            if (!validResolution(invocation, effectiveClock, resolution)) {
                return@thenCompose completed(
                    GovernanceDeletionEvidence.blocked(stale("legal-hold-resolution-mismatched")),
                )
            }
            if (!allowBlockedAssessment &&
                (resolution.status != GovernanceLegalHoldResolutionStatus.CLEAR || !resolution.complete)) {
                return@thenCompose completed(GovernanceDeletionEvidence.blocked(holdFailure(resolution)))
            }
            evaluate(invocation, fence, effectiveClock, resolution, allowBlockedAssessment)
        }.handle { result, throwable ->
            if (throwable == null) result
            else GovernanceDeletionEvidence.failed(unavailable("governance-evidence-unavailable"))
        }
    }

    private fun evaluate(
        invocation: GovernanceTrustedInvocation,
        fence: GovernanceVersionFence,
        clock: GovernanceEffectiveClock,
        resolution: GovernanceLegalHoldResolution,
        allowBlockedAssessment: Boolean,
    ): CompletionStage<GovernanceDeletionEvidence> {
        val context = try {
            calls.create(invocation, GovernancePurpose.EVALUATE_RETENTION, "evaluate-retention")
        } catch (_: RuntimeException) {
            return completed(GovernanceDeletionEvidence.failed(denied("retention-authorization-denied")))
        }
        val policyRequest = GovernanceRetentionPolicyRequest.of(context, clock)
        val policyStage = try {
            policies.load(policyRequest)
        } catch (_: RuntimeException) {
            return completed(GovernanceDeletionEvidence.failed(unavailable("retention-policy-unavailable")))
        }
        return policyStage.thenApply { policy ->
            val request = GovernanceRetentionEvaluationRequest.of(context, fence, policy, resolution, clock)
            val assessment = evaluator.evaluate(request)
            require(assessment.requestDigest == request.requestDigest && assessment.resource == invocation.resource &&
                assessment.tenantId == invocation.tenantId && assessment.fence.fenceDigest == fence.fenceDigest
            ) { "Governance retention evaluator returned mismatched evidence." }
            if (!allowBlockedAssessment && !assessment.isDeletionEligible()) {
                GovernanceDeletionEvidence.blocked(assessmentFailure(assessment))
            } else {
                GovernanceDeletionEvidence.available(assessment)
            }
        }
    }

    private fun validResolution(
        invocation: GovernanceTrustedInvocation,
        clock: GovernanceEffectiveClock,
        resolution: GovernanceLegalHoldResolution,
    ): Boolean = resolution.resource == invocation.resource && resolution.tenantId == invocation.tenantId &&
        resolution.clock.clockDigest == clock.clockDigest &&
        resolution.expiresAtEpochMilli >= invocation.deadlineEpochMilli

    private fun holdFailure(resolution: GovernanceLegalHoldResolution): GovernanceFailure =
        if (resolution.status == GovernanceLegalHoldResolutionStatus.HELD) {
            GovernanceFailure.of(GovernanceFailureClass.LEGAL_HOLD_ACTIVE, "legal-hold-active", false, false)
        } else {
            stale("legal-hold-incomplete")
        }

    private fun assessmentFailure(assessment: GovernanceRetentionAssessment): GovernanceFailure = when {
        assessment.outcome == GovernanceRetentionOutcome.BLOCKED_BY_LEGAL_HOLD ->
            GovernanceFailure.of(GovernanceFailureClass.LEGAL_HOLD_ACTIVE, "legal-hold-active", false, false)
        assessment.outcome == GovernanceRetentionOutcome.INCOMPLETE -> stale("governance-evidence-incomplete")
        else -> terminalFailure("retention-not-expired")
    }
}

class ProviderNeutralGovernancePlanningRuntime(
    private val evidence: GovernanceDeletionEvidenceResolver,
    private val calls: GovernanceAuthorizedCallFactory,
    private val clock: GovernanceRuntimeClockPort,
    private val identifiers: GovernanceRuntimeIdPort,
    private val targets: GovernanceDeletionTargetPort,
    private val repository: GovernanceDeletionRepository,
    private val workerSignals: GovernanceWorkerSignalPort,
    private val metrics: GovernanceMetricsPort,
    private val planTtlMillis: Long = 30L * 24L * 60L * 60L * 1000L,
) {
    init {
        require(planTtlMillis in 1L..90L * 24L * 60L * 60L * 1000L) {
            "Governance runtime plan TTL is invalid."
        }
    }

    fun plan(command: GovernanceDeletionPlanCommand): CompletionStage<GovernancePlanningResult> {
        val planContext = try {
            calls.create(command.invocation, GovernancePurpose.PLAN_SECURE_DELETION, "plan-secure-deletion")
        } catch (_: RuntimeException) {
            return completed(GovernancePlanningResult.failed(denied("planning-authorization-denied")))
        }
        val existing = try {
            repository.findByIdempotency(command.invocation.tenantId, command.invocation.idempotencyKey)
        } catch (_: RuntimeException) {
            return completed(GovernancePlanningResult.failed(unavailable("planning-repository-unavailable")))
        }
        if (existing != null) {
            return if (existing.commandDigest == command.commandDigest) {
                metric(GovernanceMetricCode.IDEMPOTENT_REPLAY)
                completed(GovernancePlanningResult.replayed(existing))
            } else {
                metric(GovernanceMetricCode.CAS_CONFLICT)
                completed(GovernancePlanningResult.empty(GovernancePlanningStatus.CONFLICT))
            }
        }
        return evidence.resolve(command.invocation, command.fence).thenCompose { resolved ->
            if (resolved.status != GovernanceEvidenceStatus.AVAILABLE) {
                val failure = requireNotNull(resolved.failure)
                if (resolved.status == GovernanceEvidenceStatus.BLOCKED) {
                    metric(GovernanceMetricCode.LEGAL_HOLD_BLOCKED)
                    return@thenCompose completed(GovernancePlanningResult.blocked(failure))
                }
                return@thenCompose completed(GovernancePlanningResult.failed(failure))
            }
            val assessment = requireNotNull(resolved.assessment)
            val targetRequest = GovernanceDeletionTargetRequest.of(planContext, assessment.assessmentDigest)
            val targetStage = try {
                targets.targets(targetRequest)
            } catch (_: RuntimeException) {
                return@thenCompose completed(
                    GovernancePlanningResult.failed(unavailable("deletion-targets-unavailable")),
                )
            }
            targetStage.thenCompose { targetList -> createAndStore(command, planContext, assessment, targetList) }
        }.handle { result, throwable ->
            if (throwable == null) result else GovernancePlanningResult.failed(unavailable("planning-runtime-failed"))
        }
    }

    private fun createAndStore(
        command: GovernanceDeletionPlanCommand,
        planContext: ai.icen.fw.governance.api.GovernanceCallContext,
        assessment: GovernanceRetentionAssessment,
        targetList: List<GovernanceDeletionTarget>,
    ): CompletionStage<GovernancePlanningResult> {
        val now = clock.nowEpochMilli()
        require(now in planContext.requestedAtEpochMilli..planContext.deadlineEpochMilli) {
            "Governance deletion plan creation is outside its trusted call window."
        }
        val byStage = targetList.associateBy { target -> target.stage }
        require(byStage.size == targetList.size && byStage.keys == GovernanceDeletionPlan.REQUIRED_STAGE_ORDER.toSet()) {
            "Governance deletion targets must contain every fixed stage exactly once."
        }
        val steps = GovernanceDeletionPlan.REQUIRED_STAGE_ORDER.mapIndexed { index, stage ->
            val target = requireNotNull(byStage[stage])
            val stepId = nextId(
                GovernanceRuntimeIdKind.STEP,
                command.invocation.tenantId,
                GovernanceRuntimeSupport.digest("flowweft-governance-runtime-step-seed-v1")
                    .text(command.commandDigest).text(stage.name).finish(),
                index + 1,
            )
            GovernanceDeletionStep.of(
                stepId,
                index + 1,
                stage,
                target.targetRef,
                target.targetRevision,
                target.targetDigest,
                "step-${index + 1}-${command.commandDigest}",
            )
        }
        val planId = nextId(
            GovernanceRuntimeIdKind.PLAN, command.invocation.tenantId, command.commandDigest, 0,
        )
        val plan = GovernanceDeletionPlan.of(
            planId,
            planContext,
            command.fence,
            assessment,
            steps,
            command.dryRun,
            now,
            now + planTtlMillis,
        )
        if (command.dryRun) return completed(GovernancePlanningResult.dryRun(plan))
        val run = GovernanceDeletionRun.ready(plan, command.commandDigest, command.invocation.idempotencyKey, now)
        val outbox = outbox(run, GovernanceOutboxType.RUN_READY, now)
        val stored = repository.compareAndSet(run.tenantId, run.planId, null, run, outbox)
        val exact = exactStored(run, stored) ?: if (stored.code == GovernanceStoreCode.OUTCOME_UNKNOWN) {
            repository.findByIdempotency(run.tenantId, run.idempotencyKey)?.takeIf {
                it.stateDigest == run.stateDigest
            }
        } else {
            null
        }
        if (exact == null) {
            return if (stored.code == GovernanceStoreCode.OUTCOME_UNKNOWN) {
                metric(GovernanceMetricCode.STORE_OUTCOME_UNKNOWN)
                completed(GovernancePlanningResult.empty(GovernancePlanningStatus.STORE_OUTCOME_UNKNOWN))
            } else {
                metric(GovernanceMetricCode.CAS_CONFLICT)
                completed(GovernancePlanningResult.empty(GovernancePlanningStatus.CONFLICT))
            }
        }
        metric(GovernanceMetricCode.PLAN_CREATED)
        return workerSignals.signal(outbox).handle { _, _ -> GovernancePlanningResult.created(exact) }
    }

    private fun nextId(kind: GovernanceRuntimeIdKind, tenantId: String, seed: String, ordinal: Int): String =
        GovernanceRuntimeSupport.opaque(
            identifiers.nextId(GovernanceRuntimeIdRequest.of(kind, tenantId, seed, ordinal)),
            "Governance runtime id provider returned an invalid identifier.",
        )

    private fun outbox(run: GovernanceDeletionRun, type: GovernanceOutboxType, now: Long): GovernanceOutboxRecord {
        val seed = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-outbox-seed-v1")
            .text(run.stateDigest).text(type.code).finish()
        return GovernanceOutboxRecord.of(
            nextId(
                GovernanceRuntimeIdKind.OUTBOX,
                run.tenantId,
                seed,
                (run.version % Int.MAX_VALUE.toLong()).toInt(),
            ),
            type,
            run,
            now,
        )
    }

    private fun exactStored(candidate: GovernanceDeletionRun, result: GovernanceStoreResult): GovernanceDeletionRun? =
        result.run?.takeIf { run -> run.stateDigest == candidate.stateDigest }

    private fun metric(code: GovernanceMetricCode) {
        try {
            metrics.record(GovernanceMetric.of(code))
        } catch (_: RuntimeException) {
            // Metrics are value-free observability and never change a governance decision.
        }
    }
}

private fun unavailable(reason: String): GovernanceFailure = GovernanceFailure.of(
    GovernanceFailureClass.TEMPORARY_UNAVAILABLE, reason, true, false,
)

private fun denied(reason: String): GovernanceFailure = GovernanceFailure.of(
    GovernanceFailureClass.DENIED, reason, false, false,
)

private fun stale(reason: String): GovernanceFailure = GovernanceFailure.of(
    GovernanceFailureClass.STALE_EVIDENCE, reason, false, false,
)

private fun terminalFailure(reason: String): GovernanceFailure = GovernanceFailure.of(
    GovernanceFailureClass.PERMANENT_FAILURE, reason, false, false,
)

private fun <T> completed(value: T): CompletionStage<T> = CompletableFuture.completedFuture(value)
