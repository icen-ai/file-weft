package ai.icen.fw.agent.interoperability.spi

import ai.icen.fw.agent.api.AgentRemoteOperationKind
import ai.icen.fw.agent.api.AgentRemotePeerProfile
import ai.icen.fw.agent.api.AgentRemoteProtocolInvocationRequest
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier

class AgentInteroperabilityDoctorMode private constructor(code: String) {
    val code: String = InteroperabilityContractSupport.requireMachineCode(
        code,
        "Interoperability Doctor mode is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is AgentInteroperabilityDoctorMode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = code

    companion object {
        @JvmField val CONFIGURATION = AgentInteroperabilityDoctorMode("configuration")
        @JvmField val CAPABILITY = AgentInteroperabilityDoctorMode("capability")
        @JvmField val CATALOG = AgentInteroperabilityDoctorMode("catalog")

        @JvmStatic
        fun of(code: String): AgentInteroperabilityDoctorMode = when (code) {
            CONFIGURATION.code -> CONFIGURATION
            CAPABILITY.code -> CAPABILITY
            CATALOG.code -> CATALOG
            else -> AgentInteroperabilityDoctorMode(code)
        }
    }
}

/** Read-only diagnostic request reusing the existing trusted invocation, deadline, budget, and idempotency. */
class AgentInteroperabilityDoctorRequest private constructor(
    requestId: Identifier,
    val providerId: ProviderId,
    val invocation: AgentRemoteProtocolInvocationRequest,
    val profile: AgentRemotePeerProfile,
    val mode: AgentInteroperabilityDoctorMode,
    expectedCapabilityDigest: String?,
    expectedCatalogDigest: String?,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val requestId: Identifier = requestId
    val expectedCapabilityDigest: String? = expectedCapabilityDigest?.let {
        InteroperabilityContractSupport.requireSha256(
            it,
            "Expected interoperability Doctor capability digest is invalid.",
        )
    }
    val expectedCatalogDigest: String? = expectedCatalogDigest?.let {
        InteroperabilityContractSupport.requireSha256(
            it,
            "Expected interoperability Doctor catalog digest is invalid.",
        )
    }
    val requestDigest: String

    init {
        InteroperabilityContractSupport.requireOpaqueReference(
            requestId.value,
            "Interoperability Doctor request identifier is invalid.",
        )
        require(invocation.operation.operation == AgentRemoteOperationKind.INITIALIZE) {
            "Interoperability Doctor requires a side-effect-free initialization invocation."
        }
        require(invocation.operation.peerId == profile.peerId &&
            invocation.operation.protocol == profile.protocol &&
            invocation.approvedProfileDigest == profile.profileDigest
        ) { "Interoperability Doctor profile differs from the trusted invocation." }
        invocation.requireCurrent(requestedAt)
        require(requestedAt < deadlineAt && deadlineAt <= invocation.deadlineAt) {
            "Interoperability Doctor request window is invalid."
        }
        require(deadlineAt - requestedAt <= InteroperabilityContractSupport.MAX_CALL_WINDOW_MILLIS) {
            "Interoperability Doctor request duration is too large."
        }
        requestDigest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.doctor-request.v1",
        )
            .text(requestId.value)
            .text(providerId.value)
            .text(invocation.bindingDigest)
            .text(profile.profileDigest)
            .text(mode.code)
            .optionalText(this.expectedCapabilityDigest)
            .optionalText(this.expectedCatalogDigest)
            .longValue(requestedAt)
            .longValue(deadlineAt)
            .finish()
    }

    fun requireCurrent(atTime: Long) {
        require(atTime in requestedAt until deadlineAt) { "Interoperability Doctor request is not current." }
        invocation.requireCurrent(atTime)
    }

    override fun toString(): String =
        "AgentInteroperabilityDoctorRequest(mode=$mode, tenant=<redacted>, values=<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            requestId: Identifier,
            providerId: ProviderId,
            invocation: AgentRemoteProtocolInvocationRequest,
            profile: AgentRemotePeerProfile,
            mode: AgentInteroperabilityDoctorMode,
            expectedCapabilityDigest: String?,
            expectedCatalogDigest: String?,
            requestedAt: Long,
            deadlineAt: Long,
        ): AgentInteroperabilityDoctorRequest = AgentInteroperabilityDoctorRequest(
            requestId,
            providerId,
            invocation,
            profile,
            mode,
            expectedCapabilityDigest,
            expectedCatalogDigest,
            requestedAt,
            deadlineAt,
        )
    }
}

enum class AgentInteroperabilityDoctorSeverity {
    INFO,
    WARNING,
    ERROR,
}

class AgentInteroperabilityDoctorCode private constructor(value: String) {
    val value: String = InteroperabilityContractSupport.requireMachineCode(
        value,
        "Interoperability Doctor code is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is AgentInteroperabilityDoctorCode && value == other.value

    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val PROFILE_MATCHED = AgentInteroperabilityDoctorCode("interop.profile.matched")
        @JvmField val PROFILE_DRIFTED = AgentInteroperabilityDoctorCode("interop.profile.drifted")
        @JvmField val CAPABILITY_MATCHED = AgentInteroperabilityDoctorCode("interop.capability.matched")
        @JvmField val CAPABILITY_DRIFTED = AgentInteroperabilityDoctorCode("interop.capability.drifted")
        @JvmField val CATALOG_MATCHED = AgentInteroperabilityDoctorCode("interop.catalog.matched")
        @JvmField val CATALOG_DRIFTED = AgentInteroperabilityDoctorCode("interop.catalog.drifted")
        @JvmField val PROVIDER_UNAVAILABLE = AgentInteroperabilityDoctorCode("interop.provider.unavailable")
        @JvmField val PROVIDER_UNSUPPORTED = AgentInteroperabilityDoctorCode("interop.provider.unsupported")
        @JvmField val OUTCOME_UNKNOWN = AgentInteroperabilityDoctorCode("interop.outcome.unknown")
        @JvmField val RECONCILIATION_PENDING = AgentInteroperabilityDoctorCode("interop.reconciliation.pending")

        @JvmStatic
        fun of(value: String): AgentInteroperabilityDoctorCode = when (value) {
            PROFILE_MATCHED.value -> PROFILE_MATCHED
            PROFILE_DRIFTED.value -> PROFILE_DRIFTED
            CAPABILITY_MATCHED.value -> CAPABILITY_MATCHED
            CAPABILITY_DRIFTED.value -> CAPABILITY_DRIFTED
            CATALOG_MATCHED.value -> CATALOG_MATCHED
            CATALOG_DRIFTED.value -> CATALOG_DRIFTED
            PROVIDER_UNAVAILABLE.value -> PROVIDER_UNAVAILABLE
            PROVIDER_UNSUPPORTED.value -> PROVIDER_UNSUPPORTED
            OUTCOME_UNKNOWN.value -> OUTCOME_UNKNOWN
            RECONCILIATION_PENDING.value -> RECONCILIATION_PENDING
            else -> AgentInteroperabilityDoctorCode(value)
        }
    }
}

/** Stable, value-free diagnostic evidence. Provider payloads, endpoints, headers, and secrets are forbidden. */
class AgentInteroperabilityDoctorFinding private constructor(
    val code: AgentInteroperabilityDoctorCode,
    val severity: AgentInteroperabilityDoctorSeverity,
    val count: Long,
    evidenceDigest: String,
) {
    val evidenceDigest: String = InteroperabilityContractSupport.requireSha256(
        evidenceDigest,
        "Interoperability Doctor evidence digest is invalid.",
    )
    val findingDigest: String

    init {
        require(count in 0L..1_000_000_000_000L) { "Interoperability Doctor count is invalid." }
        findingDigest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.doctor-finding.v1",
        )
            .text(code.value)
            .text(severity.name)
            .longValue(count)
            .text(this.evidenceDigest)
            .finish()
    }

    override fun toString(): String =
        "AgentInteroperabilityDoctorFinding(code=$code, severity=$severity, evidence=<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            code: AgentInteroperabilityDoctorCode,
            severity: AgentInteroperabilityDoctorSeverity,
            count: Long,
            evidenceDigest: String,
        ): AgentInteroperabilityDoctorFinding = AgentInteroperabilityDoctorFinding(
            code,
            severity,
            count,
            evidenceDigest,
        )
    }
}

enum class AgentInteroperabilityDoctorStatus {
    READY,
    DEGRADED,
    NOT_READY,
    UNSUPPORTED,
}

class AgentInteroperabilityDoctorResult private constructor(
    request: AgentInteroperabilityDoctorRequest,
    val providerId: ProviderId,
    val status: AgentInteroperabilityDoctorStatus,
    findings: Collection<AgentInteroperabilityDoctorFinding>,
    val observedAt: Long,
    val expiresAt: Long,
) {
    val requestId: Identifier = request.requestId
    val requestDigest: String = request.requestDigest
    val findings: List<AgentInteroperabilityDoctorFinding> = InteroperabilityContractSupport.immutableList(
        findings,
        InteroperabilityContractSupport.MAX_FINDINGS,
        "Interoperability Doctor findings are invalid.",
    )
    val resultDigest: String

    init {
        require(providerId == request.providerId) { "Interoperability Doctor result came from another provider." }
        require(this.findings.isNotEmpty()) { "Interoperability Doctor requires at least one finding." }
        require(observedAt in request.requestedAt..request.deadlineAt && expiresAt > observedAt) {
            "Interoperability Doctor result window is invalid."
        }
        when (status) {
            AgentInteroperabilityDoctorStatus.READY -> require(
                this.findings.none { it.severity != AgentInteroperabilityDoctorSeverity.INFO },
            ) { "Ready interoperability Doctor result may contain only informational findings." }
            AgentInteroperabilityDoctorStatus.DEGRADED -> require(
                this.findings.any { it.severity == AgentInteroperabilityDoctorSeverity.WARNING } &&
                    this.findings.none { it.severity == AgentInteroperabilityDoctorSeverity.ERROR },
            ) { "Degraded interoperability Doctor result requires warnings without errors." }
            AgentInteroperabilityDoctorStatus.NOT_READY -> require(
                this.findings.any { it.severity == AgentInteroperabilityDoctorSeverity.ERROR },
            ) { "Not-ready interoperability Doctor result requires an error." }
            AgentInteroperabilityDoctorStatus.UNSUPPORTED -> require(
                this.findings.any { it.code == AgentInteroperabilityDoctorCode.PROVIDER_UNSUPPORTED },
            ) { "Unsupported interoperability Doctor result requires an unsupported finding." }
        }
        val digest = InteroperabilityContractSupport.digest(
            "flowweft.agent.interoperability.doctor-result.v1",
        )
            .text(requestId.value)
            .text(requestDigest)
            .text(providerId.value)
            .text(status.name)
            .longValue(observedAt)
            .longValue(expiresAt)
            .integer(this.findings.size)
        this.findings.forEach { finding -> digest.text(finding.findingDigest) }
        resultDigest = digest.finish()
    }

    override fun toString(): String =
        "AgentInteroperabilityDoctorResult(status=$status, findings=${findings.size}, values=<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            request: AgentInteroperabilityDoctorRequest,
            providerId: ProviderId,
            status: AgentInteroperabilityDoctorStatus,
            findings: Collection<AgentInteroperabilityDoctorFinding>,
            observedAt: Long,
            expiresAt: Long,
        ): AgentInteroperabilityDoctorResult = AgentInteroperabilityDoctorResult(
            request,
            providerId,
            status,
            findings,
            observedAt,
            expiresAt,
        )
    }
}
