package ai.icen.fw.reliability.api

class ReliabilitySliKind private constructor(code: String) {
    val code: String = ReliabilityContractSupport.code(code, "Reliability SLI kind is invalid.")

    override fun equals(other: Any?): Boolean = this === other || other is ReliabilitySliKind && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = code

    companion object {
        @JvmField val AVAILABILITY = ReliabilitySliKind("availability")
        @JvmField val SUCCESS_RATE = ReliabilitySliKind("success-rate")
        @JvmField val LATENCY_GOOD_RATE = ReliabilitySliKind("latency-good-rate")
        @JvmField val FRESHNESS = ReliabilitySliKind("freshness")
        @JvmField val BACKLOG_HEALTH = ReliabilitySliKind("backlog-health")

        @JvmStatic
        fun of(code: String): ReliabilitySliKind = builtIns.firstOrNull { it.code == code }
            ?: ReliabilitySliKind(code)

        private val builtIns = listOf(AVAILABILITY, SUCCESS_RATE, LATENCY_GOOD_RATE, FRESHNESS, BACKLOG_HEALTH)
    }
}

/** All proportions are integer parts-per-million; floating-point ratios are not representable. */
class ReliabilitySloObjective private constructor(
    objectiveId: String,
    objectiveVersion: String,
    sourcePolicyDigest: String,
    val resource: ReliabilityResourceRef,
    val sliKind: ReliabilitySliKind,
    val targetPpm: Long,
    val windowMillis: Long,
    val minimumSampleCount: Long,
    val maximumObservationAgeMillis: Long,
    val effectiveFromEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val objectiveId: String = ReliabilityContractSupport.code(objectiveId, "Reliability SLO id is invalid.")
    val objectiveVersion: String = ReliabilityContractSupport.text(
        objectiveVersion, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability SLO version is invalid.",
    )
    val sourcePolicyDigest: String = ReliabilityContractSupport.sha256(
        sourcePolicyDigest, "Reliability SLO source policy digest is invalid.",
    )
    val objectiveDigest: String

    init {
        require(targetPpm in 1L..ReliabilityContractSupport.ONE_MILLION_PPM) {
            "Reliability SLO target ppm is invalid."
        }
        require(windowMillis in 1L..MAX_WINDOW_MILLIS && minimumSampleCount in 1L..MAX_SAMPLE_COUNT &&
            maximumObservationAgeMillis in 1L..MAX_OBSERVATION_AGE_MILLIS
        ) { "Reliability SLO window or evidence limits are invalid." }
        require(effectiveFromEpochMilli >= 0L && expiresAtEpochMilli > effectiveFromEpochMilli) {
            "Reliability SLO validity window is invalid."
        }
        objectiveDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-slo-objective-v1")
            .text(this.objectiveId)
            .text(this.objectiveVersion)
            .text(this.sourcePolicyDigest)
            .text(resource.referenceDigest)
            .text(sliKind.code)
            .longValue(targetPpm)
            .longValue(windowMillis)
            .longValue(minimumSampleCount)
            .longValue(maximumObservationAgeMillis)
            .longValue(effectiveFromEpochMilli)
            .longValue(expiresAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilitySloObjective(<redacted>)"

    companion object {
        const val MAX_WINDOW_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
        const val MAX_OBSERVATION_AGE_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L
        const val MAX_SAMPLE_COUNT: Long = 1_000_000_000_000L

        @JvmStatic
        fun of(
            objectiveId: String,
            objectiveVersion: String,
            sourcePolicyDigest: String,
            resource: ReliabilityResourceRef,
            sliKind: ReliabilitySliKind,
            targetPpm: Long,
            windowMillis: Long,
            minimumSampleCount: Long,
            maximumObservationAgeMillis: Long,
            effectiveFromEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): ReliabilitySloObjective = ReliabilitySloObjective(
            objectiveId,
            objectiveVersion,
            sourcePolicyDigest,
            resource,
            sliKind,
            targetPpm,
            windowMillis,
            minimumSampleCount,
            maximumObservationAgeMillis,
            effectiveFromEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

/** A provider reports integer good/total counts; the contract computes the ppm value canonically. */
class ReliabilitySliObservation private constructor(
    objectiveDigest: String,
    val windowStartEpochMilli: Long,
    val windowEndEpochMilli: Long,
    val goodCount: Long,
    val totalCount: Long,
    val observedAtEpochMilli: Long,
) {
    val objectiveDigest: String = ReliabilityContractSupport.sha256(
        objectiveDigest, "Reliability SLI objective digest is invalid.",
    )
    val valuePpm: Long
    val observationDigest: String

    init {
        require(windowStartEpochMilli >= 0L && windowEndEpochMilli > windowStartEpochMilli &&
            observedAtEpochMilli >= windowEndEpochMilli
        ) { "Reliability SLI observation window is invalid." }
        require(totalCount in 1L..ReliabilitySloObjective.MAX_SAMPLE_COUNT && goodCount in 0L..totalCount) {
            "Reliability SLI counts are invalid; zero samples must be reported as missing data."
        }
        valuePpm = ReliabilityContractSupport.ratioPpm(
            goodCount,
            totalCount,
            ReliabilityContractSupport.ONE_MILLION_PPM,
            "Reliability SLI ratio is invalid.",
        )
        observationDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-sli-observation-v1")
            .text(this.objectiveDigest)
            .longValue(windowStartEpochMilli)
            .longValue(windowEndEpochMilli)
            .longValue(goodCount)
            .longValue(totalCount)
            .longValue(observedAtEpochMilli)
            .longValue(valuePpm)
            .finish()
    }

    override fun toString(): String =
        "ReliabilitySliObservation(valuePpm=$valuePpm, totalCount=$totalCount, <redacted>)"

    companion object {
        @JvmStatic
        fun of(
            objectiveDigest: String,
            windowStartEpochMilli: Long,
            windowEndEpochMilli: Long,
            goodCount: Long,
            totalCount: Long,
            observedAtEpochMilli: Long,
        ): ReliabilitySliObservation = ReliabilitySliObservation(
            objectiveDigest,
            windowStartEpochMilli,
            windowEndEpochMilli,
            goodCount,
            totalCount,
            observedAtEpochMilli,
        )
    }
}

class ReliabilitySloEvaluationRequest private constructor(
    val context: ReliabilityCallContext,
    val objective: ReliabilitySloObjective,
    val observation: ReliabilitySliObservation?,
    val expectedWindowStartEpochMilli: Long,
    val expectedWindowEndEpochMilli: Long,
    val evaluatedAtEpochMilli: Long,
) {
    val requestDigest: String

    init {
        require(context.purpose == ReliabilityPurpose.EVALUATE_SLO &&
            context.action == ReliabilityAction.EVALUATE_SLO && context.resource == objective.resource
        ) { "Reliability SLO evaluation is not authorized for the exact objective resource." }
        require(evaluatedAtEpochMilli >= context.requestedAtEpochMilli &&
            evaluatedAtEpochMilli < context.deadlineEpochMilli &&
            evaluatedAtEpochMilli in objective.effectiveFromEpochMilli until objective.expiresAtEpochMilli
        ) { "Reliability SLO evaluation time is outside the authorized objective window." }
        require(expectedWindowStartEpochMilli >= objective.effectiveFromEpochMilli &&
            expectedWindowEndEpochMilli > expectedWindowStartEpochMilli &&
            expectedWindowEndEpochMilli - expectedWindowStartEpochMilli == objective.windowMillis &&
            expectedWindowEndEpochMilli <= evaluatedAtEpochMilli
        ) { "Reliability SLO request is not bound to one exact objective window." }
        requestDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-slo-evaluation-request-v1")
            .text(context.contextDigest)
            .text(objective.objectiveDigest)
            .optionalText(observation?.observationDigest)
            .longValue(expectedWindowStartEpochMilli)
            .longValue(expectedWindowEndEpochMilli)
            .longValue(evaluatedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilitySloEvaluationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: ReliabilityCallContext,
            objective: ReliabilitySloObjective,
            observation: ReliabilitySliObservation?,
            expectedWindowStartEpochMilli: Long,
            expectedWindowEndEpochMilli: Long,
            evaluatedAtEpochMilli: Long,
        ): ReliabilitySloEvaluationRequest = ReliabilitySloEvaluationRequest(
            context,
            objective,
            observation,
            expectedWindowStartEpochMilli,
            expectedWindowEndEpochMilli,
            evaluatedAtEpochMilli,
        )
    }
}

enum class ReliabilitySloDataState {
    AVAILABLE,
    MISSING,
    STALE,
    INSUFFICIENT,
    MISMATCHED,
}

/** Canonical error-budget and burn-rate result. Non-available data can never satisfy the SLO. */
class ReliabilityErrorBudgetEvaluation private constructor(
    val requestDigest: String,
    val objectiveDigest: String,
    val state: ReliabilitySloDataState,
    val targetPpm: Long,
    val observedPpm: Long?,
    val observedBadCount: Long?,
    val allowedBadPpm: Long,
    val burnRatePpm: Long?,
    val budgetConsumedPpm: Long?,
    val remainingBudgetPpm: Long?,
    val satisfied: Boolean,
    val failure: ReliabilityFailure?,
    val evaluatedAtEpochMilli: Long,
) {
    val evaluationDigest: String

    init {
        ReliabilityContractSupport.sha256(requestDigest, "Reliability SLO request digest is invalid.")
        ReliabilityContractSupport.sha256(objectiveDigest, "Reliability SLO objective digest is invalid.")
        ReliabilityContractSupport.ppm(targetPpm, "Reliability SLO target ppm is invalid.")
        ReliabilityContractSupport.ppm(allowedBadPpm, "Reliability SLO error budget ppm is invalid.")
        observedPpm?.let { ReliabilityContractSupport.ppm(it, "Reliability SLO observation ppm is invalid.") }
        observedBadCount?.let { ReliabilityContractSupport.nonNegative(it, "Reliability bad-event count is invalid.") }
        budgetConsumedPpm?.let {
            ReliabilityContractSupport.ppm(it, "Reliability SLO consumed budget ppm is invalid.")
        }
        remainingBudgetPpm?.let {
            ReliabilityContractSupport.ppm(it, "Reliability SLO remaining budget ppm is invalid.")
        }
        require((state == ReliabilitySloDataState.AVAILABLE) ==
            (observedPpm != null && observedBadCount != null && burnRatePpm != null &&
                budgetConsumedPpm != null && remainingBudgetPpm != null && failure == null)
        ) { "Reliability SLO data availability is inconsistent." }
        require(state != ReliabilitySloDataState.AVAILABLE ||
            requireNotNull(budgetConsumedPpm) + requireNotNull(remainingBudgetPpm) ==
            ReliabilityContractSupport.ONE_MILLION_PPM
        ) { "Reliability consumed and remaining budget fractions must form one complete budget." }
        require(!satisfied || state == ReliabilitySloDataState.AVAILABLE && requireNotNull(observedPpm) >= targetPpm) {
            "Reliability SLO cannot be satisfied without current qualifying data."
        }
        evaluationDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-error-budget-v1")
            .text(requestDigest)
            .text(objectiveDigest)
            .text(state.name)
            .longValue(targetPpm)
            .longValue(observedPpm ?: -1L)
            .longValue(observedBadCount ?: -1L)
            .longValue(allowedBadPpm)
            .longValue(burnRatePpm ?: -1L)
            .longValue(budgetConsumedPpm ?: -1L)
            .longValue(remainingBudgetPpm ?: -1L)
            .bool(satisfied)
            .optionalText(failure?.failureDigest)
            .longValue(evaluatedAtEpochMilli)
            .finish()
    }

    override fun toString(): String =
        "ReliabilityErrorBudgetEvaluation(state=$state, satisfied=$satisfied, burnRatePpm=$burnRatePpm)"

    companion object {
        @JvmStatic
        fun evaluate(request: ReliabilitySloEvaluationRequest): ReliabilityErrorBudgetEvaluation {
            val objective = request.objective
            val observation = request.observation
            val state = when {
                observation == null -> ReliabilitySloDataState.MISSING
                observation.objectiveDigest != objective.objectiveDigest ||
                    observation.windowStartEpochMilli != request.expectedWindowStartEpochMilli ||
                    observation.windowEndEpochMilli != request.expectedWindowEndEpochMilli ->
                    ReliabilitySloDataState.MISMATCHED
                observation.observedAtEpochMilli > request.evaluatedAtEpochMilli ||
                    request.evaluatedAtEpochMilli - observation.observedAtEpochMilli >
                    objective.maximumObservationAgeMillis -> ReliabilitySloDataState.STALE
                observation.totalCount < objective.minimumSampleCount -> ReliabilitySloDataState.INSUFFICIENT
                else -> ReliabilitySloDataState.AVAILABLE
            }
            val allowedBad = ReliabilityContractSupport.ONE_MILLION_PPM - objective.targetPpm
            if (state != ReliabilitySloDataState.AVAILABLE) {
                val code = when (state) {
                    ReliabilitySloDataState.MISSING -> ReliabilityFailureCode.DATA_MISSING
                    ReliabilitySloDataState.STALE -> ReliabilityFailureCode.DATA_STALE
                    ReliabilitySloDataState.INSUFFICIENT -> ReliabilityFailureCode.DATA_INSUFFICIENT
                    else -> ReliabilityFailureCode.DATA_MISMATCHED
                }
                return ReliabilityErrorBudgetEvaluation(
                    request.requestDigest,
                    objective.objectiveDigest,
                    state,
                    objective.targetPpm,
                    null,
                    null,
                    allowedBad,
                    null,
                    null,
                    null,
                    false,
                    ReliabilityFailure.of(ReliabilityFailureClass.STALE_EVIDENCE, code),
                    request.evaluatedAtEpochMilli,
                )
            }
            val observed = requireNotNull(observation).valuePpm
            val observedBadCount = observation.totalCount - observation.goodCount
            val exactBurn = when {
                allowedBad > 0L -> ReliabilityContractSupport.errorBudgetRatioPpm(
                    observedBadCount,
                    observation.totalCount,
                    allowedBad,
                    ReliabilityContractSupport.MAX_BURN_RATE_PPM,
                    "Reliability SLO burn rate is invalid.",
                )
                observedBadCount == 0L -> 0L
                else -> ReliabilityContractSupport.MAX_BURN_RATE_PPM
            }
            val consumed = when {
                allowedBad > 0L -> ReliabilityContractSupport.errorBudgetRatioPpm(
                    observedBadCount,
                    observation.totalCount,
                    allowedBad,
                    ReliabilityContractSupport.ONE_MILLION_PPM,
                    "Reliability SLO consumed budget is invalid.",
                )
                observedBadCount == 0L -> 0L
                else -> ReliabilityContractSupport.ONE_MILLION_PPM
            }
            val remaining = ReliabilityContractSupport.ONE_MILLION_PPM - consumed
            return ReliabilityErrorBudgetEvaluation(
                request.requestDigest,
                objective.objectiveDigest,
                state,
                objective.targetPpm,
                observed,
                observedBadCount,
                allowedBad,
                exactBurn,
                consumed,
                remaining,
                observed >= objective.targetPpm,
                null,
                request.evaluatedAtEpochMilli,
            )
        }

        /**
         * Rebuilds a previously validated evaluation from canonical durable fields.
         * The independently stored digest is mandatory so a partial or corrupted record cannot
         * silently become current SLO evidence.
         */
        @JvmStatic
        fun rehydrate(
            requestDigest: String,
            objectiveDigest: String,
            state: ReliabilitySloDataState,
            targetPpm: Long,
            observedPpm: Long?,
            observedBadCount: Long?,
            allowedBadPpm: Long,
            burnRatePpm: Long?,
            budgetConsumedPpm: Long?,
            remainingBudgetPpm: Long?,
            satisfied: Boolean,
            failure: ReliabilityFailure?,
            evaluatedAtEpochMilli: Long,
            expectedEvaluationDigest: String,
        ): ReliabilityErrorBudgetEvaluation {
            val restored = ReliabilityErrorBudgetEvaluation(
                requestDigest,
                objectiveDigest,
                state,
                targetPpm,
                observedPpm,
                observedBadCount,
                allowedBadPpm,
                burnRatePpm,
                budgetConsumedPpm,
                remainingBudgetPpm,
                satisfied,
                failure,
                evaluatedAtEpochMilli,
            )
            val expected = ReliabilityContractSupport.sha256(
                expectedEvaluationDigest,
                "Reliability persisted SLO evaluation digest is invalid.",
            )
            require(restored.evaluationDigest == expected) {
                "Reliability persisted SLO evaluation digest does not match its canonical fields."
            }
            return restored
        }
    }
}

class ReliabilityBurnRatePolicy private constructor(
    policyId: String,
    policyVersion: String,
    sourcePolicyDigest: String,
    objectiveDigest: String,
    val warningBurnRatePpm: Long,
    val criticalBurnRatePpm: Long,
) {
    val policyId: String = ReliabilityContractSupport.code(policyId, "Reliability burn policy id is invalid.")
    val policyVersion: String = ReliabilityContractSupport.text(
        policyVersion, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability burn policy version is invalid.",
    )
    val sourcePolicyDigest: String = ReliabilityContractSupport.sha256(
        sourcePolicyDigest, "Reliability burn policy source digest is invalid.",
    )
    val objectiveDigest: String = ReliabilityContractSupport.sha256(
        objectiveDigest, "Reliability burn policy objective digest is invalid.",
    )
    val policyDigest: String

    init {
        require(warningBurnRatePpm in 1L..ReliabilityContractSupport.MAX_BURN_RATE_PPM &&
            criticalBurnRatePpm in (warningBurnRatePpm + 1L)..ReliabilityContractSupport.MAX_BURN_RATE_PPM
        ) { "Reliability burn-rate thresholds are invalid." }
        policyDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-burn-policy-v1")
            .text(this.policyId)
            .text(this.policyVersion)
            .text(this.sourcePolicyDigest)
            .text(this.objectiveDigest)
            .longValue(warningBurnRatePpm)
            .longValue(criticalBurnRatePpm)
            .finish()
    }

    override fun toString(): String = "ReliabilityBurnRatePolicy(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            policyId: String,
            policyVersion: String,
            sourcePolicyDigest: String,
            objectiveDigest: String,
            warningBurnRatePpm: Long,
            criticalBurnRatePpm: Long,
        ): ReliabilityBurnRatePolicy = ReliabilityBurnRatePolicy(
            policyId,
            policyVersion,
            sourcePolicyDigest,
            objectiveDigest,
            warningBurnRatePpm,
            criticalBurnRatePpm,
        )
    }
}

enum class ReliabilityAlertSeverity { NONE, WARNING, CRITICAL }
enum class ReliabilityAlertCode { WITHIN_BUDGET, SLO_BREACH, BURN_RATE_WARNING, BURN_RATE_CRITICAL, DATA_UNAVAILABLE }

/** Missing, stale, insufficient, or mismatched data always triggers a fail-closed critical alert. */
class ReliabilityBurnRateAlert private constructor(
    val policyDigest: String,
    val evaluationDigest: String,
    val severity: ReliabilityAlertSeverity,
    val code: ReliabilityAlertCode,
    val triggered: Boolean,
    val evaluatedAtEpochMilli: Long,
) {
    val alertDigest: String

    init {
        ReliabilityContractSupport.sha256(policyDigest, "Reliability alert policy digest is invalid.")
        ReliabilityContractSupport.sha256(evaluationDigest, "Reliability alert evaluation digest is invalid.")
        require(triggered == (severity != ReliabilityAlertSeverity.NONE)) {
            "Reliability alert trigger and severity are inconsistent."
        }
        alertDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-burn-alert-v1")
            .text(policyDigest)
            .text(evaluationDigest)
            .text(severity.name)
            .text(code.name)
            .bool(triggered)
            .longValue(evaluatedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityBurnRateAlert(severity=$severity, code=$code)"

    companion object {
        @JvmStatic
        fun evaluate(
            policy: ReliabilityBurnRatePolicy,
            evaluation: ReliabilityErrorBudgetEvaluation,
            evaluatedAtEpochMilli: Long,
        ): ReliabilityBurnRateAlert {
            require(policy.objectiveDigest == evaluation.objectiveDigest &&
                evaluatedAtEpochMilli >= evaluation.evaluatedAtEpochMilli
            ) { "Reliability alert inputs are not bound to the same objective and time." }
            val burn = evaluation.burnRatePpm
            val pair = when {
                evaluation.state != ReliabilitySloDataState.AVAILABLE ->
                    ReliabilityAlertSeverity.CRITICAL to ReliabilityAlertCode.DATA_UNAVAILABLE
                requireNotNull(burn) >= policy.criticalBurnRatePpm ->
                    ReliabilityAlertSeverity.CRITICAL to ReliabilityAlertCode.BURN_RATE_CRITICAL
                burn >= policy.warningBurnRatePpm ->
                    ReliabilityAlertSeverity.WARNING to ReliabilityAlertCode.BURN_RATE_WARNING
                !evaluation.satisfied -> ReliabilityAlertSeverity.WARNING to ReliabilityAlertCode.SLO_BREACH
                else -> ReliabilityAlertSeverity.NONE to ReliabilityAlertCode.WITHIN_BUDGET
            }
            return ReliabilityBurnRateAlert(
                policy.policyDigest,
                evaluation.evaluationDigest,
                pair.first,
                pair.second,
                pair.first != ReliabilityAlertSeverity.NONE,
                evaluatedAtEpochMilli,
            )
        }

        /** Restores a canonical alert and rejects a missing, malformed, or mismatched digest. */
        @JvmStatic
        fun rehydrate(
            policyDigest: String,
            evaluationDigest: String,
            severity: ReliabilityAlertSeverity,
            code: ReliabilityAlertCode,
            triggered: Boolean,
            evaluatedAtEpochMilli: Long,
            expectedAlertDigest: String,
        ): ReliabilityBurnRateAlert {
            val restored = ReliabilityBurnRateAlert(
                policyDigest,
                evaluationDigest,
                severity,
                code,
                triggered,
                evaluatedAtEpochMilli,
            )
            val expected = ReliabilityContractSupport.sha256(
                expectedAlertDigest,
                "Reliability persisted burn alert digest is invalid.",
            )
            require(restored.alertDigest == expected) {
                "Reliability persisted burn alert digest does not match its canonical fields."
            }
            return restored
        }
    }
}
