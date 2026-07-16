package ai.icen.fw.capacity.api

import ai.icen.fw.core.id.Identifier

/** Fenced reservation issued by an atomic ADMIT or DEGRADE decision. */
class CapacityReservationLease private constructor(
    reservationId: Identifier,
    leaseId: Identifier,
    providerId: Identifier,
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    val target: ResourceScope,
    val workload: WorkloadKind,
    demands: Collection<CapacityDemand>,
    reservationRequestBindingDigest: String,
    reservationIdempotencyScopeDigest: String,
    lastMutationBindingDigest: String,
    lastMutationIdempotencyScopeDigest: String,
    policyResolutionDigest: String,
    val fencingToken: Long,
    val stateVersion: Long,
    val acquiredAt: Long,
    val updatedAt: Long,
    val expiresAt: Long,
) {
    val reservationId: Identifier = requireCapacityIdentifier(reservationId, "Capacity reservation identifier")
    val leaseId: Identifier = requireCapacityIdentifier(leaseId, "Capacity lease identifier")
    val providerId: Identifier = requireCapacityIdentifier(providerId, "Capacity lease provider identifier")
    val tenantId: Identifier = requireCapacityIdentifier(tenantId, "Capacity lease tenant identifier")
    val principalId: Identifier = requireCapacityIdentifier(principalId, "Capacity lease principal identifier")
    val principalType: String = requireCapacityCode(principalType, "Capacity lease principal type")
    val demands: List<CapacityDemand> = capacityList(
        demands.sortedBy { demand -> demand.dimension.bindingCode },
        CapacityContractLimits.MAX_LIMITS,
        "Capacity lease demands",
    )
    val reservationRequestBindingDigest: String = requireCapacityDigest(
        reservationRequestBindingDigest,
        "Capacity reservation request binding",
    )
    val reservationIdempotencyScopeDigest: String = requireCapacityDigest(
        reservationIdempotencyScopeDigest,
        "Capacity reservation idempotency scope",
    )
    val lastMutationBindingDigest: String = requireCapacityDigest(
        lastMutationBindingDigest,
        "Capacity lease mutation binding",
    )
    val lastMutationIdempotencyScopeDigest: String = requireCapacityDigest(
        lastMutationIdempotencyScopeDigest,
        "Capacity lease mutation idempotency scope",
    )
    val policyResolutionDigest: String = requireCapacityDigest(
        policyResolutionDigest,
        "Capacity lease policy resolution",
    )
    val leaseDigest: String

    init {
        require(target.tenantId == this.tenantId && fencingToken > 0L && stateVersion > 0L &&
            acquiredAt >= 0L && updatedAt >= acquiredAt && expiresAt > updatedAt &&
            expiresAt - updatedAt <= CapacityContractLimits.MAX_LEASE_DURATION_MILLIS
        ) { "Capacity reservation lease identity, fence, version or lifetime is invalid." }
        require(this.demands.isNotEmpty() &&
            this.demands.map { demand -> demand.dimension.code }.toSet().size == this.demands.size
        ) { "Capacity reservation lease requires unique demands." }
        val digest = CapacityDigest("flowweft.capacity.reservation-lease.v1")
            .add(this.reservationId.value)
            .add(this.leaseId.value)
            .add(this.providerId.value)
            .add(this.tenantId.value)
            .add(this.principalType)
            .add(this.principalId.value)
            .add(target.bindingDigest)
            .add(workload.value)
            .add(this.reservationRequestBindingDigest)
            .add(this.reservationIdempotencyScopeDigest)
            .add(this.lastMutationBindingDigest)
            .add(this.lastMutationIdempotencyScopeDigest)
            .add(this.policyResolutionDigest)
            .add(fencingToken)
            .add(stateVersion)
            .add(acquiredAt)
            .add(updatedAt)
            .add(expiresAt)
        this.demands.forEach { demand ->
            digest.add(demand.dimension.code).add(demand.dimension.unit.value).add(demand.amount)
        }
        leaseDigest = digest.finish()
    }

    fun isCurrent(atTime: Long): Boolean = atTime >= acquiredAt && atTime < expiresAt

    override fun toString(): String =
        "CapacityReservationLease(workload=$workload, stateVersion=$stateVersion, <redacted>)"

    companion object {
        @JvmStatic
        fun issue(
            reservationId: Identifier,
            leaseId: Identifier,
            providerId: Identifier,
            request: CapacityAdmissionRequest,
            fencingToken: Long,
            stateVersion: Long,
            issuedAt: Long,
            expiresAt: Long,
        ): CapacityReservationLease {
            require(issuedAt in request.requestedAt until request.deadlineAt &&
                stateVersion > request.precondition.expectedStateVersion
            ) { "Capacity lease was not issued by the exact atomic admission transition." }
            return CapacityReservationLease(
                reservationId,
                leaseId,
                providerId,
                request.context.tenantId,
                request.context.principalId,
                request.context.principalType,
                request.target,
                request.workload,
                request.demands,
                request.bindingDigest,
                request.idempotencyScope.scopeDigest,
                request.bindingDigest,
                request.idempotencyScope.scopeDigest,
                requireNotNull(request.precondition.expectedPolicyResolutionDigest),
                fencingToken,
                stateVersion,
                issuedAt,
                issuedAt,
                expiresAt,
            )
        }

        @JvmStatic
        fun renewed(
            request: CapacityLeaseRenewalRequest,
            policyResolutionDigest: String,
            fencingToken: Long,
            stateVersion: Long,
            renewedAt: Long,
            expiresAt: Long,
        ): CapacityReservationLease {
            val current = request.lease
            require(policyResolutionDigest == request.precondition.expectedPolicyResolutionDigest &&
                renewedAt in request.requestedAt until request.deadlineAt &&
                fencingToken > current.fencingToken && stateVersion > current.stateVersion &&
                expiresAt > current.expiresAt && expiresAt <= request.requestedExpiresAt
            ) { "Capacity lease renewal does not match the current lease, policy or requested lifetime." }
            return CapacityReservationLease(
                current.reservationId,
                current.leaseId,
                current.providerId,
                current.tenantId,
                current.principalId,
                current.principalType,
                current.target,
                current.workload,
                current.demands,
                current.reservationRequestBindingDigest,
                current.reservationIdempotencyScopeDigest,
                request.bindingDigest,
                request.idempotencyScope.scopeDigest,
                policyResolutionDigest,
                fencingToken,
                stateVersion,
                current.acquiredAt,
                renewedAt,
                expiresAt,
            )
        }
    }
}

class CapacityLeaseRenewalRequest(
    operationId: Identifier,
    val context: CapacityTrustedContext,
    val lease: CapacityReservationLease,
    val precondition: CapacityWritePrecondition,
    val requestedExpiresAt: Long,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val operationId: Identifier = requireCapacityIdentifier(operationId, "Capacity renewal operation identifier")
    val idempotencyScope: CapacityIdempotencyScope
    val idempotencyBindingDigest: String
    val bindingDigest: String

    init {
        context.requirePurpose(CapacityPurpose.LEASE)
        context.requireFresh(requestedAt)
        require(context.tenantId == lease.tenantId && context.principalId == lease.principalId &&
            context.principalType == lease.principalType && context.authorizedScope.appliesTo(lease.target) &&
            lease.isCurrent(requestedAt)
        ) { "Capacity lease renewal does not belong to the current principal or current lease." }
        require(precondition.expectedStateVersion == lease.stateVersion &&
            precondition.expectedPolicyResolutionDigest != null
        ) { "Capacity lease renewal requires exact state and policy CAS evidence." }
        require(requestedExpiresAt > lease.expiresAt && requestedExpiresAt > requestedAt &&
            requestedExpiresAt - requestedAt <= CapacityContractLimits.MAX_LEASE_DURATION_MILLIS &&
            deadlineAt > requestedAt && deadlineAt <= context.authorizationExpiresAt &&
            deadlineAt - requestedAt <= CapacityContractLimits.MAX_REQUEST_DURATION_MILLIS
        ) { "Capacity lease renewal lifetime is invalid." }
        idempotencyScope = CapacityIdempotencyScope.create(
            context,
            "capacity.lease.renew",
            lease.target,
            lease.workload,
            precondition.idempotencyKeyDigest,
        )
        idempotencyBindingDigest = CapacityDigest("flowweft.capacity.lease-renewal-idempotency-binding.v1")
            .add(idempotencyScope.scopeDigest)
            .add(lease.leaseDigest)
            .add(precondition.bindingDigest)
            .add(requestedExpiresAt)
            .finish()
        bindingDigest = CapacityDigest("flowweft.capacity.lease-renewal-request.v1")
            .add(this.operationId.value)
            .add(context.bindingDigest)
            .add(idempotencyBindingDigest)
            .add(requestedAt)
            .add(deadlineAt)
            .finish()
    }

    override fun toString(): String = "CapacityLeaseRenewalRequest(<redacted>)"
}

class CapacityLeaseReleaseRequest(
    operationId: Identifier,
    val context: CapacityTrustedContext,
    val lease: CapacityReservationLease,
    val precondition: CapacityWritePrecondition,
    reasonCode: String,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val operationId: Identifier = requireCapacityIdentifier(operationId, "Capacity release operation identifier")
    val reasonCode: String = requireCapacityCode(reasonCode, "Capacity release reason")
    val idempotencyScope: CapacityIdempotencyScope
    val idempotencyBindingDigest: String
    val bindingDigest: String

    init {
        context.requirePurpose(CapacityPurpose.LEASE)
        context.requireFresh(requestedAt)
        require(context.tenantId == lease.tenantId && context.principalId == lease.principalId &&
            context.principalType == lease.principalType && context.authorizedScope.appliesTo(lease.target)
        ) { "Capacity lease release does not belong to the current principal." }
        require(precondition.expectedStateVersion == lease.stateVersion &&
            precondition.expectedPolicyResolutionDigest == null
        ) { "Capacity lease release requires exact lease-state CAS evidence only." }
        require(requestedAt >= lease.acquiredAt && deadlineAt > requestedAt &&
            deadlineAt <= context.authorizationExpiresAt &&
            deadlineAt - requestedAt <= CapacityContractLimits.MAX_REQUEST_DURATION_MILLIS
        ) { "Capacity lease release lifetime is invalid." }
        idempotencyScope = CapacityIdempotencyScope.create(
            context,
            "capacity.lease.release",
            lease.target,
            lease.workload,
            precondition.idempotencyKeyDigest,
        )
        idempotencyBindingDigest = CapacityDigest("flowweft.capacity.lease-release-idempotency-binding.v1")
            .add(idempotencyScope.scopeDigest)
            .add(lease.leaseDigest)
            .add(precondition.bindingDigest)
            .add(this.reasonCode)
            .finish()
        bindingDigest = CapacityDigest("flowweft.capacity.lease-release-request.v1")
            .add(this.operationId.value)
            .add(context.bindingDigest)
            .add(idempotencyBindingDigest)
            .add(requestedAt)
            .add(deadlineAt)
            .finish()
    }

    override fun toString(): String = "CapacityLeaseReleaseRequest(<redacted>)"
}

class CapacityLeaseRenewalReceipt(
    receiptId: Identifier,
    providerId: Identifier,
    val request: CapacityLeaseRenewalRequest,
    val renewedLease: CapacityReservationLease,
    val usage: CapacityUsageSnapshot,
    val decidedAt: Long,
) {
    val receiptId: Identifier = requireCapacityIdentifier(receiptId, "Capacity renewal receipt identifier")
    val providerId: Identifier = requireCapacityIdentifier(providerId, "Capacity renewal provider identifier")
    val receiptDigest: String

    init {
        request.context.requireFresh(decidedAt)
        require(decidedAt in request.requestedAt until request.deadlineAt &&
            renewedLease.providerId == this.providerId &&
            renewedLease.lastMutationBindingDigest == request.bindingDigest &&
            renewedLease.fencingToken > request.lease.fencingToken &&
            renewedLease.stateVersion > request.lease.stateVersion &&
            renewedLease.isCurrent(decidedAt) &&
            usage.providerId == this.providerId && usage.target == renewedLease.target &&
            usage.workload == renewedLease.workload && usage.stateVersion == renewedLease.stateVersion &&
            usage.policyResolution.resolutionDigest == renewedLease.policyResolutionDigest &&
            usage.observedAt == decidedAt && usage.isCurrent(decidedAt)
        ) { "Capacity lease renewal receipt is not one atomic fenced transition." }
        receiptDigest = CapacityDigest("flowweft.capacity.lease-renewal-receipt.v1")
            .add(this.receiptId.value)
            .add(this.providerId.value)
            .add(request.bindingDigest)
            .add(renewedLease.leaseDigest)
            .add(usage.snapshotDigest)
            .add(decidedAt)
            .finish()
    }
}

class CapacityLeaseReleaseReceipt(
    receiptId: Identifier,
    providerId: Identifier,
    val request: CapacityLeaseReleaseRequest,
    val usage: CapacityUsageSnapshot,
    val releasedStateVersion: Long,
    val releasedAt: Long,
) {
    val receiptId: Identifier = requireCapacityIdentifier(receiptId, "Capacity release receipt identifier")
    val providerId: Identifier = requireCapacityIdentifier(providerId, "Capacity release provider identifier")
    val receiptDigest: String

    init {
        request.context.requireFresh(releasedAt)
        require(releasedAt in request.requestedAt until request.deadlineAt &&
            releasedStateVersion > request.lease.stateVersion && usage.stateVersion == releasedStateVersion &&
            usage.providerId == this.providerId && usage.target == request.lease.target &&
            usage.workload == request.lease.workload && usage.observedAt == releasedAt && usage.isCurrent(releasedAt)
        ) { "Capacity lease release receipt is not one atomic fenced transition." }
        receiptDigest = CapacityDigest("flowweft.capacity.lease-release-receipt.v1")
            .add(this.receiptId.value)
            .add(this.providerId.value)
            .add(request.bindingDigest)
            .add(request.lease.leaseDigest)
            .add(usage.snapshotDigest)
            .add(releasedStateVersion)
            .add(releasedAt)
            .finish()
    }
}
