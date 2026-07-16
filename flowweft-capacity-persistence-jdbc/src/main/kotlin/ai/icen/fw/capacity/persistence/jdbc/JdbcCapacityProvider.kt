package ai.icen.fw.capacity.persistence.jdbc

import ai.icen.fw.capacity.api.CapacityAdmissionDecision
import ai.icen.fw.capacity.api.CapacityAdmissionOutcome
import ai.icen.fw.capacity.api.CapacityAdmissionRequest
import ai.icen.fw.capacity.api.CapacityDecisionReason
import ai.icen.fw.capacity.api.CapacityDoctorReport
import ai.icen.fw.capacity.api.CapacityDoctorRequest
import ai.icen.fw.capacity.api.CapacityDoctorSignal
import ai.icen.fw.capacity.api.CapacityDoctorSignalCode
import ai.icen.fw.capacity.api.CapacityDoctorStatus
import ai.icen.fw.capacity.api.CapacityLeaseReleaseReceipt
import ai.icen.fw.capacity.api.CapacityLeaseReleaseRequest
import ai.icen.fw.capacity.api.CapacityLeaseRenewalReceipt
import ai.icen.fw.capacity.api.CapacityLeaseRenewalRequest
import ai.icen.fw.capacity.api.CapacityMeasureSnapshot
import ai.icen.fw.capacity.api.CapacityPolicyResolution
import ai.icen.fw.capacity.api.CapacityProviderCapability
import ai.icen.fw.capacity.api.CapacityProviderDescriptor
import ai.icen.fw.capacity.api.CapacityProviderErrorCode
import ai.icen.fw.capacity.api.CapacityProviderResult
import ai.icen.fw.capacity.api.CapacityProviderSpi
import ai.icen.fw.capacity.api.CapacityReservationLease
import ai.icen.fw.capacity.api.CapacityScopeLevel
import ai.icen.fw.capacity.api.CapacitySnapshotRequest
import ai.icen.fw.capacity.api.CapacityUsageSnapshot
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationEvidence
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationPort
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationRequest
import ai.icen.fw.capacity.runtime.CapacityPolicySource
import ai.icen.fw.capacity.runtime.CapacityPolicySourceRequest
import ai.icen.fw.capacity.runtime.CapacityPolicySourceSnapshot
import ai.icen.fw.core.id.Identifier
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.function.LongSupplier
import javax.sql.DataSource

/**
 * Production JDBC capacity backend for PostgreSQL, MySQL 8 and KingbaseES.
 *
 * Mutations use two durable phases: a short PREPARED idempotency intent, followed by one
 * serializable state transition that atomically writes usage/reservation, canonical outcome and
 * outbox evidence. A connection/commit failure never causes an automatic second mutation.
 */
class JdbcCapacityProvider @JvmOverloads constructor(
    dataSource: DataSource,
    private val providerId: Identifier,
    private val configurationDigest: String,
    configuredDialect: CapacityJdbcDialect? = null,
    private val clock: LongSupplier = LongSupplier { System.currentTimeMillis() },
    private val descriptorLifetimeMillis: Long = 60_000L,
    private val leaseDurationMillis: Long = 60_000L,
    private val throttleRetryMillis: Long = 1_000L,
) : CapacityProviderSpi, CapacityPolicySource, CapacityOutcomeReconciliationPort {
    private val transactions = CapacityJdbcTransactions(dataSource, configuredDialect)
    private val policies = JdbcCapacityPolicyStore(dataSource, providerId, configuredDialect)

    init {
        require(configurationDigest.length == 64 && configurationDigest.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Capacity JDBC configuration digest is invalid."
        }
        require(descriptorLifetimeMillis > 0L && leaseDurationMillis in 1L..86_400_000L &&
            throttleRetryMillis > 0L
        ) { "Capacity JDBC provider lifetimes are invalid." }
    }

    fun putPolicy(request: CapacityPolicyPutRequest): CapacityPolicyPutReceipt = policies.put(request)

    override fun snapshot(request: CapacityPolicySourceRequest): CapacityPolicySourceSnapshot = policies.snapshot(request)

    override fun descriptor(): CapacityProviderDescriptor {
        val now = clock.asLong
        val observedAt = maxOf(0L, now - minOf(now, descriptorLifetimeMillis))
        return CapacityProviderDescriptor(
            providerId,
            CONTRACT_VERSION,
            setOf(
                CapacityProviderCapability.ATOMIC_ADMISSION,
                CapacityProviderCapability.HIERARCHICAL_POLICIES,
                CapacityProviderCapability.FENCED_LEASES,
                CapacityProviderCapability.USAGE_SNAPSHOTS,
                CapacityProviderCapability.DOCTOR_EVIDENCE,
            ),
            configurationDigest,
            observedAt,
            safeAdd(now, descriptorLifetimeMillis),
        )
    }

    override fun snapshot(request: CapacitySnapshotRequest): CapacityProviderResult<CapacityUsageSnapshot> {
        return try {
            val source = policies.snapshot(CapacityPolicySourceRequest(
                request.context,
                providerId,
                request.target,
                request.workload,
                request.requestedAt,
                request.deadlineAt,
            ))
            if (source.policies.isEmpty()) {
                CapacityProviderResult.failure(CapacityProviderErrorCode.NOT_FOUND)
            } else {
                val resolution = CapacityPolicyResolution.resolve(
                    request.target,
                    request.workload,
                    source.policies,
                    request.requestedAt,
                )
                val usage = transactions.read { connection, _ ->
                    loadUsage(
                        connection,
                        request.context.tenantId.value,
                        resolution,
                        request.requestedAt,
                        minOf(request.deadlineAt, resolution.expiresAt),
                        false,
                    )
                }
                CapacityProviderResult.success(usage)
            }
        } catch (_: Exception) {
            CapacityProviderResult.failure(CapacityProviderErrorCode.UNAVAILABLE)
        }
    }

    override fun admit(request: CapacityAdmissionRequest): CapacityProviderResult<CapacityAdmissionDecision> {
        if (request.context.tenantId != request.target.tenantId) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.UNAUTHORIZED)
        }
        return when (val preparation = prepare(
            request.context.tenantId.value,
            OP_ADMIT,
            request.idempotencyScope.scopeDigest,
            request.idempotencyBindingDigest,
            request.bindingDigest,
            request.requestedAt,
        )) {
            is IntentPreparation.Replay -> replayAdmission(preparation.row)
            IntentPreparation.Conflict -> CapacityProviderResult.failure(CapacityProviderErrorCode.STATE_CONFLICT)
            IntentPreparation.Unknown -> CapacityProviderResult.failure(CapacityProviderErrorCode.INTERNAL_FAILURE)
            is IntentPreparation.Prepared -> executeAdmission(request, preparation.rowId)
        }
    }

    override fun renew(request: CapacityLeaseRenewalRequest): CapacityProviderResult<CapacityLeaseRenewalReceipt> {
        if (request.lease.providerId != providerId || request.context.tenantId != request.lease.tenantId) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.UNAUTHORIZED)
        }
        return when (val preparation = prepare(
            request.context.tenantId.value,
            OP_RENEW,
            request.idempotencyScope.scopeDigest,
            request.idempotencyBindingDigest,
            request.bindingDigest,
            request.requestedAt,
        )) {
            is IntentPreparation.Replay -> replayRenewal(preparation.row)
            IntentPreparation.Conflict -> CapacityProviderResult.failure(CapacityProviderErrorCode.STATE_CONFLICT)
            IntentPreparation.Unknown -> CapacityProviderResult.failure(CapacityProviderErrorCode.INTERNAL_FAILURE)
            is IntentPreparation.Prepared -> executeRenewal(request, preparation.rowId)
        }
    }

    override fun release(request: CapacityLeaseReleaseRequest): CapacityProviderResult<CapacityLeaseReleaseReceipt> {
        if (request.lease.providerId != providerId || request.context.tenantId != request.lease.tenantId) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.UNAUTHORIZED)
        }
        return when (val preparation = prepare(
            request.context.tenantId.value,
            OP_RELEASE,
            request.idempotencyScope.scopeDigest,
            request.idempotencyBindingDigest,
            request.bindingDigest,
            request.requestedAt,
        )) {
            is IntentPreparation.Replay -> replayRelease(preparation.row)
            IntentPreparation.Conflict -> CapacityProviderResult.failure(CapacityProviderErrorCode.STATE_CONFLICT)
            IntentPreparation.Unknown -> CapacityProviderResult.failure(CapacityProviderErrorCode.INTERNAL_FAILURE)
            is IntentPreparation.Prepared -> executeRelease(request, preparation.rowId)
        }
    }

    override fun doctor(request: CapacityDoctorRequest): CapacityProviderResult<CapacityDoctorReport> = try {
        transactions.read { connection, _ ->
            connection.prepareStatement("SELECT COUNT(*) FROM fw_capacity_state WHERE tenant_id = ?").use { statement ->
                statement.setString(1, request.context.tenantId.value)
                statement.executeQuery().use { result -> check(result.next()) }
            }
        }
        val signal = CapacityDoctorSignal(
            Identifier("capacity-doctor-${UUID.randomUUID()}"),
            CapacityDoctorSignalCode.CAPACITY_WITHIN_LIMIT,
            CapacityDoctorStatus.READY,
            request.target.level,
            null,
            null,
            null,
            null,
            null,
            request.requestedAt,
            request.deadlineAt,
        )
        CapacityProviderResult.success(CapacityDoctorReport(
            providerId,
            CapacityDoctorStatus.READY,
            listOf(signal),
            request.requestedAt,
            request.deadlineAt,
        ))
    } catch (_: Exception) {
        CapacityProviderResult.failure(CapacityProviderErrorCode.UNAVAILABLE)
    }

    /** Exact, read-only idempotency lookup. It cannot invoke any mutation path. */
    override fun reconcile(request: CapacityOutcomeReconciliationRequest): CapacityOutcomeReconciliationEvidence {
        val reference = request.reference
        require(reference.providerId == providerId) { "Capacity reconciliation provider does not match." }
        val rowId = intentRowId(
            reference.tenantId.value,
            reference.operation,
            reference.idempotencyScopeDigest,
        )
        val row = transactions.read { connection, _ ->
            loadIntent(connection, reference.tenantId.value, rowId, false)
        }
        val observedAt = request.requestedAt
        val expiresAt = request.deadlineAt
        if (row == null || row.status == INTENT_PREPARED) {
            return CapacityOutcomeReconciliationEvidence.stillUnknown(
                request,
                reconciliationDigest(reference.referenceDigest, row),
                observedAt,
                expiresAt,
            )
        }
        if (row.bindingDigest != reference.idempotencyBindingDigest) {
            return CapacityOutcomeReconciliationEvidence.stillUnknown(
                request,
                reconciliationDigest(reference.referenceDigest, row),
                observedAt,
                expiresAt,
            )
        }
        if (row.status == INTENT_NOT_APPLIED) {
            return CapacityOutcomeReconciliationEvidence.confirmedNotApplied(
                request,
                reconciliationDigest(reference.referenceDigest, row),
                observedAt,
                expiresAt,
            )
        }
        require(row.status == INTENT_APPLIED && row.outcomeMemento != null) {
            "Capacity reconciliation idempotency row is invalid."
        }
        val evidenceDigest = reconciliationDigest(reference.referenceDigest, row)
        return when (row.outcomeKind) {
            OUTCOME_ADMISSION -> CapacityOutcomeReconciliationEvidence.appliedAdmission(
                request,
                decodeAdmission(row),
                evidenceDigest,
                observedAt,
                expiresAt,
            )
            OUTCOME_RENEWAL -> CapacityOutcomeReconciliationEvidence.appliedRenewal(
                request,
                decodeRenewal(row),
                evidenceDigest,
                observedAt,
                expiresAt,
            )
            OUTCOME_RELEASE -> CapacityOutcomeReconciliationEvidence.appliedRelease(
                request,
                decodeRelease(row),
                evidenceDigest,
                observedAt,
                expiresAt,
            )
            else -> error("Capacity reconciliation outcome kind is invalid.")
        }
    }

    private fun executeAdmission(
        request: CapacityAdmissionRequest,
        intentId: String,
    ): CapacityProviderResult<CapacityAdmissionDecision> = try {
        transactions.transaction { connection, dialect ->
            val tenantId = request.context.tenantId.value
            val now = operationTime(request.requestedAt, request.deadlineAt)
                ?: return@transaction failIntent(connection, tenantId, intentId, CapacityProviderErrorCode.UNAUTHORIZED, request.requestedAt)
            val intent = requirePreparedIntent(connection, tenantId, intentId, request.idempotencyBindingDigest)
                ?: return@transaction CapacityProviderResult.failure(CapacityProviderErrorCode.STATE_CONFLICT)
            val resolution = loadExactResolution(
                connection,
                tenantId,
                request.target.bindingDigest,
                request.workload.value,
                requireNotNull(request.precondition.expectedPolicyResolutionDigest),
                now,
                true,
            ) ?: return@transaction failIntent(
                connection, tenantId, intentId, CapacityProviderErrorCode.POLICY_CHANGED, now,
            )
            val stateId = stateRowId(tenantId, request.target.bindingDigest, request.workload.value)
            ensureState(connection, dialect, tenantId, stateId, request.target.bindingDigest, request.workload.value, now)
            val state = loadState(connection, tenantId, stateId, true)
                ?: return@transaction failIntent(connection, tenantId, intentId, CapacityProviderErrorCode.INTERNAL_FAILURE, now)
            if (state.stateVersion != request.precondition.expectedStateVersion) {
                return@transaction failIntent(
                    connection, tenantId, intentId, CapacityProviderErrorCode.STATE_CONFLICT, now,
                )
            }
            val current = loadMeasureValues(connection, tenantId, stateId, true)
            val demandByDimension = request.demands.associateBy { it.dimension.bindingCode }
            val projected = LinkedHashMap<String, MeasureValue>()
            resolution.effectiveLimits.forEach { limit ->
                val existing = current[limit.dimension.bindingCode] ?: MeasureValue(
                    limit.dimension.code, limit.dimension.unit.value, 0L, 0L,
                )
                val demand = demandByDimension[limit.dimension.bindingCode]?.amount ?: 0L
                projected[limit.dimension.bindingCode] = existing.copy(
                    reserved = safeAdd(existing.reserved, demand),
                )
            }
            if (demandByDimension.keys.any { it !in projected }) {
                return@transaction failIntent(
                    connection, tenantId, intentId, CapacityProviderErrorCode.POLICY_CHANGED, now,
                )
            }
            val exceeds = resolution.effectiveLimits.any { limit ->
                val value = requireNotNull(projected[limit.dimension.bindingCode])
                safeAdd(value.used, value.reserved) > limit.limit
            }
            val critical = !exceeds && resolution.effectiveLimits.any { limit ->
                val value = requireNotNull(projected[limit.dimension.bindingCode])
                safeAdd(value.used, value.reserved) >= limit.criticalWatermark
            }
            val allowed = resolution.allowedDegradations.intersect(request.permittedDegradations)
            val outcome = when {
                exceeds -> CapacityAdmissionOutcome.REJECT
                critical && allowed.isNotEmpty() -> CapacityAdmissionOutcome.DEGRADE
                critical -> CapacityAdmissionOutcome.THROTTLE
                else -> CapacityAdmissionOutcome.ADMIT
            }
            val reserves = outcome == CapacityAdmissionOutcome.ADMIT || outcome == CapacityAdmissionOutcome.DEGRADE
            val newStateVersion = if (reserves) safeAdd(state.stateVersion, 1L) else state.stateVersion
            val fence = state.nextFencingToken
            val usageValues = if (reserves) projected else currentWithPolicyDimensions(current, resolution)
            if (reserves) {
                writeMeasures(connection, tenantId, stateId, usageValues, now)
                updateState(
                    connection,
                    tenantId,
                    stateId,
                    state,
                    newStateVersion,
                    safeAdd(fence, 1L),
                    resolution.resolutionDigest,
                    now,
                )
            }
            val usage = captureUsage(
                resolution,
                usageValues,
                newStateVersion,
                now,
                minOf(request.context.authorizationExpiresAt, resolution.expiresAt),
            )
            val decisionId = nextId("capacity-decision")
            var chain: CapacityJdbcLeaseChain? = null
            val decision = when (outcome) {
                CapacityAdmissionOutcome.REJECT -> CapacityAdmissionDecision.reject(
                    decisionId, providerId, request, usage, CapacityDecisionReason.LIMIT_EXCEEDED,
                    now, usage.expiresAt,
                )
                CapacityAdmissionOutcome.THROTTLE -> CapacityAdmissionDecision.throttle(
                    decisionId, providerId, request, usage, throttleRetryMillis,
                    CapacityDecisionReason.WATERMARK_PRESSURE, now, usage.expiresAt,
                )
                CapacityAdmissionOutcome.ADMIT, CapacityAdmissionOutcome.DEGRADE -> {
                    val leaseExpiry = minOf(
                        safeAdd(now, leaseDurationMillis),
                        request.context.authorizationExpiresAt,
                        resolution.expiresAt,
                    )
                    if (leaseExpiry <= now) return@transaction failIntent(
                        connection, tenantId, intentId, CapacityProviderErrorCode.POLICY_CHANGED, now,
                    )
                    chain = CapacityJdbcLeaseChain(
                        request,
                        nextId("capacity-reservation"),
                        nextId("capacity-lease"),
                        providerId,
                        fence,
                        newStateVersion,
                        now,
                        leaseExpiry,
                    )
                    val lease = requireNotNull(chain).lease()
                    insertReservation(connection, tenantId, stateId, lease, requireNotNull(chain), now)
                    if (outcome == CapacityAdmissionOutcome.ADMIT) CapacityAdmissionDecision.admit(
                        decisionId, providerId, request, usage, lease, now, minOf(usage.expiresAt, lease.expiresAt),
                    ) else CapacityAdmissionDecision.degrade(
                        decisionId, providerId, request, usage, lease, allowed,
                        CapacityDecisionReason.WATERMARK_PRESSURE, now, minOf(usage.expiresAt, lease.expiresAt),
                    )
                }
            }
            completeIntent(
                connection,
                tenantId,
                intent,
                OUTCOME_ADMISSION,
                CapacityJdbcCanonicalCodec.encodeAdmission(decision, chain),
                decision.decisionDigest,
                now,
            )
            insertOutbox(connection, tenantId, providerId.value, OP_ADMIT, decision.decisionDigest, now)
            CapacityProviderResult.success(decision)
        }
    } catch (_: Exception) {
        CapacityProviderResult.failure(CapacityProviderErrorCode.INTERNAL_FAILURE)
    }

    private fun executeRenewal(
        request: CapacityLeaseRenewalRequest,
        intentId: String,
    ): CapacityProviderResult<CapacityLeaseRenewalReceipt> = try {
        transactions.transaction { connection, _ ->
            val tenantId = request.context.tenantId.value
            val now = operationTime(request.requestedAt, request.deadlineAt)
                ?: return@transaction failIntent(connection, tenantId, intentId, CapacityProviderErrorCode.UNAUTHORIZED, request.requestedAt)
            val intent = requirePreparedIntent(connection, tenantId, intentId, request.idempotencyBindingDigest)
                ?: return@transaction CapacityProviderResult.failure(CapacityProviderErrorCode.STATE_CONFLICT)
            val resolution = loadExactResolution(
                connection,
                tenantId,
                request.lease.target.bindingDigest,
                request.lease.workload.value,
                requireNotNull(request.precondition.expectedPolicyResolutionDigest),
                now,
                true,
            ) ?: return@transaction failIntent(
                connection, tenantId, intentId, CapacityProviderErrorCode.POLICY_CHANGED, now,
            )
            val reservation = loadReservation(connection, tenantId, request.lease.reservationId.value, true)
                ?: return@transaction failIntent(connection, tenantId, intentId, CapacityProviderErrorCode.NOT_FOUND, now)
            if (reservation.status != RESERVATION_ACTIVE || !reservation.lease.isCurrent(now)
            ) return@transaction failIntent(
                connection, tenantId, intentId, CapacityProviderErrorCode.LEASE_EXPIRED, now,
            )
            if (reservation.lease.leaseDigest != request.lease.leaseDigest ||
                reservation.lease.stateVersion != request.lease.stateVersion ||
                reservation.lease.fencingToken != request.lease.fencingToken
            ) return@transaction failIntent(
                connection, tenantId, intentId, CapacityProviderErrorCode.STATE_CONFLICT, now,
            )
            val state = loadState(connection, tenantId, reservation.stateId, true)
                ?: return@transaction failIntent(connection, tenantId, intentId, CapacityProviderErrorCode.NOT_FOUND, now)
            val newStateVersion = safeAdd(state.stateVersion, 1L)
            val fence = state.nextFencingToken
            val renewedExpiry = minOf(request.requestedExpiresAt, resolution.expiresAt)
            if (renewedExpiry <= request.lease.expiresAt) return@transaction failIntent(
                connection, tenantId, intentId, CapacityProviderErrorCode.POLICY_CHANGED, now,
            )
            val step = CapacityJdbcRenewalStep(
                request.operationId,
                request.context,
                request.precondition,
                request.requestedExpiresAt,
                request.requestedAt,
                request.deadlineAt,
                resolution.resolutionDigest,
                fence,
                newStateVersion,
                now,
                renewedExpiry,
            )
            val chain = reservation.chain.copy(renewals = reservation.chain.renewals + step)
            val renewedLease = chain.lease()
            updateState(
                connection, tenantId, reservation.stateId, state, newStateVersion,
                safeAdd(fence, 1L), resolution.resolutionDigest, now,
            )
            updateReservation(connection, tenantId, reservation, renewedLease, chain, now)
            val usage = loadUsageFromState(
                connection,
                tenantId,
                reservation.stateId,
                resolution,
                newStateVersion,
                now,
                minOf(request.deadlineAt, resolution.expiresAt),
                true,
            )
            val receipt = CapacityLeaseRenewalReceipt(
                nextId("capacity-renewal-receipt"), providerId, request, renewedLease, usage, now,
            )
            completeIntent(
                connection, tenantId, intent, OUTCOME_RENEWAL,
                CapacityJdbcCanonicalCodec.encodeRenewal(receipt, chain), receipt.receiptDigest, now,
            )
            insertOutbox(connection, tenantId, providerId.value, OP_RENEW, receipt.receiptDigest, now)
            CapacityProviderResult.success(receipt)
        }
    } catch (_: Exception) {
        CapacityProviderResult.failure(CapacityProviderErrorCode.INTERNAL_FAILURE)
    }

    private fun executeRelease(
        request: CapacityLeaseReleaseRequest,
        intentId: String,
    ): CapacityProviderResult<CapacityLeaseReleaseReceipt> = try {
        transactions.transaction { connection, _ ->
            val tenantId = request.context.tenantId.value
            val now = operationTime(request.requestedAt, request.deadlineAt)
                ?: return@transaction failIntent(connection, tenantId, intentId, CapacityProviderErrorCode.UNAUTHORIZED, request.requestedAt)
            val intent = requirePreparedIntent(connection, tenantId, intentId, request.idempotencyBindingDigest)
                ?: return@transaction CapacityProviderResult.failure(CapacityProviderErrorCode.STATE_CONFLICT)
            val resolution = loadExactResolution(
                connection,
                tenantId,
                request.lease.target.bindingDigest,
                request.lease.workload.value,
                request.lease.policyResolutionDigest,
                now,
                false,
            ) ?: return@transaction failIntent(
                connection, tenantId, intentId, CapacityProviderErrorCode.POLICY_CHANGED, now,
            )
            val reservation = loadReservation(connection, tenantId, request.lease.reservationId.value, true)
                ?: return@transaction failIntent(connection, tenantId, intentId, CapacityProviderErrorCode.NOT_FOUND, now)
            if (reservation.status != RESERVATION_ACTIVE || !reservation.lease.isCurrent(now)
            ) return@transaction failIntent(
                connection, tenantId, intentId, CapacityProviderErrorCode.LEASE_EXPIRED, now,
            )
            if (reservation.lease.leaseDigest != request.lease.leaseDigest ||
                reservation.lease.stateVersion != request.lease.stateVersion ||
                reservation.lease.fencingToken != request.lease.fencingToken
            ) return@transaction failIntent(
                connection, tenantId, intentId, CapacityProviderErrorCode.STATE_CONFLICT, now,
            )
            val state = loadState(connection, tenantId, reservation.stateId, true)
                ?: return@transaction failIntent(connection, tenantId, intentId, CapacityProviderErrorCode.NOT_FOUND, now)
            val values = loadMeasureValues(connection, tenantId, reservation.stateId, true).toMutableMap()
            request.lease.demands.forEach { demand ->
                val key = demand.dimension.bindingCode
                val current = values[key] ?: return@transaction failIntent(
                    connection, tenantId, intentId, CapacityProviderErrorCode.STATE_CONFLICT, now,
                )
                if (current.reserved < demand.amount) return@transaction failIntent(
                    connection, tenantId, intentId, CapacityProviderErrorCode.STATE_CONFLICT, now,
                )
                values[key] = current.copy(reserved = current.reserved - demand.amount)
            }
            val newStateVersion = safeAdd(state.stateVersion, 1L)
            writeMeasures(connection, tenantId, reservation.stateId, values, now)
            updateState(
                connection, tenantId, reservation.stateId, state, newStateVersion,
                safeAdd(state.nextFencingToken, 1L), resolution.resolutionDigest, now,
            )
            releaseReservation(connection, tenantId, reservation, now)
            val usage = captureUsage(
                resolution,
                currentWithPolicyDimensions(values, resolution),
                newStateVersion,
                now,
                minOf(request.deadlineAt, resolution.expiresAt),
            )
            val receipt = CapacityLeaseReleaseReceipt(
                nextId("capacity-release-receipt"), providerId, request, usage, newStateVersion, now,
            )
            completeIntent(
                connection, tenantId, intent, OUTCOME_RELEASE,
                CapacityJdbcCanonicalCodec.encodeRelease(receipt, reservation.chain), receipt.receiptDigest, now,
            )
            insertOutbox(connection, tenantId, providerId.value, OP_RELEASE, receipt.receiptDigest, now)
            CapacityProviderResult.success(receipt)
        }
    } catch (_: Exception) {
        CapacityProviderResult.failure(CapacityProviderErrorCode.INTERNAL_FAILURE)
    }

    private fun prepare(
        tenantId: String,
        operation: String,
        scopeDigest: String,
        bindingDigest: String,
        requestBindingDigest: String,
        now: Long,
    ): IntentPreparation {
        val rowId = intentRowId(tenantId, operation, scopeDigest)
        try {
            transactions.transaction { connection, _ ->
                connection.prepareStatement(
                    """INSERT INTO fw_capacity_idempotency
                       (id, tenant_id, provider_id, operation_code, scope_digest, binding_digest,
                        request_binding_digest, status, outcome_kind, outcome_memento,
                        outcome_memento_digest, outcome_digest, prepared_time, completed_time,
                        created_time, updated_time)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                ).use { statement ->
                    statement.setString(1, rowId)
                    statement.setString(2, tenantId)
                    statement.setString(3, providerId.value)
                    statement.setString(4, operation)
                    statement.setString(5, scopeDigest)
                    statement.setString(6, bindingDigest)
                    statement.setString(7, requestBindingDigest)
                    statement.setString(8, INTENT_PREPARED)
                    statement.setObject(9, null)
                    statement.setObject(10, null)
                    statement.setObject(11, null)
                    statement.setObject(12, null)
                    statement.setLong(13, now)
                    statement.setObject(14, null)
                    statement.setLong(15, now)
                    statement.setLong(16, now)
                    statement.executeUpdate()
                }
            }
            return IntentPreparation.Prepared(rowId)
        } catch (failure: SQLException) {
            if (!failure.isIntegrityViolation() && failure.cause !is SQLException) return IntentPreparation.Unknown
        } catch (_: Exception) {
            return IntentPreparation.Unknown
        }
        val existing = try {
            transactions.read { connection, _ -> loadIntent(connection, tenantId, rowId, false) }
        } catch (_: Exception) {
            null
        } ?: return IntentPreparation.Unknown
        return when (classifyCapacityJdbcIntent(
            existing.bindingDigest,
            existing.operation,
            existing.scopeDigest,
            existing.status,
            bindingDigest,
            operation,
            scopeDigest,
        )) {
            CapacityJdbcIntentDecision.REPLAY -> IntentPreparation.Replay(existing)
            CapacityJdbcIntentDecision.CONFLICT -> IntentPreparation.Conflict
            CapacityJdbcIntentDecision.UNKNOWN -> IntentPreparation.Unknown
        }
    }

    private fun requirePreparedIntent(
        connection: Connection,
        tenantId: String,
        rowId: String,
        bindingDigest: String,
    ): IntentRow? = loadIntent(connection, tenantId, rowId, true)?.takeIf {
        it.status == INTENT_PREPARED && it.bindingDigest == bindingDigest
    }

    private fun <T> failIntent(
        connection: Connection,
        tenantId: String,
        intentId: String,
        error: CapacityProviderErrorCode,
        now: Long,
    ): CapacityProviderResult<T> {
        connection.prepareStatement(
            """UPDATE fw_capacity_idempotency SET status = ?, completed_time = ?, updated_time = ?
               WHERE tenant_id = ? AND id = ? AND status = ?""".trimIndent(),
        ).use { statement ->
            statement.setString(1, INTENT_NOT_APPLIED)
            statement.setLong(2, now)
            statement.setLong(3, now)
            statement.setString(4, tenantId)
            statement.setString(5, intentId)
            statement.setString(6, INTENT_PREPARED)
            check(statement.executeUpdate() == 1) { "Capacity idempotency intent changed unexpectedly." }
        }
        return CapacityProviderResult.failure(error)
    }

    private fun completeIntent(
        connection: Connection,
        tenantId: String,
        intent: IntentRow,
        outcomeKind: String,
        memento: ByteArray,
        outcomeDigest: String,
        now: Long,
    ) {
        connection.prepareStatement(
            """UPDATE fw_capacity_idempotency SET status = ?, outcome_kind = ?, outcome_memento = ?,
                      outcome_memento_digest = ?, outcome_digest = ?, completed_time = ?, updated_time = ?
               WHERE tenant_id = ? AND id = ? AND status = ? AND binding_digest = ?""".trimIndent(),
        ).use { statement ->
            statement.setString(1, INTENT_APPLIED)
            statement.setString(2, outcomeKind)
            statement.setBytes(3, memento)
            statement.setString(4, CapacityJdbcDigests.bytes(memento))
            statement.setString(5, outcomeDigest)
            statement.setLong(6, now)
            statement.setLong(7, now)
            statement.setString(8, tenantId)
            statement.setString(9, intent.id)
            statement.setString(10, INTENT_PREPARED)
            statement.setString(11, intent.bindingDigest)
            check(statement.executeUpdate() == 1) { "Capacity idempotency completion lost its fence." }
        }
    }

    private fun loadIntent(
        connection: Connection,
        tenantId: String,
        rowId: String,
        forUpdate: Boolean,
    ): IntentRow? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        connection.prepareStatement(
            """SELECT id, operation_code, scope_digest, binding_digest, request_binding_digest,
                      status, outcome_kind, outcome_memento, outcome_memento_digest, outcome_digest,
                      prepared_time, completed_time
               FROM fw_capacity_idempotency WHERE tenant_id = ? AND id = ?$suffix""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, rowId)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                val memento = result.getBytes("outcome_memento")
                val mementoDigest = result.getString("outcome_memento_digest")
                if (memento != null) require(CapacityJdbcDigests.bytes(memento) == mementoDigest) {
                    "Capacity outcome memento digest is invalid."
                }
                return IntentRow(
                    result.getString("id"), result.getString("operation_code"),
                    result.getString("scope_digest"), result.getString("binding_digest"),
                    result.getString("request_binding_digest"), result.getString("status"),
                    result.getString("outcome_kind"), memento, mementoDigest,
                    result.getString("outcome_digest"), result.getLong("prepared_time"),
                    result.getLong("completed_time").takeUnless { result.wasNull() },
                )
            }
        }
    }

    private fun loadExactResolution(
        connection: Connection,
        tenantId: String,
        targetDigest: String,
        workload: String,
        resolutionDigest: String,
        at: Long,
        requireCurrentRevision: Boolean,
    ): CapacityPolicyResolution? {
        val rowId = CapacityJdbcDigests.rowId(
            "capacity-policy-snapshot",
            tenantId,
            providerId.value,
            targetDigest,
            workload,
            resolutionDigest,
        )
        connection.prepareStatement(
            """SELECT source_revision_digest, resolution_memento, expires_time
               FROM fw_capacity_policy_snapshot
               WHERE tenant_id = ? AND id = ? AND provider_id = ? AND target_digest = ?
                 AND workload_kind = ? AND resolution_digest = ? AND observed_time <= ? AND expires_time > ?
               FOR UPDATE""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, rowId)
            statement.setString(3, providerId.value)
            statement.setString(4, targetDigest)
            statement.setString(5, workload)
            statement.setString(6, resolutionDigest)
            statement.setLong(7, at)
            statement.setLong(8, at)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                val sourceRevision = result.getString(1)
                if (requireCurrentRevision && sourceRevision != currentPolicyRevision(connection, tenantId, at)) return null
                val resolution = CapacityJdbcCanonicalCodec.decodeResolution(result.getBytes(2))
                require(resolution.resolutionDigest == resolutionDigest &&
                    resolution.target.bindingDigest == targetDigest && resolution.workload.value == workload &&
                    resolution.isCurrent(at)
                ) { "Capacity policy snapshot memento is invalid." }
                return resolution
            }
        }
    }

    private fun currentPolicyRevision(connection: Connection, tenantId: String, at: Long): String {
        val digests = mutableListOf<String>()
        connection.prepareStatement(
            """SELECT binding_digest FROM fw_capacity_policy
               WHERE tenant_id = ? AND enabled = ? AND effective_time <= ? AND expires_time > ?
               ORDER BY binding_digest""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setBoolean(2, true)
            statement.setLong(3, at)
            statement.setLong(4, at)
            statement.executeQuery().use { result -> while (result.next()) digests += result.getString(1) }
        }
        return CapacityJdbcDigests.digest(
            "flowweft.capacity.jdbc.policy-source.v1",
            tenantId,
            *digests.toTypedArray(),
        )
    }

    private fun ensureState(
        connection: Connection,
        dialect: CapacityJdbcDialect,
        tenantId: String,
        stateId: String,
        targetDigest: String,
        workload: String,
        now: Long,
    ) {
        val sql = when (dialect) {
            CapacityJdbcDialect.POSTGRESQL, CapacityJdbcDialect.KINGBASE ->
                """INSERT INTO fw_capacity_state
                   (id, tenant_id, provider_id, target_digest, workload_kind, state_version,
                    next_fencing_token, policy_resolution_digest, created_time, updated_time)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING""".trimIndent()
            CapacityJdbcDialect.MYSQL ->
                """INSERT INTO fw_capacity_state
                   (id, tenant_id, provider_id, target_digest, workload_kind, state_version,
                    next_fencing_token, policy_resolution_digest, created_time, updated_time)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = VALUES(id)""".trimIndent()
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, stateId)
            statement.setString(2, tenantId)
            statement.setString(3, providerId.value)
            statement.setString(4, targetDigest)
            statement.setString(5, workload)
            statement.setLong(6, 0L)
            statement.setLong(7, 1L)
            statement.setObject(8, null)
            statement.setLong(9, now)
            statement.setLong(10, now)
            statement.executeUpdate()
        }
    }

    private fun loadState(connection: Connection, tenantId: String, stateId: String, forUpdate: Boolean): StateRow? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        connection.prepareStatement(
            """SELECT id, state_version, next_fencing_token, policy_resolution_digest
               FROM fw_capacity_state WHERE tenant_id = ? AND id = ? AND provider_id = ?$suffix""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, stateId)
            statement.setString(3, providerId.value)
            statement.executeQuery().use { result -> return if (result.next()) StateRow(
                result.getString(1), result.getLong(2), result.getLong(3), result.getString(4),
            ) else null }
        }
    }

    private fun updateState(
        connection: Connection,
        tenantId: String,
        stateId: String,
        previous: StateRow,
        newStateVersion: Long,
        nextFence: Long,
        policyDigest: String,
        now: Long,
    ) {
        connection.prepareStatement(
            """UPDATE fw_capacity_state SET state_version = ?, next_fencing_token = ?,
                      policy_resolution_digest = ?, updated_time = ?
               WHERE tenant_id = ? AND id = ? AND provider_id = ? AND state_version = ?
                 AND next_fencing_token = ?""".trimIndent(),
        ).use { statement ->
            statement.setLong(1, newStateVersion)
            statement.setLong(2, nextFence)
            statement.setString(3, policyDigest)
            statement.setLong(4, now)
            statement.setString(5, tenantId)
            statement.setString(6, stateId)
            statement.setString(7, providerId.value)
            statement.setLong(8, previous.stateVersion)
            statement.setLong(9, previous.nextFencingToken)
            check(statement.executeUpdate() == 1) { "Capacity state CAS failed." }
        }
    }

    private fun loadMeasureValues(
        connection: Connection,
        tenantId: String,
        stateId: String,
        forUpdate: Boolean,
    ): Map<String, MeasureValue> {
        val values = LinkedHashMap<String, MeasureValue>()
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        connection.prepareStatement(
            """SELECT dimension_code, unit_code, used_value, reserved_value
               FROM fw_capacity_measure WHERE tenant_id = ? AND state_id = ? ORDER BY dimension_code$suffix""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, stateId)
            statement.executeQuery().use { result -> while (result.next()) {
                val value = MeasureValue(result.getString(1), result.getString(2), result.getLong(3), result.getLong(4))
                require(value.used >= 0L && value.reserved >= 0L) { "Capacity measure row is negative." }
                values["${value.dimensionCode}:${value.unitCode}"] = value
            } }
        }
        return values
    }

    private fun writeMeasures(
        connection: Connection,
        tenantId: String,
        stateId: String,
        values: Map<String, MeasureValue>,
        now: Long,
    ) {
        values.values.forEach { value ->
            val updated = connection.prepareStatement(
                """UPDATE fw_capacity_measure SET used_value = ?, reserved_value = ?, updated_time = ?
                   WHERE tenant_id = ? AND state_id = ? AND dimension_code = ? AND unit_code = ?""".trimIndent(),
            ).use { statement ->
                statement.setLong(1, value.used)
                statement.setLong(2, value.reserved)
                statement.setLong(3, now)
                statement.setString(4, tenantId)
                statement.setString(5, stateId)
                statement.setString(6, value.dimensionCode)
                statement.setString(7, value.unitCode)
                statement.executeUpdate()
            }
            if (updated == 0) connection.prepareStatement(
                """INSERT INTO fw_capacity_measure
                   (id, tenant_id, state_id, dimension_code, unit_code, used_value, reserved_value,
                    created_time, updated_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
            ).use { statement ->
                statement.setString(1, CapacityJdbcDigests.rowId(
                    "capacity-measure", tenantId, stateId, value.dimensionCode, value.unitCode,
                ))
                statement.setString(2, tenantId)
                statement.setString(3, stateId)
                statement.setString(4, value.dimensionCode)
                statement.setString(5, value.unitCode)
                statement.setLong(6, value.used)
                statement.setLong(7, value.reserved)
                statement.setLong(8, now)
                statement.setLong(9, now)
                statement.executeUpdate()
            }
        }
    }

    private fun loadUsage(
        connection: Connection,
        tenantId: String,
        resolution: CapacityPolicyResolution,
        observedAt: Long,
        expiresAt: Long,
        forUpdate: Boolean,
    ): CapacityUsageSnapshot {
        val stateId = stateRowId(tenantId, resolution.target.bindingDigest, resolution.workload.value)
        val state = loadState(connection, tenantId, stateId, forUpdate)
        return loadUsageFromState(
            connection,
            tenantId,
            stateId,
            resolution,
            state?.stateVersion ?: 0L,
            observedAt,
            expiresAt,
            forUpdate,
        )
    }

    private fun loadUsageFromState(
        connection: Connection,
        tenantId: String,
        stateId: String,
        resolution: CapacityPolicyResolution,
        stateVersion: Long,
        observedAt: Long,
        expiresAt: Long,
        forUpdate: Boolean,
    ): CapacityUsageSnapshot = captureUsage(
        resolution,
        currentWithPolicyDimensions(loadMeasureValues(connection, tenantId, stateId, forUpdate), resolution),
        stateVersion,
        observedAt,
        expiresAt,
    )

    private fun captureUsage(
        resolution: CapacityPolicyResolution,
        values: Map<String, MeasureValue>,
        stateVersion: Long,
        observedAt: Long,
        expiresAt: Long,
    ): CapacityUsageSnapshot {
        require(expiresAt > observedAt) { "Capacity usage evidence lifetime is exhausted." }
        val measures = resolution.effectiveLimits.map { limit ->
            val value = values[limit.dimension.bindingCode] ?: MeasureValue(
                limit.dimension.code, limit.dimension.unit.value, 0L, 0L,
            )
            require(value.dimensionCode == limit.dimension.code && value.unitCode == limit.dimension.unit.value) {
                "Capacity measure unit does not match policy."
            }
            CapacityMeasureSnapshot(limit, value.used, value.reserved)
        }
        return CapacityUsageSnapshot.capture(
            providerId, resolution, measures, stateVersion, observedAt, expiresAt,
        )
    }

    private fun currentWithPolicyDimensions(
        current: Map<String, MeasureValue>,
        resolution: CapacityPolicyResolution,
    ): Map<String, MeasureValue> {
        val values = LinkedHashMap(current)
        resolution.effectiveLimits.forEach { limit -> values.putIfAbsent(
            limit.dimension.bindingCode,
            MeasureValue(limit.dimension.code, limit.dimension.unit.value, 0L, 0L),
        ) }
        return values
    }

    private fun insertReservation(
        connection: Connection,
        tenantId: String,
        stateId: String,
        lease: CapacityReservationLease,
        chain: CapacityJdbcLeaseChain,
        now: Long,
    ) {
        val memento = CapacityJdbcCanonicalCodec.encodeLeaseChain(chain)
        connection.prepareStatement(
            """INSERT INTO fw_capacity_reservation
               (id, tenant_id, state_id, lease_id, provider_id, target_digest, workload_kind,
                lease_digest, fencing_token, state_version, policy_resolution_digest, lease_memento,
                lease_memento_digest, status, acquired_time, updated_lease_time, expires_time,
                released_time, created_time, updated_time)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
        ).use { statement ->
            statement.setString(1, lease.reservationId.value)
            statement.setString(2, tenantId)
            statement.setString(3, stateId)
            statement.setString(4, lease.leaseId.value)
            statement.setString(5, providerId.value)
            statement.setString(6, lease.target.bindingDigest)
            statement.setString(7, lease.workload.value)
            statement.setString(8, lease.leaseDigest)
            statement.setLong(9, lease.fencingToken)
            statement.setLong(10, lease.stateVersion)
            statement.setString(11, lease.policyResolutionDigest)
            statement.setBytes(12, memento)
            statement.setString(13, CapacityJdbcDigests.bytes(memento))
            statement.setString(14, RESERVATION_ACTIVE)
            statement.setLong(15, lease.acquiredAt)
            statement.setLong(16, lease.updatedAt)
            statement.setLong(17, lease.expiresAt)
            statement.setObject(18, null)
            statement.setLong(19, now)
            statement.setLong(20, now)
            statement.executeUpdate()
        }
    }

    private fun loadReservation(
        connection: Connection,
        tenantId: String,
        reservationId: String,
        forUpdate: Boolean,
    ): ReservationRow? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        connection.prepareStatement(
            """SELECT id, state_id, lease_digest, lease_memento, lease_memento_digest, status
               FROM fw_capacity_reservation
               WHERE tenant_id = ? AND id = ? AND provider_id = ?$suffix""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, reservationId)
            statement.setString(3, providerId.value)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                val memento = result.getBytes("lease_memento")
                require(CapacityJdbcDigests.bytes(memento) == result.getString("lease_memento_digest")) {
                    "Capacity reservation memento digest is invalid."
                }
                val chain = CapacityJdbcCanonicalCodec.decodeLeaseChain(memento)
                val lease = chain.lease()
                require(lease.reservationId.value == reservationId &&
                    lease.leaseDigest == result.getString("lease_digest") && lease.tenantId.value == tenantId &&
                    lease.providerId == providerId
                ) { "Capacity reservation row does not match its memento." }
                return ReservationRow(
                    result.getString("id"), result.getString("state_id"), lease, chain, result.getString("status"),
                )
            }
        }
    }

    private fun updateReservation(
        connection: Connection,
        tenantId: String,
        previous: ReservationRow,
        lease: CapacityReservationLease,
        chain: CapacityJdbcLeaseChain,
        now: Long,
    ) {
        val memento = CapacityJdbcCanonicalCodec.encodeLeaseChain(chain)
        connection.prepareStatement(
            """UPDATE fw_capacity_reservation SET lease_digest = ?, fencing_token = ?, state_version = ?,
                      policy_resolution_digest = ?, lease_memento = ?, lease_memento_digest = ?,
                      updated_lease_time = ?, expires_time = ?, updated_time = ?
               WHERE tenant_id = ? AND id = ? AND provider_id = ? AND status = ?
                 AND lease_digest = ? AND fencing_token = ?""".trimIndent(),
        ).use { statement ->
            statement.setString(1, lease.leaseDigest)
            statement.setLong(2, lease.fencingToken)
            statement.setLong(3, lease.stateVersion)
            statement.setString(4, lease.policyResolutionDigest)
            statement.setBytes(5, memento)
            statement.setString(6, CapacityJdbcDigests.bytes(memento))
            statement.setLong(7, lease.updatedAt)
            statement.setLong(8, lease.expiresAt)
            statement.setLong(9, now)
            statement.setString(10, tenantId)
            statement.setString(11, previous.id)
            statement.setString(12, providerId.value)
            statement.setString(13, RESERVATION_ACTIVE)
            statement.setString(14, previous.lease.leaseDigest)
            statement.setLong(15, previous.lease.fencingToken)
            check(statement.executeUpdate() == 1) { "Capacity reservation fencing CAS failed." }
        }
    }

    private fun releaseReservation(
        connection: Connection,
        tenantId: String,
        previous: ReservationRow,
        now: Long,
    ) {
        connection.prepareStatement(
            """UPDATE fw_capacity_reservation SET status = ?, released_time = ?, updated_time = ?
               WHERE tenant_id = ? AND id = ? AND provider_id = ? AND status = ?
                 AND lease_digest = ? AND fencing_token = ?""".trimIndent(),
        ).use { statement ->
            statement.setString(1, RESERVATION_RELEASED)
            statement.setLong(2, now)
            statement.setLong(3, now)
            statement.setString(4, tenantId)
            statement.setString(5, previous.id)
            statement.setString(6, providerId.value)
            statement.setString(7, RESERVATION_ACTIVE)
            statement.setString(8, previous.lease.leaseDigest)
            statement.setLong(9, previous.lease.fencingToken)
            check(statement.executeUpdate() == 1) { "Capacity reservation release lost its fence." }
        }
    }

    private fun replayAdmission(row: IntentRow): CapacityProviderResult<CapacityAdmissionDecision> = try {
        CapacityProviderResult.success(decodeAdmission(row), true)
    } catch (_: Exception) { CapacityProviderResult.failure(CapacityProviderErrorCode.INTERNAL_FAILURE) }

    private fun replayRenewal(row: IntentRow): CapacityProviderResult<CapacityLeaseRenewalReceipt> = try {
        CapacityProviderResult.success(decodeRenewal(row), true)
    } catch (_: Exception) { CapacityProviderResult.failure(CapacityProviderErrorCode.INTERNAL_FAILURE) }

    private fun replayRelease(row: IntentRow): CapacityProviderResult<CapacityLeaseReleaseReceipt> = try {
        CapacityProviderResult.success(decodeRelease(row), true)
    } catch (_: Exception) { CapacityProviderResult.failure(CapacityProviderErrorCode.INTERNAL_FAILURE) }

    private fun decodeAdmission(row: IntentRow): CapacityAdmissionDecision {
        require(row.outcomeKind == OUTCOME_ADMISSION && row.outcomeMemento != null)
        return CapacityJdbcCanonicalCodec.decodeAdmission(row.outcomeMemento).also {
            require(it.decisionDigest == row.outcomeDigest)
        }
    }

    private fun decodeRenewal(row: IntentRow): CapacityLeaseRenewalReceipt {
        require(row.outcomeKind == OUTCOME_RENEWAL && row.outcomeMemento != null)
        return CapacityJdbcCanonicalCodec.decodeRenewal(row.outcomeMemento).also {
            require(it.receiptDigest == row.outcomeDigest)
        }
    }

    private fun decodeRelease(row: IntentRow): CapacityLeaseReleaseReceipt {
        require(row.outcomeKind == OUTCOME_RELEASE && row.outcomeMemento != null)
        return CapacityJdbcCanonicalCodec.decodeRelease(row.outcomeMemento).also {
            require(it.receiptDigest == row.outcomeDigest)
        }
    }

    private fun operationTime(requestedAt: Long, deadlineAt: Long): Long? {
        val now = maxOf(requestedAt, clock.asLong)
        return now.takeIf { it < deadlineAt }
    }

    private fun stateRowId(tenantId: String, targetDigest: String, workload: String): String =
        CapacityJdbcDigests.rowId("capacity-state", tenantId, providerId.value, targetDigest, workload)

    private fun intentRowId(tenantId: String, operation: String, scopeDigest: String): String =
        CapacityJdbcDigests.rowId("capacity-intent", tenantId, providerId.value, operation, scopeDigest)

    private fun reconciliationDigest(referenceDigest: String, row: IntentRow?): String = CapacityJdbcDigests.digest(
        "flowweft.capacity.jdbc.reconciliation.v1",
        referenceDigest,
        row?.status ?: "ABSENT",
        row?.outcomeDigest ?: "-",
        row?.completedAt?.toString() ?: "-",
    )

    private fun nextId(kind: String): Identifier = Identifier("$kind-${UUID.randomUUID()}")

    private fun safeAdd(first: Long, second: Long): Long = Math.addExact(first, second)

    private sealed class IntentPreparation {
        data class Prepared(val rowId: String) : IntentPreparation()
        data class Replay(val row: IntentRow) : IntentPreparation()
        object Conflict : IntentPreparation()
        object Unknown : IntentPreparation()
    }

    private data class IntentRow(
        val id: String,
        val operation: String,
        val scopeDigest: String,
        val bindingDigest: String,
        val requestBindingDigest: String,
        val status: String,
        val outcomeKind: String?,
        val outcomeMemento: ByteArray?,
        val outcomeMementoDigest: String?,
        val outcomeDigest: String?,
        val preparedAt: Long,
        val completedAt: Long?,
    )

    private data class StateRow(
        val id: String,
        val stateVersion: Long,
        val nextFencingToken: Long,
        val policyResolutionDigest: String?,
    )

    private data class MeasureValue(
        val dimensionCode: String,
        val unitCode: String,
        val used: Long,
        val reserved: Long,
    )

    private data class ReservationRow(
        val id: String,
        val stateId: String,
        val lease: CapacityReservationLease,
        val chain: CapacityJdbcLeaseChain,
        val status: String,
    )

    companion object {
        const val CONTRACT_VERSION: String = "flowweft.capacity.jdbc-provider.v1"
        private const val OP_ADMIT = "capacity.admit"
        private const val OP_RENEW = "capacity.lease.renew"
        private const val OP_RELEASE = "capacity.lease.release"
        private const val INTENT_PREPARED = "PREPARED"
        private const val INTENT_APPLIED = "APPLIED"
        private const val INTENT_NOT_APPLIED = "NOT_APPLIED"
        private const val OUTCOME_ADMISSION = "ADMISSION"
        private const val OUTCOME_RENEWAL = "RENEWAL"
        private const val OUTCOME_RELEASE = "RELEASE"
        private const val RESERVATION_ACTIVE = "ACTIVE"
        private const val RESERVATION_RELEASED = "RELEASED"
    }
}

internal enum class CapacityJdbcIntentDecision { REPLAY, CONFLICT, UNKNOWN }

/** Pure arbitration shared by the SQL path and deterministic concurrency/crash-point tests. */
internal fun classifyCapacityJdbcIntent(
    storedBinding: String,
    storedOperation: String,
    storedScope: String,
    storedStatus: String,
    attemptedBinding: String,
    attemptedOperation: String,
    attemptedScope: String,
): CapacityJdbcIntentDecision = when {
    storedBinding != attemptedBinding || storedOperation != attemptedOperation || storedScope != attemptedScope ->
        CapacityJdbcIntentDecision.CONFLICT
    storedStatus == "APPLIED" -> CapacityJdbcIntentDecision.REPLAY
    storedStatus == "NOT_APPLIED" -> CapacityJdbcIntentDecision.CONFLICT
    else -> CapacityJdbcIntentDecision.UNKNOWN
}
