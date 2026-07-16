package ai.icen.fw.agent.web.api

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.ModelId
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.evaluation.AgentEvaluationDoctorStatus
import ai.icen.fw.agent.evaluation.AgentEvaluationDoctorSnapshot
import ai.icen.fw.core.id.Identifier

/** Open availability code so new provider states remain forward-compatible values. */
class AgentWebCapabilityStatus(value: String) {
    val value: String = agentWebCode(value, "Agent Web capability status")

    override fun equals(other: Any?): Boolean = other is AgentWebCapabilityStatus && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val AVAILABLE = AgentWebCapabilityStatus("AVAILABLE")
        @JvmField val UNAVAILABLE = AgentWebCapabilityStatus("UNAVAILABLE")
        @JvmField val UNCONFIGURED = AgentWebCapabilityStatus("UNCONFIGURED")
        @JvmField val DEGRADED = AgentWebCapabilityStatus("DEGRADED")
        @JvmField val UNSUPPORTED = AgentWebCapabilityStatus("UNSUPPORTED")
    }
}

/** Secret-free local provider capability snapshot; descriptor access must perform no network I/O. */
class AgentWebProviderCapabilityDto(
    val providerId: ProviderId,
    providerKind: String,
    capabilities: Collection<AgentCapabilityId>,
    models: Collection<ModelId>,
    val status: AgentWebCapabilityStatus,
    reasonCode: String?,
    descriptorDigest: String,
    val observedAt: Long,
    val expiresAt: Long,
) {
    val providerKind: String = agentWebCode(providerKind, "Agent Web provider kind")
    val capabilities: Set<AgentCapabilityId> = agentWebSet(
        capabilities,
        AGENT_WEB_MAX_CAPABILITIES,
        "Agent Web provider capabilities",
    )
    val models: Set<ModelId> = agentWebSet(models, AGENT_WEB_MAX_CAPABILITIES, "Agent Web provider models")
    val reasonCode: String? = reasonCode?.let { code -> agentWebCode(code, "Agent Web provider reason") }
    val descriptorDigest: String = agentWebSha256(descriptorDigest, "Agent Web provider descriptor")

    init {
        require(observedAt >= 0L && expiresAt > observedAt) { "Agent Web provider snapshot lifetime is invalid." }
        require((status == AgentWebCapabilityStatus.AVAILABLE) == (this.reasonCode == null)) {
            "Agent Web provider availability and reason do not agree."
        }
    }
}

/**
 * Safe configuration projection. Connections and credentials are server-side references only;
 * endpoint URLs, secret material and provider error text are not representable.
 */
class AgentWebProviderConfigurationDto(
    profileId: Identifier,
    val providerId: ProviderId,
    connectionProfileReference: Identifier,
    credentialReference: Identifier?,
    val modelId: ModelId?,
    capabilities: Collection<AgentCapabilityId>,
    val enabled: Boolean,
    configurationRevision: String,
    val stateVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val profileId: Identifier = agentWebIdentifier(profileId, "Agent Web provider profile identifier")
    val connectionProfileReference: Identifier = agentWebIdentifier(
        connectionProfileReference,
        "Agent Web connection profile reference",
    )
    val credentialReference: Identifier? = credentialReference?.let { reference ->
        agentWebIdentifier(reference, "Agent Web credential reference")
    }
    val capabilities: Set<AgentCapabilityId> = agentWebSet(
        capabilities,
        AGENT_WEB_MAX_CAPABILITIES,
        "Agent Web configured capabilities",
    )
    val configurationRevision: String = agentWebText(
        configurationRevision,
        AGENT_WEB_MAX_ID_BYTES,
        "Agent Web configuration revision",
    )

    init {
        require(stateVersion >= 0L && createdAt >= 0L && updatedAt >= createdAt) {
            "Agent Web provider configuration version or timestamps are invalid."
        }
    }

    override fun toString(): String = "AgentWebProviderConfigurationDto(<redacted>)"
}

class AgentWebProviderConfigurationCommand @JvmOverloads constructor(
    val providerId: ProviderId,
    connectionProfileReference: Identifier,
    credentialReference: Identifier?,
    val modelId: ModelId?,
    capabilities: Collection<AgentCapabilityId>,
    val enabled: Boolean = true,
) {
    val connectionProfileReference: Identifier = agentWebIdentifier(
        connectionProfileReference,
        "Agent Web connection profile reference",
    )
    val credentialReference: Identifier? = credentialReference?.let { reference ->
        agentWebIdentifier(reference, "Agent Web credential reference")
    }
    val capabilities: Set<AgentCapabilityId> = agentWebSet(
        capabilities,
        AGENT_WEB_MAX_CAPABILITIES,
        "Agent Web configured capabilities",
    )

    init {
        require(this.capabilities.isNotEmpty()) { "Agent Web provider configuration requires a capability." }
    }

    override fun toString(): String = "AgentWebProviderConfigurationCommand(<redacted>)"
}

class AgentWebDoctorStatus(value: String) {
    val value: String = agentWebCode(value, "Agent Web Doctor status")

    override fun equals(other: Any?): Boolean = other is AgentWebDoctorStatus && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val READY = AgentWebDoctorStatus("READY")
        @JvmField val DEGRADED = AgentWebDoctorStatus("DEGRADED")
        @JvmField val UNAVAILABLE = AgentWebDoctorStatus("UNAVAILABLE")
        @JvmField val UNSUPPORTED = AgentWebDoctorStatus("UNSUPPORTED")
    }
}

class AgentWebDoctorCheckDto(
    componentCode: String,
    val status: AgentWebDoctorStatus,
    reasonCode: String?,
    val observedAt: Long,
) {
    val componentCode: String = agentWebCode(componentCode, "Agent Web Doctor component")
    val reasonCode: String? = reasonCode?.let { code -> agentWebCode(code, "Agent Web Doctor reason") }

    init {
        require(observedAt >= 0L) { "Agent Web Doctor check time is invalid." }
        require((status == AgentWebDoctorStatus.READY) == (this.reasonCode == null)) {
            "Agent Web Doctor status and reason do not agree."
        }
    }
}

/** Aggregate-only diagnostics; no prompt, body, secret, endpoint or provider exception can appear. */
class AgentWebDoctorReportDto @JvmOverloads constructor(
    val status: AgentWebDoctorStatus,
    checks: Collection<AgentWebDoctorCheckDto>,
    val observedAt: Long,
    val evaluation: AgentEvaluationDoctorSnapshot? = null,
) {
    val checks: List<AgentWebDoctorCheckDto> = agentWebList(
        checks,
        AGENT_WEB_MAX_DOCTOR_CHECKS,
        "Agent Web Doctor checks",
    )

    init {
        require(this.checks.isNotEmpty() && observedAt >= 0L &&
            this.checks.map { check -> check.componentCode }.toSet().size == this.checks.size
        ) { "Agent Web Doctor report is empty, ambiguous or invalid." }
        require(this.checks.all { check -> check.observedAt <= observedAt }) {
            "Agent Web Doctor report predates a component check."
        }
        require(evaluation == null || evaluation.observedAt <= observedAt) {
            "Agent Web Doctor report predates its evaluation diagnostic."
        }
        require(status != AgentWebDoctorStatus.READY ||
            this.checks.all { check -> check.status == AgentWebDoctorStatus.READY } &&
            (evaluation == null || evaluation.status == AgentEvaluationDoctorStatus.READY)
        ) { "A ready Agent Web Doctor report cannot contain a non-ready check." }
    }

    override fun toString(): String = "AgentWebDoctorReportDto(status=$status, checks=${checks.size})"
}
