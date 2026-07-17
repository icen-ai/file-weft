package ai.icen.fw.capacity.api

import ai.icen.fw.core.id.Identifier

enum class CapacityDoctorStatus {
    READY,
    DEGRADED,
    UNAVAILABLE,
    UNSUPPORTED,
}

/** Open diagnostic code; it is never provider exception text. */
class CapacityDoctorSignalCode(value: String) {
    val value: String = requireCapacityCode(value, "Capacity Doctor signal code")

    override fun equals(other: Any?): Boolean = other is CapacityDoctorSignalCode && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val CAPACITY_WITHIN_LIMIT = CapacityDoctorSignalCode("capacity_within_limit")
        @JvmField val WATERMARK_PRESSURE = CapacityDoctorSignalCode("watermark_pressure")
        @JvmField val CAPACITY_EXHAUSTED = CapacityDoctorSignalCode("capacity_exhausted")
        @JvmField val POLICY_UNAVAILABLE = CapacityDoctorSignalCode("policy_unavailable")
        @JvmField val PROVIDER_UNAVAILABLE = CapacityDoctorSignalCode("provider_unavailable")
    }
}

/**
 * Value-free diagnostic evidence: no measured count, tenant/resource identifier, error text,
 * endpoint, credential or raw provider payload is representable.
 */
class CapacityDoctorSignal(
    signalId: Identifier,
    val code: CapacityDoctorSignalCode,
    val status: CapacityDoctorStatus,
    val scopeLevel: CapacityScopeLevel,
    val workload: WorkloadKind?,
    val dimension: CapacityDimension?,
    val pressure: CapacityPressureLevel?,
    policyResolutionDigest: String?,
    usageSnapshotDigest: String?,
    val observedAt: Long,
    val expiresAt: Long,
) {
    val signalId: Identifier = requireCapacityIdentifier(signalId, "Capacity Doctor signal identifier")
    val policyResolutionDigest: String? = policyResolutionDigest?.let { digest ->
        requireCapacityDigest(digest, "Capacity Doctor policy evidence")
    }
    val usageSnapshotDigest: String? = usageSnapshotDigest?.let { digest ->
        requireCapacityDigest(digest, "Capacity Doctor usage evidence")
    }
    val evidenceDigest: String

    init {
        require(observedAt >= 0L && expiresAt > observedAt) { "Capacity Doctor signal lifetime is invalid." }
        require((status == CapacityDoctorStatus.READY) == (code == CapacityDoctorSignalCode.CAPACITY_WITHIN_LIMIT)) {
            "Capacity Doctor ready status and signal code disagree."
        }
        require(status != CapacityDoctorStatus.READY || pressure == null || pressure == CapacityPressureLevel.NORMAL) {
            "A ready Capacity Doctor signal cannot carry pressure."
        }
        evidenceDigest = CapacityDigest("flowweft.capacity.doctor-signal.v1")
            .add(this.signalId.value)
            .add(code.value)
            .add(status.name)
            .add(scopeLevel.name)
            .add(workload?.value ?: "-")
            .add(dimension?.code ?: "-")
            .add(dimension?.unit?.value ?: "-")
            .add(pressure?.name ?: "-")
            .add(this.policyResolutionDigest ?: "-")
            .add(this.usageSnapshotDigest ?: "-")
            .add(observedAt)
            .add(expiresAt)
            .finish()
    }
}

class CapacityDoctorReport(
    providerId: Identifier,
    val status: CapacityDoctorStatus,
    signals: Collection<CapacityDoctorSignal>,
    val observedAt: Long,
    val expiresAt: Long,
) {
    val providerId: Identifier = requireCapacityIdentifier(providerId, "Capacity Doctor provider identifier")
    val signals: List<CapacityDoctorSignal> = capacityList(
        signals,
        CapacityContractLimits.MAX_DOCTOR_SIGNALS,
        "Capacity Doctor signals",
    )
    val reportDigest: String

    init {
        require(this.signals.isNotEmpty() &&
            this.signals.map { signal -> signal.signalId }.toSet().size == this.signals.size &&
            observedAt >= 0L && expiresAt > observedAt &&
            this.signals.all { signal -> signal.observedAt <= observedAt && signal.expiresAt >= expiresAt }
        ) { "Capacity Doctor report is empty, ambiguous or stale." }
        require(status != CapacityDoctorStatus.READY ||
            this.signals.all { signal -> signal.status == CapacityDoctorStatus.READY }
        ) { "A ready Capacity Doctor report cannot contain a non-ready signal." }
        val digest = CapacityDigest("flowweft.capacity.doctor-report.v1")
            .add(this.providerId.value)
            .add(status.name)
            .add(observedAt)
            .add(expiresAt)
        this.signals.sortedBy { signal -> signal.signalId.value }.forEach { signal ->
            digest.add(signal.evidenceDigest)
        }
        reportDigest = digest.finish()
    }

    override fun toString(): String = "CapacityDoctorReport(status=$status, signals=${signals.size})"
}

/** Open metric event identity; evidence is categorical and contains no measured value. */
class CapacityMetricCode(value: String) {
    val value: String = requireCapacityCode(value, "Capacity metric code")

    override fun equals(other: Any?): Boolean = other is CapacityMetricCode && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val ADMISSION_DECISION = CapacityMetricCode("admission_decision")
        @JvmField val LEASE_RENEWAL = CapacityMetricCode("lease_renewal")
        @JvmField val LEASE_RELEASE = CapacityMetricCode("lease_release")
        @JvmField val PRESSURE_OBSERVATION = CapacityMetricCode("pressure_observation")
    }
}

/** Safe metric evidence for a sink that owns aggregation; it deliberately has no numeric value. */
class CapacityMetricEvidence(
    observationId: Identifier,
    providerId: Identifier,
    val metric: CapacityMetricCode,
    val scopeLevel: CapacityScopeLevel,
    val workload: WorkloadKind,
    val outcome: CapacityAdmissionOutcome? = null,
    val pressure: CapacityPressureLevel? = null,
    sourceEvidenceDigest: String,
    val observedAt: Long,
) {
    val observationId: Identifier = requireCapacityIdentifier(
        observationId,
        "Capacity metric observation identifier",
    )
    val providerId: Identifier = requireCapacityIdentifier(providerId, "Capacity metric provider identifier")
    val sourceEvidenceDigest: String = requireCapacityDigest(
        sourceEvidenceDigest,
        "Capacity metric source evidence",
    )
    val evidenceDigest: String

    init {
        require(observedAt >= 0L) { "Capacity metric observation time is invalid." }
        require(metric != CapacityMetricCode.ADMISSION_DECISION || outcome != null) {
            "Capacity admission metric evidence requires an explicit outcome."
        }
        require(metric != CapacityMetricCode.PRESSURE_OBSERVATION || pressure != null) {
            "Capacity pressure metric evidence requires an explicit pressure band."
        }
        evidenceDigest = CapacityDigest("flowweft.capacity.metric-evidence.v1")
            .add(this.observationId.value)
            .add(this.providerId.value)
            .add(metric.value)
            .add(scopeLevel.name)
            .add(workload.value)
            .add(outcome?.name ?: "-")
            .add(pressure?.name ?: "-")
            .add(this.sourceEvidenceDigest)
            .add(observedAt)
            .finish()
    }
}

fun interface CapacityMetricSink {
    /** Implementations aggregate internally and must not add tenant/resource IDs as metric labels. */
    fun observe(evidence: CapacityMetricEvidence)

    companion object {
        @JvmField val NOOP: CapacityMetricSink = CapacityMetricSink { }
    }
}
