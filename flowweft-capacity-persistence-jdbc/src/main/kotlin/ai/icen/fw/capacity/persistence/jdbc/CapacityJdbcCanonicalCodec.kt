package ai.icen.fw.capacity.persistence.jdbc

import ai.icen.fw.capacity.api.CapacityAdmissionDecision
import ai.icen.fw.capacity.api.CapacityAdmissionOutcome
import ai.icen.fw.capacity.api.CapacityAdmissionRequest
import ai.icen.fw.capacity.api.CapacityDegradationCapability
import ai.icen.fw.capacity.api.CapacityDecisionReason
import ai.icen.fw.capacity.api.CapacityDemand
import ai.icen.fw.capacity.api.CapacityDimension
import ai.icen.fw.capacity.api.CapacityLeaseReleaseReceipt
import ai.icen.fw.capacity.api.CapacityLeaseReleaseRequest
import ai.icen.fw.capacity.api.CapacityLeaseRenewalReceipt
import ai.icen.fw.capacity.api.CapacityLeaseRenewalRequest
import ai.icen.fw.capacity.api.CapacityLimit
import ai.icen.fw.capacity.api.CapacityMeasureSnapshot
import ai.icen.fw.capacity.api.CapacityPolicy
import ai.icen.fw.capacity.api.CapacityPolicyResolution
import ai.icen.fw.capacity.api.CapacityPurpose
import ai.icen.fw.capacity.api.CapacityReservationLease
import ai.icen.fw.capacity.api.CapacityScopeLevel
import ai.icen.fw.capacity.api.CapacityTrustedContext
import ai.icen.fw.capacity.api.CapacityUnit
import ai.icen.fw.capacity.api.CapacityUsageSnapshot
import ai.icen.fw.capacity.api.CapacityWritePrecondition
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.capacity.api.WorkloadKind
import ai.icen.fw.core.id.Identifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

/**
 * Versioned, bounded mementos for durable idempotency. This intentionally does not use Java
 * serialization: public capacity contracts may evolve additively without binding persisted rows
 * to Kotlin/JVM implementation details.
 */
internal object CapacityJdbcCanonicalCodec {
    private const val MAGIC = 0x46574350 // FWCP
    private const val VERSION = 1
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 4096
    private const val MAX_ITEMS = 512

    fun encodeResolution(value: CapacityPolicyResolution): ByteArray = encode(TYPE_RESOLUTION) {
        resolution(value)
    }

    fun decodeResolution(bytes: ByteArray): CapacityPolicyResolution = decode(bytes, TYPE_RESOLUTION) {
        resolution()
    }

    fun encodeLeaseChain(value: CapacityJdbcLeaseChain): ByteArray = encode(TYPE_LEASE) { leaseChain(value) }

    fun decodeLeaseChain(bytes: ByteArray): CapacityJdbcLeaseChain = decode(bytes, TYPE_LEASE) { leaseChain() }

    fun encodeAdmission(value: CapacityAdmissionDecision, chain: CapacityJdbcLeaseChain?): ByteArray =
        encode(TYPE_ADMISSION) {
            admissionRequest(value.request)
            usage(value.usage)
            text(value.decisionId.value)
            text(value.providerId.value)
            text(value.outcome.name)
            bool(chain != null)
            if (chain != null) leaseChain(chain)
            collection(value.degradationCapabilities.sorted()) { text(it.value) }
            nullableLong(value.retryAfterMillis)
            nullableText(value.reason?.value)
            long(value.decidedAt)
            long(value.expiresAt)
        }

    fun decodeAdmission(bytes: ByteArray): CapacityAdmissionDecision = decode(bytes, TYPE_ADMISSION) {
        val request = admissionRequest()
        val usage = usage()
        val decisionId = Identifier(text())
        val providerId = Identifier(text())
        val outcome = enumValue<CapacityAdmissionOutcome>(text())
        val chain = if (bool()) leaseChain() else null
        val degradations = collection { CapacityDegradationCapability(text()) }
        val retryAfter = nullableLong()
        val reason = nullableText()?.let(::CapacityDecisionReason)
        val decidedAt = long()
        val expiresAt = long()
        when (outcome) {
            CapacityAdmissionOutcome.ADMIT -> CapacityAdmissionDecision.admit(
                decisionId, providerId, request, usage, requireNotNull(chain).lease(), decidedAt, expiresAt,
            )
            CapacityAdmissionOutcome.DEGRADE -> CapacityAdmissionDecision.degrade(
                decisionId,
                providerId,
                request,
                usage,
                requireNotNull(chain).lease(),
                degradations,
                requireNotNull(reason),
                decidedAt,
                expiresAt,
            )
            CapacityAdmissionOutcome.THROTTLE -> CapacityAdmissionDecision.throttle(
                decisionId,
                providerId,
                request,
                usage,
                requireNotNull(retryAfter),
                requireNotNull(reason),
                decidedAt,
                expiresAt,
            )
            CapacityAdmissionOutcome.REJECT -> CapacityAdmissionDecision.reject(
                decisionId, providerId, request, usage, requireNotNull(reason), decidedAt, expiresAt,
            )
        }
    }

    fun encodeRenewal(value: CapacityLeaseRenewalReceipt, chain: CapacityJdbcLeaseChain): ByteArray =
        encode(TYPE_RENEWAL) {
            leaseChain(chain)
            usage(value.usage)
            text(value.receiptId.value)
            text(value.providerId.value)
            long(value.decidedAt)
        }

    fun decodeRenewal(bytes: ByteArray): CapacityLeaseRenewalReceipt = decode(bytes, TYPE_RENEWAL) {
        val chain = leaseChain()
        require(chain.renewals.isNotEmpty()) { "Renewal memento has no renewal transition." }
        val last = chain.renewals.last()
        val before = chain.copy(renewals = chain.renewals.dropLast(1))
        val request = last.request(before.lease())
        val renewed = CapacityReservationLease.renewed(
            request,
            last.policyResolutionDigest,
            last.fencingToken,
            last.stateVersion,
            last.renewedAt,
            last.expiresAt,
        )
        val usage = usage()
        CapacityLeaseRenewalReceipt(Identifier(text()), Identifier(text()), request, renewed, usage, long())
    }

    fun encodeRelease(value: CapacityLeaseReleaseReceipt, chain: CapacityJdbcLeaseChain): ByteArray =
        encode(TYPE_RELEASE) {
            leaseChain(chain)
            releaseRequestTail(value.request)
            usage(value.usage)
            text(value.receiptId.value)
            text(value.providerId.value)
            long(value.releasedStateVersion)
            long(value.releasedAt)
        }

    fun decodeRelease(bytes: ByteArray): CapacityLeaseReleaseReceipt = decode(bytes, TYPE_RELEASE) {
        val chain = leaseChain()
        val request = releaseRequestTail(chain.lease())
        val usage = usage()
        CapacityLeaseReleaseReceipt(
            Identifier(text()),
            Identifier(text()),
            request,
            usage,
            long(),
            long(),
        )
    }

    private fun <T> encode(type: Int, block: Writer.() -> T): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(type)
            Writer(output).block()
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) { "Capacity memento is too large." } }
    }

    private fun <T> decode(bytes: ByteArray, type: Int, block: Reader.() -> T): T {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Capacity memento size is invalid." }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        try {
            require(input.readInt() == MAGIC && input.readInt() == VERSION && input.readInt() == type) {
                "Capacity memento header is invalid."
            }
            val reader = Reader(input)
            val value = reader.block()
            require(input.read() == -1) { "Capacity memento has trailing data." }
            return value
        } catch (failure: EOFException) {
            throw IllegalArgumentException("Capacity memento is truncated.", failure)
        } finally {
            input.close()
        }
    }

    private class Writer(private val output: DataOutputStream) {
        fun text(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            require(bytes.isNotEmpty() && bytes.size <= MAX_TEXT_BYTES) { "Capacity memento text is invalid." }
            output.writeInt(bytes.size)
            output.write(bytes)
        }

        fun nullableText(value: String?) { bool(value != null); if (value != null) text(value) }
        fun long(value: Long) = output.writeLong(value)
        fun bool(value: Boolean) = output.writeBoolean(value)
        fun nullableLong(value: Long?) { bool(value != null); if (value != null) long(value) }

        fun <T> collection(values: Collection<T>, write: Writer.(T) -> Unit) {
            require(values.size <= MAX_ITEMS) { "Capacity memento collection is too large." }
            output.writeInt(values.size)
            values.forEach { value -> write(value) }
        }

        fun scope(value: ResourceScope) {
            text(value.level.name)
            nullableText(value.tenantId?.value)
            nullableText(value.providerId?.value)
            nullableText(value.resourceType)
            nullableText(value.resourceId?.value)
        }

        fun context(value: CapacityTrustedContext) {
            text(value.tenantId.value)
            text(value.principalId.value)
            text(value.principalType)
            text(value.requestId.value)
            text(value.purpose.value)
            scope(value.authorizedScope)
            text(value.authenticationId.value)
            text(value.authorizationDecisionId.value)
            text(value.authorizationRevision)
            text(value.authorizationEvidenceDigest)
            long(value.initiatedAt)
            long(value.authorizationExpiresAt)
        }

        fun dimension(value: CapacityDimension) { text(value.code); text(value.unit.value) }

        fun demand(value: CapacityDemand) { dimension(value.dimension); long(value.amount) }

        fun policy(value: CapacityPolicy) {
            text(value.policyId.value)
            text(value.contractVersion)
            text(value.revision)
            long(value.stateVersion)
            scope(value.scope)
            collection(value.workloads.sorted()) { text(it.value) }
            collection(value.limits) {
                dimension(it.dimension)
                long(it.limit)
                long(it.warningWatermark)
                long(it.criticalWatermark)
            }
            long(value.effectiveFrom)
            long(value.expiresAt)
            collection(value.degradationCapabilities.sorted()) { text(it.value) }
            bool(value.enabled)
        }

        fun resolution(value: CapacityPolicyResolution) {
            scope(value.target)
            text(value.workload.value)
            long(value.observedAt)
            collection(value.policies) { policy(it) }
        }

        fun precondition(value: CapacityWritePrecondition) {
            text(value.idempotencyKeyDigest)
            long(value.expectedStateVersion)
            nullableText(value.expectedPolicyResolutionDigest)
        }

        fun admissionRequest(value: CapacityAdmissionRequest) {
            text(value.operationId.value)
            context(value.context)
            scope(value.target)
            text(value.workload.value)
            collection(value.demands) { demand(it) }
            collection(value.permittedDegradations.sorted()) { text(it.value) }
            precondition(value.precondition)
            long(value.requestedAt)
            long(value.deadlineAt)
        }

        fun usage(value: CapacityUsageSnapshot) {
            text(value.providerId.value)
            resolution(value.policyResolution)
            collection(value.measures) { long(it.used); long(it.reserved) }
            long(value.stateVersion)
            long(value.observedAt)
            long(value.expiresAt)
        }

        fun leaseChain(value: CapacityJdbcLeaseChain) {
            admissionRequest(value.admissionRequest)
            text(value.reservationId.value)
            text(value.leaseId.value)
            text(value.providerId.value)
            long(value.initialFencingToken)
            long(value.initialStateVersion)
            long(value.issuedAt)
            long(value.initialExpiresAt)
            collection(value.renewals) { renewalStep(it) }
        }

        private fun renewalStep(value: CapacityJdbcRenewalStep) {
            text(value.operationId.value)
            context(value.context)
            precondition(value.precondition)
            long(value.requestedExpiresAt)
            long(value.requestedAt)
            long(value.deadlineAt)
            text(value.policyResolutionDigest)
            long(value.fencingToken)
            long(value.stateVersion)
            long(value.renewedAt)
            long(value.expiresAt)
        }

        fun releaseRequestTail(value: CapacityLeaseReleaseRequest) {
            text(value.operationId.value)
            context(value.context)
            precondition(value.precondition)
            text(value.reasonCode)
            long(value.requestedAt)
            long(value.deadlineAt)
        }
    }

    private class Reader(private val input: DataInputStream) {
        fun text(): String {
            val size = input.readInt()
            require(size in 1..MAX_TEXT_BYTES) { "Capacity memento text length is invalid." }
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        fun nullableText(): String? = if (bool()) text() else null
        fun long(): Long = input.readLong()
        fun bool(): Boolean = input.readBoolean()
        fun nullableLong(): Long? = if (bool()) long() else null

        fun <T> collection(read: Reader.() -> T): List<T> {
            val size = input.readInt()
            require(size in 0..MAX_ITEMS) { "Capacity memento collection length is invalid." }
            return (0 until size).map { read() }
        }

        fun scope(): ResourceScope {
            val level = enumValue<CapacityScopeLevel>(text())
            val tenant = nullableText()?.let(::Identifier)
            val provider = nullableText()?.let(::Identifier)
            val resourceType = nullableText()
            val resourceId = nullableText()?.let(::Identifier)
            return when (level) {
                CapacityScopeLevel.SYSTEM -> ResourceScope.system()
                CapacityScopeLevel.TENANT -> ResourceScope.tenant(requireNotNull(tenant))
                CapacityScopeLevel.PROVIDER -> ResourceScope.provider(requireNotNull(tenant), requireNotNull(provider))
                CapacityScopeLevel.RESOURCE -> ResourceScope.resource(
                    requireNotNull(tenant), requireNotNull(resourceType), requireNotNull(resourceId), provider,
                )
            }
        }

        fun context(): CapacityTrustedContext = CapacityTrustedContext.authenticated(
            Identifier(text()),
            Identifier(text()),
            text(),
            Identifier(text()),
            CapacityPurpose(text()),
            scope(),
            Identifier(text()),
            Identifier(text()),
            text(),
            text(),
            long(),
            long(),
        )

        fun dimension(): CapacityDimension = CapacityDimension(text(), CapacityUnit(text()))
        fun demand(): CapacityDemand = CapacityDemand(dimension(), long())

        fun policy(): CapacityPolicy = CapacityPolicy(
            Identifier(text()),
            text(),
            text(),
            long(),
            scope(),
            collection { WorkloadKind(text()) },
            collection { CapacityLimit(dimension(), long(), long(), long()) },
            long(),
            long(),
            collection { CapacityDegradationCapability(text()) },
            bool(),
        )

        fun resolution(): CapacityPolicyResolution {
            val target = scope()
            val workload = WorkloadKind(text())
            val observedAt = long()
            return CapacityPolicyResolution.resolve(target, workload, collection { policy() }, observedAt)
        }

        fun precondition(operation: String): CapacityWritePrecondition {
            val key = text()
            val version = long()
            val policy = nullableText()
            return when (operation) {
                OP_ADMIT -> CapacityWritePrecondition.admission(key, version, requireNotNull(policy))
                OP_RENEW -> CapacityWritePrecondition.renewal(key, version, requireNotNull(policy))
                OP_RELEASE -> CapacityWritePrecondition.release(key, version)
                else -> error("Unsupported capacity precondition operation.")
            }
        }

        fun admissionRequest(): CapacityAdmissionRequest = CapacityAdmissionRequest(
            Identifier(text()),
            context(),
            scope(),
            WorkloadKind(text()),
            collection { demand() },
            collection { CapacityDegradationCapability(text()) },
            precondition(OP_ADMIT),
            long(),
            long(),
        )

        fun usage(): CapacityUsageSnapshot {
            val providerId = Identifier(text())
            val resolution = resolution()
            val values = collection { long() to long() }
            require(values.size == resolution.effectiveLimits.size) {
                "Capacity usage memento does not cover its policy limits."
            }
            val measures = values.mapIndexed { index, (used, reserved) ->
                CapacityMeasureSnapshot(resolution.effectiveLimits[index], used, reserved)
            }
            return CapacityUsageSnapshot.capture(providerId, resolution, measures, long(), long(), long())
        }

        fun leaseChain(): CapacityJdbcLeaseChain = CapacityJdbcLeaseChain(
            admissionRequest(),
            Identifier(text()),
            Identifier(text()),
            Identifier(text()),
            long(),
            long(),
            long(),
            long(),
            collection { renewalStep() },
        ).also { it.lease() }

        private fun renewalStep(): CapacityJdbcRenewalStep = CapacityJdbcRenewalStep(
            Identifier(text()),
            context(),
            precondition(OP_RENEW),
            long(),
            long(),
            long(),
            text(),
            long(),
            long(),
            long(),
            long(),
        )

        fun releaseRequestTail(lease: CapacityReservationLease): CapacityLeaseReleaseRequest =
            CapacityLeaseReleaseRequest(
                Identifier(text()),
                context(),
                lease,
                precondition(OP_RELEASE),
                text(),
                long(),
                long(),
            )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Capacity memento enum value is invalid.")

    private const val TYPE_RESOLUTION = 1
    private const val TYPE_LEASE = 2
    private const val TYPE_ADMISSION = 3
    private const val TYPE_RENEWAL = 4
    private const val TYPE_RELEASE = 5
    private const val OP_ADMIT = "admit"
    private const val OP_RENEW = "renew"
    private const val OP_RELEASE = "release"
}

internal data class CapacityJdbcLeaseChain(
    val admissionRequest: CapacityAdmissionRequest,
    val reservationId: Identifier,
    val leaseId: Identifier,
    val providerId: Identifier,
    val initialFencingToken: Long,
    val initialStateVersion: Long,
    val issuedAt: Long,
    val initialExpiresAt: Long,
    val renewals: List<CapacityJdbcRenewalStep> = emptyList(),
) {
    fun lease(): CapacityReservationLease {
        var current = CapacityReservationLease.issue(
            reservationId,
            leaseId,
            providerId,
            admissionRequest,
            initialFencingToken,
            initialStateVersion,
            issuedAt,
            initialExpiresAt,
        )
        renewals.forEach { step ->
            val request = step.request(current)
            current = CapacityReservationLease.renewed(
                request,
                step.policyResolutionDigest,
                step.fencingToken,
                step.stateVersion,
                step.renewedAt,
                step.expiresAt,
            )
        }
        return current
    }
}

internal data class CapacityJdbcRenewalStep(
    val operationId: Identifier,
    val context: CapacityTrustedContext,
    val precondition: CapacityWritePrecondition,
    val requestedExpiresAt: Long,
    val requestedAt: Long,
    val deadlineAt: Long,
    val policyResolutionDigest: String,
    val fencingToken: Long,
    val stateVersion: Long,
    val renewedAt: Long,
    val expiresAt: Long,
) {
    fun request(lease: CapacityReservationLease): CapacityLeaseRenewalRequest = CapacityLeaseRenewalRequest(
        operationId,
        context,
        lease,
        precondition,
        requestedExpiresAt,
        requestedAt,
        deadlineAt,
    )
}
