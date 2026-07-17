package ai.icen.fw.capacity.api

import ai.icen.fw.core.id.Identifier

/** Digest-only idempotency plus exact state/policy CAS. Raw idempotency keys never enter this API. */
class CapacityWritePrecondition private constructor(
    idempotencyKeyDigest: String,
    val expectedStateVersion: Long,
    expectedPolicyResolutionDigest: String?,
) {
    val idempotencyKeyDigest: String = requireCapacityDigest(
        idempotencyKeyDigest,
        "Capacity idempotency key",
    )
    val expectedPolicyResolutionDigest: String? = expectedPolicyResolutionDigest?.let { digest ->
        requireCapacityDigest(digest, "Expected capacity policy resolution")
    }
    val bindingDigest: String

    init {
        require(expectedStateVersion >= 0L) { "Expected capacity state version is invalid." }
        bindingDigest = CapacityDigest("flowweft.capacity.write-precondition.v1")
            .add(this.idempotencyKeyDigest)
            .add(expectedStateVersion)
            .add(this.expectedPolicyResolutionDigest ?: "-")
            .finish()
    }

    override fun toString(): String = "CapacityWritePrecondition(expectedStateVersion=$expectedStateVersion, <redacted>)"

    companion object {
        @JvmStatic
        fun admission(
            idempotencyKeyDigest: String,
            expectedStateVersion: Long,
            expectedPolicyResolutionDigest: String,
        ): CapacityWritePrecondition = CapacityWritePrecondition(
            idempotencyKeyDigest,
            expectedStateVersion,
            expectedPolicyResolutionDigest,
        )

        @JvmStatic
        fun renewal(
            idempotencyKeyDigest: String,
            expectedStateVersion: Long,
            expectedPolicyResolutionDigest: String,
        ): CapacityWritePrecondition = CapacityWritePrecondition(
            idempotencyKeyDigest,
            expectedStateVersion,
            expectedPolicyResolutionDigest,
        )

        @JvmStatic
        fun release(
            idempotencyKeyDigest: String,
            expectedStateVersion: Long,
        ): CapacityWritePrecondition = CapacityWritePrecondition(
            idempotencyKeyDigest,
            expectedStateVersion,
            null,
        )
    }
}

/** Stable replay namespace deliberately excludes transient request/authentication identifiers. */
class CapacityIdempotencyScope private constructor(
    val tenantId: Identifier,
    val principalId: Identifier,
    val principalType: String,
    val operation: String,
    val target: ResourceScope,
    val workload: WorkloadKind,
    val idempotencyKeyDigest: String,
) {
    val scopeDigest: String = CapacityDigest("flowweft.capacity.idempotency-scope.v1")
        .add(tenantId.value)
        .add(principalType)
        .add(principalId.value)
        .add(operation)
        .add(target.bindingDigest)
        .add(workload.value)
        .add(idempotencyKeyDigest)
        .finish()

    override fun equals(other: Any?): Boolean =
        other is CapacityIdempotencyScope && scopeDigest == other.scopeDigest

    override fun hashCode(): Int = scopeDigest.hashCode()
    override fun toString(): String = "CapacityIdempotencyScope(<redacted>)"

    companion object {
        internal fun create(
            context: CapacityTrustedContext,
            operation: String,
            target: ResourceScope,
            workload: WorkloadKind,
            idempotencyKeyDigest: String,
        ): CapacityIdempotencyScope = CapacityIdempotencyScope(
            context.tenantId,
            context.principalId,
            context.principalType,
            requireCapacityCode(operation, "Capacity idempotency operation"),
            target,
            workload,
            requireCapacityDigest(idempotencyKeyDigest, "Capacity idempotency key"),
        )
    }
}

/** Exact, payload-free demand submitted to an atomic capacity provider. */
class CapacityAdmissionRequest(
    operationId: Identifier,
    val context: CapacityTrustedContext,
    val target: ResourceScope,
    val workload: WorkloadKind,
    demands: Collection<CapacityDemand>,
    permittedDegradations: Collection<CapacityDegradationCapability>,
    val precondition: CapacityWritePrecondition,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val operationId: Identifier = requireCapacityIdentifier(operationId, "Capacity admission operation identifier")
    val demands: List<CapacityDemand> = capacityList(
        demands.sortedBy { demand -> demand.dimension.bindingCode },
        CapacityContractLimits.MAX_LIMITS,
        "Capacity admission demands",
    )
    val permittedDegradations: Set<CapacityDegradationCapability> = capacitySet(
        permittedDegradations,
        CapacityContractLimits.MAX_DEGRADATIONS,
        "Capacity permitted degradations",
    )
    val idempotencyScope: CapacityIdempotencyScope
    val idempotencyBindingDigest: String
    val bindingDigest: String

    init {
        context.requirePurpose(CapacityPurpose.ADMISSION)
        context.requireFresh(requestedAt)
        require(target.level != CapacityScopeLevel.SYSTEM && target.tenantId == context.tenantId &&
            context.authorizedScope.appliesTo(target)
        ) {
            "Capacity admission target does not belong to the trusted tenant."
        }
        require(requestedAt >= context.initiatedAt && deadlineAt > requestedAt &&
            deadlineAt <= context.authorizationExpiresAt &&
            deadlineAt - requestedAt <= CapacityContractLimits.MAX_REQUEST_DURATION_MILLIS
        ) { "Capacity admission lifetime is invalid or exceeds current authorization." }
        require(this.demands.isNotEmpty() &&
            this.demands.map { demand -> demand.dimension.code }.toSet().size == this.demands.size
        ) { "Capacity admission requires unique dimension demands." }
        require(precondition.expectedPolicyResolutionDigest != null) {
            "Capacity admission requires an exact policy-resolution CAS digest."
        }
        idempotencyScope = CapacityIdempotencyScope.create(
            context,
            "capacity.admit",
            target,
            workload,
            precondition.idempotencyKeyDigest,
        )
        val replayDigest = CapacityDigest("flowweft.capacity.admission-idempotency-binding.v1")
            .add(idempotencyScope.scopeDigest)
            .add(target.bindingDigest)
            .add(workload.value)
            .add(precondition.bindingDigest)
        this.demands.forEach { demand ->
            replayDigest.add(demand.dimension.code).add(demand.dimension.unit.value).add(demand.amount)
        }
        this.permittedDegradations.sorted().forEach { capability -> replayDigest.add(capability.value) }
        idempotencyBindingDigest = replayDigest.finish()
        val digest = CapacityDigest("flowweft.capacity.admission-request.v1")
            .add(this.operationId.value)
            .add(context.bindingDigest)
            .add(idempotencyBindingDigest)
            .add(requestedAt)
            .add(deadlineAt)
        bindingDigest = digest.finish()
    }

    override fun toString(): String =
        "CapacityAdmissionRequest(workload=$workload, dimensions=${demands.size}, <redacted>)"
}

enum class CapacityAdmissionOutcome {
    ADMIT,
    THROTTLE,
    REJECT,
    DEGRADE,
}

/** Open reason vocabulary. Provider messages and exceptions have no field in this contract. */
class CapacityDecisionReason(value: String) {
    val value: String = requireCapacityCode(value, "Capacity decision reason")

    override fun equals(other: Any?): Boolean = other is CapacityDecisionReason && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val LIMIT_EXCEEDED = CapacityDecisionReason("limit_exceeded")
        @JvmField val WATERMARK_PRESSURE = CapacityDecisionReason("watermark_pressure")
        @JvmField val CAPACITY_DISABLED = CapacityDecisionReason("capacity_disabled")
        @JvmField val REQUIRED_CAPABILITY_UNAVAILABLE =
            CapacityDecisionReason("required_capability_unavailable")
    }
}

/** Canonical atomic outcome. ADMIT/DEGRADE reserve immediately; THROTTLE/REJECT never do. */
class CapacityAdmissionDecision private constructor(
    decisionId: Identifier,
    providerId: Identifier,
    val request: CapacityAdmissionRequest,
    val outcome: CapacityAdmissionOutcome,
    val usage: CapacityUsageSnapshot,
    val lease: CapacityReservationLease?,
    degradationCapabilities: Collection<CapacityDegradationCapability>,
    val retryAfterMillis: Long?,
    val reason: CapacityDecisionReason?,
    val decidedAt: Long,
    val expiresAt: Long,
) {
    val decisionId: Identifier = requireCapacityIdentifier(decisionId, "Capacity admission decision identifier")
    val providerId: Identifier = requireCapacityIdentifier(providerId, "Capacity admission provider identifier")
    val degradationCapabilities: Set<CapacityDegradationCapability> = capacitySet(
        degradationCapabilities,
        CapacityContractLimits.MAX_DEGRADATIONS,
        "Capacity decision degradations",
    )
    val decisionDigest: String

    init {
        request.context.requireFresh(decidedAt)
        require(decidedAt in request.requestedAt until request.deadlineAt &&
            expiresAt > decidedAt && expiresAt <= request.context.authorizationExpiresAt &&
            expiresAt <= usage.expiresAt && usage.observedAt == decidedAt && usage.isCurrent(decidedAt)
        ) { "Capacity admission decision lifetime or evidence time is invalid." }
        require(usage.providerId == this.providerId && usage.target == request.target &&
            usage.workload == request.workload &&
            usage.policyResolution.resolutionDigest == request.precondition.expectedPolicyResolutionDigest
        ) { "Capacity admission decision usage does not match the exact request policy and target." }
        val requestedAmounts = request.demands.associate { demand -> demand.dimension to demand.amount }
        require(requestedAmounts.keys.all { dimension -> usage.measureFor(dimension) != null }) {
            "Capacity admission decision omitted a requested capacity dimension."
        }
        val admitted = outcome == CapacityAdmissionOutcome.ADMIT || outcome == CapacityAdmissionOutcome.DEGRADE
        require(admitted == (lease != null)) {
            "Only admitted or explicitly degraded capacity decisions carry a reservation lease."
        }
        if (lease != null) {
            require(lease.providerId == this.providerId &&
                lease.reservationRequestBindingDigest == request.bindingDigest &&
                lease.demands.associate { demand -> demand.dimension to demand.amount } == requestedAmounts &&
                lease.stateVersion == usage.stateVersion && lease.isCurrent(decidedAt) &&
                expiresAt <= lease.expiresAt &&
                usage.measures.all { measure -> measure.total <= measure.effectiveLimit.limit }
            ) { "Capacity admission lease does not match its atomic usage snapshot." }
        } else {
            require(usage.stateVersion == request.precondition.expectedStateVersion) {
                "A non-reserving capacity decision cannot mutate capacity state."
            }
        }
        when (outcome) {
            CapacityAdmissionOutcome.ADMIT -> require(
                this.degradationCapabilities.isEmpty() && retryAfterMillis == null && reason == null,
            ) { "ADMIT cannot hide throttling, rejection or degradation semantics." }

            CapacityAdmissionOutcome.DEGRADE -> require(
                this.degradationCapabilities.isNotEmpty() && retryAfterMillis == null && reason != null &&
                    request.permittedDegradations.containsAll(this.degradationCapabilities) &&
                    usage.policyResolution.allowedDegradations.containsAll(this.degradationCapabilities),
            ) { "DEGRADE requires explicitly permitted, policy-approved capacity-only capabilities." }

            CapacityAdmissionOutcome.THROTTLE -> require(
                this.degradationCapabilities.isEmpty() && retryAfterMillis != null &&
                    retryAfterMillis > 0L && reason != null,
            ) { "THROTTLE requires a positive retry delay and no implicit degradation." }

            CapacityAdmissionOutcome.REJECT -> require(
                this.degradationCapabilities.isEmpty() && retryAfterMillis == null && reason != null,
            ) { "REJECT is definitive for the exact request and cannot imply a retry or degradation." }
        }
        val digest = CapacityDigest("flowweft.capacity.admission-decision.v1")
            .add(this.decisionId.value)
            .add(this.providerId.value)
            .add(request.bindingDigest)
            .add(outcome.name)
            .add(usage.snapshotDigest)
            .add(lease?.leaseDigest ?: "-")
            .add(retryAfterMillis ?: -1L)
            .add(reason?.value ?: "-")
            .add(decidedAt)
            .add(expiresAt)
        this.degradationCapabilities.sorted().forEach { capability -> digest.add(capability.value) }
        decisionDigest = digest.finish()
    }

    override fun toString(): String = "CapacityAdmissionDecision(outcome=$outcome, <redacted>)"

    companion object {
        @JvmStatic
        fun admit(
            decisionId: Identifier,
            providerId: Identifier,
            request: CapacityAdmissionRequest,
            usage: CapacityUsageSnapshot,
            lease: CapacityReservationLease,
            decidedAt: Long,
            expiresAt: Long,
        ): CapacityAdmissionDecision = CapacityAdmissionDecision(
            decisionId,
            providerId,
            request,
            CapacityAdmissionOutcome.ADMIT,
            usage,
            lease,
            emptyList(),
            null,
            null,
            decidedAt,
            expiresAt,
        )

        @JvmStatic
        fun degrade(
            decisionId: Identifier,
            providerId: Identifier,
            request: CapacityAdmissionRequest,
            usage: CapacityUsageSnapshot,
            lease: CapacityReservationLease,
            degradationCapabilities: Collection<CapacityDegradationCapability>,
            reason: CapacityDecisionReason,
            decidedAt: Long,
            expiresAt: Long,
        ): CapacityAdmissionDecision = CapacityAdmissionDecision(
            decisionId,
            providerId,
            request,
            CapacityAdmissionOutcome.DEGRADE,
            usage,
            lease,
            degradationCapabilities,
            null,
            reason,
            decidedAt,
            expiresAt,
        )

        @JvmStatic
        fun throttle(
            decisionId: Identifier,
            providerId: Identifier,
            request: CapacityAdmissionRequest,
            usage: CapacityUsageSnapshot,
            retryAfterMillis: Long,
            reason: CapacityDecisionReason,
            decidedAt: Long,
            expiresAt: Long,
        ): CapacityAdmissionDecision = CapacityAdmissionDecision(
            decisionId,
            providerId,
            request,
            CapacityAdmissionOutcome.THROTTLE,
            usage,
            null,
            emptyList(),
            retryAfterMillis,
            reason,
            decidedAt,
            expiresAt,
        )

        @JvmStatic
        fun reject(
            decisionId: Identifier,
            providerId: Identifier,
            request: CapacityAdmissionRequest,
            usage: CapacityUsageSnapshot,
            reason: CapacityDecisionReason,
            decidedAt: Long,
            expiresAt: Long,
        ): CapacityAdmissionDecision = CapacityAdmissionDecision(
            decisionId,
            providerId,
            request,
            CapacityAdmissionOutcome.REJECT,
            usage,
            null,
            emptyList(),
            null,
            reason,
            decidedAt,
            expiresAt,
        )
    }
}
