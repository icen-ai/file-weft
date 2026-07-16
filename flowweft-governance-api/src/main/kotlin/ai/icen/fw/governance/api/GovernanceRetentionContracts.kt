package ai.icen.fw.governance.api

class GovernanceRetentionPolicyMode private constructor(code: String) {
    val code: String = GovernanceContractSupport.requireMachineCode(
        code, "Governance retention policy mode is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceRetentionPolicyMode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceRetentionPolicyMode(<redacted>)"

    companion object {
        @JvmField val RETAIN_UNTIL = GovernanceRetentionPolicyMode("retain-until")
        @JvmField val RETAIN_INDEFINITELY = GovernanceRetentionPolicyMode("retain-indefinitely")
        @JvmField val UNKNOWN = GovernanceRetentionPolicyMode("unknown")

        @JvmStatic
        fun of(code: String): GovernanceRetentionPolicyMode = when (code) {
            RETAIN_UNTIL.code -> RETAIN_UNTIL
            RETAIN_INDEFINITELY.code -> RETAIN_INDEFINITELY
            UNKNOWN.code -> UNKNOWN
            else -> GovernanceRetentionPolicyMode(code)
        }
    }
}

/** Immutable, versioned resource-specific policy evidence. */
class GovernanceRetentionPolicySnapshot private constructor(
    tenantId: String,
    val resource: GovernanceResourceRef,
    policyId: String,
    version: String,
    policyDigest: String,
    val mode: GovernanceRetentionPolicyMode,
    val effectiveFromEpochMilli: Long,
    val capturedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
    val retainUntilEpochMilli: Long?,
) {
    val tenantId: String = GovernanceContractSupport.requireText(
        tenantId, GovernanceContractSupport.MAX_ID_UTF8_BYTES, "Governance retention tenant is invalid.",
    )
    val policyId: String = GovernanceContractSupport.requireMachineCode(
        policyId, "Governance retention policy identifier is invalid.",
    )
    val version: String = GovernanceContractSupport.requireText(
        version, GovernanceContractSupport.MAX_REVISION_UTF8_BYTES,
        "Governance retention policy version is invalid.",
    )
    val policyDigest: String = GovernanceContractSupport.requireSha256(
        policyDigest, "Governance retention policy digest is invalid.",
    )
    val snapshotDigest: String

    init {
        require(!version.equals("latest", ignoreCase = true)) {
            "Governance retention policy version must be exact."
        }
        require(effectiveFromEpochMilli >= 0L && capturedAtEpochMilli >= 0L &&
            expiresAtEpochMilli > capturedAtEpochMilli) {
            "Governance retention policy evidence window is invalid."
        }
        when (mode) {
            GovernanceRetentionPolicyMode.RETAIN_UNTIL -> require(retainUntilEpochMilli != null &&
                retainUntilEpochMilli >= 0L) {
                "Retain-until governance policy requires a retention deadline."
            }
            GovernanceRetentionPolicyMode.RETAIN_INDEFINITELY,
            GovernanceRetentionPolicyMode.UNKNOWN,
            -> require(retainUntilEpochMilli == null) {
                "Only retain-until governance policy may carry a retention deadline."
            }
            else -> require(false) { "Unknown governance retention policy mode is fail-closed." }
        }
        snapshotDigest = GovernanceContractSupport.digest("flowweft-governance-api-retention-policy-v1")
            .text(this.tenantId)
            .text(resource.referenceDigest)
            .text(this.policyId)
            .text(this.version)
            .text(this.policyDigest)
            .text(mode.code)
            .longValue(effectiveFromEpochMilli)
            .longValue(capturedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .longValue(retainUntilEpochMilli ?: -1L)
            .finish()
    }

    override fun toString(): String = "GovernanceRetentionPolicySnapshot(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            resource: GovernanceResourceRef,
            policyId: String,
            version: String,
            policyDigest: String,
            mode: GovernanceRetentionPolicyMode,
            effectiveFromEpochMilli: Long,
            capturedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
            retainUntilEpochMilli: Long?,
        ): GovernanceRetentionPolicySnapshot = GovernanceRetentionPolicySnapshot(
            tenantId,
            resource,
            policyId,
            version,
            policyDigest,
            mode,
            effectiveFromEpochMilli,
            capturedAtEpochMilli,
            expiresAtEpochMilli,
            retainUntilEpochMilli,
        )
    }
}

/** Exact evidence bundle for deterministic retention evaluation. */
class GovernanceRetentionEvaluationRequest private constructor(
    val context: GovernanceCallContext,
    val fence: GovernanceVersionFence,
    val policy: GovernanceRetentionPolicySnapshot,
    val legalHolds: GovernanceLegalHoldResolution,
    val clock: GovernanceEffectiveClock,
) {
    val resource: GovernanceResourceRef = fence.resource
    val requestDigest: String

    init {
        require(context.purpose == GovernancePurpose.EVALUATE_RETENTION) {
            "Governance retention evaluation requires its exact purpose."
        }
        require(context.authorization.resource == resource && policy.resource == resource &&
            legalHolds.resource == resource) {
            "Governance retention evidence does not match the exact resource snapshot."
        }
        require(context.tenantId == policy.tenantId && context.tenantId == legalHolds.tenantId) {
            "Governance retention evidence does not match the exact tenant."
        }
        require(clock.clockDigest == legalHolds.clock.clockDigest) {
            "Governance retention and legal-hold evidence use different effective clocks."
        }
        require(clock.observedAtEpochMilli in context.requestedAtEpochMilli..context.deadlineEpochMilli) {
            "Governance retention clock observation is outside the call window."
        }
        requestDigest = GovernanceContractSupport.digest("flowweft-governance-api-retention-request-v1")
            .text(context.contextDigest)
            .text(fence.fenceDigest)
            .text(policy.snapshotDigest)
            .text(legalHolds.resolutionDigest)
            .text(clock.clockDigest)
            .finish()
    }

    override fun toString(): String = "GovernanceRetentionEvaluationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: GovernanceCallContext,
            fence: GovernanceVersionFence,
            policy: GovernanceRetentionPolicySnapshot,
            legalHolds: GovernanceLegalHoldResolution,
            clock: GovernanceEffectiveClock,
        ): GovernanceRetentionEvaluationRequest = GovernanceRetentionEvaluationRequest(
            context, fence, policy, legalHolds, clock,
        )
    }
}

class GovernanceRetentionOutcome private constructor(code: String) {
    val code: String = GovernanceContractSupport.requireMachineCode(
        code, "Governance retention outcome is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceRetentionOutcome && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceRetentionOutcome(<redacted>)"

    companion object {
        @JvmField val ELIGIBLE_FOR_DELETION = GovernanceRetentionOutcome("eligible-for-deletion")
        @JvmField val RETAIN = GovernanceRetentionOutcome("retain")
        @JvmField val BLOCKED_BY_LEGAL_HOLD = GovernanceRetentionOutcome("blocked-by-legal-hold")
        @JvmField val INCOMPLETE = GovernanceRetentionOutcome("incomplete")
    }
}

class GovernanceRetentionReason private constructor(code: String) {
    val code: String = GovernanceContractSupport.requireMachineCode(
        code, "Governance retention reason is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceRetentionReason && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceRetentionReason(<redacted>)"

    companion object {
        @JvmField val RETENTION_EXPIRED = GovernanceRetentionReason("retention-expired")
        @JvmField val RETENTION_PERIOD_ACTIVE = GovernanceRetentionReason("retention-period-active")
        @JvmField val RETAIN_INDEFINITELY = GovernanceRetentionReason("retain-indefinitely")
        @JvmField val ACTIVE_LEGAL_HOLD = GovernanceRetentionReason("active-legal-hold")
        @JvmField val INCOMPLETE_LEGAL_HOLD = GovernanceRetentionReason("incomplete-legal-hold")
        @JvmField val POLICY_UNKNOWN = GovernanceRetentionReason("policy-unknown")
        @JvmField val POLICY_NOT_EFFECTIVE = GovernanceRetentionReason("policy-not-effective")
        @JvmField val STALE_EVIDENCE = GovernanceRetentionReason("stale-evidence")
    }
}

/** Derived decision. Callers cannot directly manufacture an eligible result. */
class GovernanceRetentionAssessment private constructor(
    tenantId: String,
    val resource: GovernanceResourceRef,
    val fence: GovernanceVersionFence,
    val policy: GovernanceRetentionPolicySnapshot,
    val legalHolds: GovernanceLegalHoldResolution,
    val clock: GovernanceEffectiveClock,
    requestDigest: String,
    val outcome: GovernanceRetentionOutcome,
    val reason: GovernanceRetentionReason,
) {
    val tenantId: String = GovernanceContractSupport.requireText(
        tenantId,
        GovernanceContractSupport.MAX_ID_UTF8_BYTES,
        "Governance retention assessment tenant is invalid.",
    )
    val requestDigest: String = GovernanceContractSupport.requireSha256(
        requestDigest,
        "Governance retention assessment request digest is invalid.",
    )
    val validUntilEpochMilli: Long = minOf(
        policy.expiresAtEpochMilli,
        legalHolds.expiresAtEpochMilli,
        clock.expiresAtEpochMilli,
    )
    val assessmentDigest: String

    init {
        require(fence.resource == resource && policy.resource == resource && legalHolds.resource == resource) {
            "Governance retention assessment evidence does not match its resource snapshot."
        }
        require(this.tenantId == policy.tenantId && this.tenantId == legalHolds.tenantId) {
            "Governance retention assessment evidence does not match its tenant."
        }
        require(clock.clockDigest == legalHolds.clock.clockDigest) {
            "Governance retention assessment evidence uses different effective clocks."
        }
        require(decision(policy, legalHolds, clock) == (outcome to reason)) {
            "Governance retention assessment outcome does not match its canonical evidence."
        }
        assessmentDigest = GovernanceContractSupport.digest(
            "flowweft-governance-api-retention-assessment-v1",
        )
            .text(this.requestDigest)
            .text(outcome.code)
            .text(reason.code)
            .longValue(validUntilEpochMilli)
            .finish()
    }

    fun isDeletionEligible(): Boolean = outcome == GovernanceRetentionOutcome.ELIGIBLE_FOR_DELETION

    override fun toString(): String = "GovernanceRetentionAssessment(<redacted>)"

    companion object {
        @JvmStatic
        fun evaluate(request: GovernanceRetentionEvaluationRequest): GovernanceRetentionAssessment {
            val result = decision(request.policy, request.legalHolds, request.clock)
            return GovernanceRetentionAssessment(
                request.context.tenantId,
                request.resource,
                request.fence,
                request.policy,
                request.legalHolds,
                request.clock,
                request.requestDigest,
                result.first,
                result.second,
            )
        }

        /** Restores an assessment from durable evidence and rejects any changed canonical field. */
        @JvmStatic
        fun rehydrate(
            tenantId: String,
            resource: GovernanceResourceRef,
            fence: GovernanceVersionFence,
            policy: GovernanceRetentionPolicySnapshot,
            legalHolds: GovernanceLegalHoldResolution,
            clock: GovernanceEffectiveClock,
            requestDigest: String,
            outcome: GovernanceRetentionOutcome,
            reason: GovernanceRetentionReason,
            expectedAssessmentDigest: String,
        ): GovernanceRetentionAssessment {
            val restored = GovernanceRetentionAssessment(
                tenantId,
                resource,
                fence,
                policy,
                legalHolds,
                clock,
                requestDigest,
                outcome,
                reason,
            )
            val expected = GovernanceContractSupport.requireSha256(
                expectedAssessmentDigest,
                "Governance persisted retention assessment digest is invalid.",
            )
            require(restored.assessmentDigest == expected) {
                "Governance persisted retention assessment digest does not match its canonical evidence."
            }
            return restored
        }

        private fun decision(
            policy: GovernanceRetentionPolicySnapshot,
            holds: GovernanceLegalHoldResolution,
            clock: GovernanceEffectiveClock,
        ): Pair<GovernanceRetentionOutcome, GovernanceRetentionReason> = when {
            holds.status == GovernanceLegalHoldResolutionStatus.HELD ->
                GovernanceRetentionOutcome.BLOCKED_BY_LEGAL_HOLD to GovernanceRetentionReason.ACTIVE_LEGAL_HOLD
            holds.status != GovernanceLegalHoldResolutionStatus.CLEAR || !holds.complete ||
                clock.observedAtEpochMilli >= holds.expiresAtEpochMilli ->
                GovernanceRetentionOutcome.INCOMPLETE to GovernanceRetentionReason.INCOMPLETE_LEGAL_HOLD
            policy.mode == GovernanceRetentionPolicyMode.UNKNOWN ->
                GovernanceRetentionOutcome.INCOMPLETE to GovernanceRetentionReason.POLICY_UNKNOWN
            clock.effectiveAtEpochMilli < policy.effectiveFromEpochMilli ->
                GovernanceRetentionOutcome.INCOMPLETE to GovernanceRetentionReason.POLICY_NOT_EFFECTIVE
            policy.capturedAtEpochMilli > clock.observedAtEpochMilli ||
                clock.observedAtEpochMilli >= policy.expiresAtEpochMilli ||
                clock.observedAtEpochMilli >= clock.expiresAtEpochMilli ->
                GovernanceRetentionOutcome.INCOMPLETE to GovernanceRetentionReason.STALE_EVIDENCE
            policy.mode == GovernanceRetentionPolicyMode.RETAIN_INDEFINITELY ->
                GovernanceRetentionOutcome.RETAIN to GovernanceRetentionReason.RETAIN_INDEFINITELY
            clock.effectiveAtEpochMilli < requireNotNull(policy.retainUntilEpochMilli) ->
                GovernanceRetentionOutcome.RETAIN to GovernanceRetentionReason.RETENTION_PERIOD_ACTIVE
            else -> GovernanceRetentionOutcome.ELIGIBLE_FOR_DELETION to GovernanceRetentionReason.RETENTION_EXPIRED
        }
    }
}
