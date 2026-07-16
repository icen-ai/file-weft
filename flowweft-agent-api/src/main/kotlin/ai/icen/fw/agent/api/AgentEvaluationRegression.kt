package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier

/**
 * Immutable evidence describing the exact provider contract used by one evaluation execution.
 * Credentials, endpoints and model prompts are deliberately excluded.
 */
class AgentEvaluationProviderSnapshot(
    val providerId: ProviderId,
    implementationVersion: String,
    capabilities: Collection<AgentCapabilityId>,
    descriptorDigest: String,
    val capturedAt: Long,
    val expiresAt: Long,
) {
    val implementationVersion: String = requireAgentToken(
        implementationVersion,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent evaluation provider version is invalid.",
    )
    val capabilities: Set<AgentCapabilityId>
    val descriptorDigest: String = requireSha256(
        descriptorDigest,
        "Agent evaluation provider descriptor digest is invalid.",
    )
    val snapshotDigest: String

    init {
        val capabilitySnapshot = immutableAgentList(capabilities)
        require(capabilitySnapshot.isNotEmpty()) {
            "Agent evaluation provider snapshot requires at least one capability."
        }
        require(capabilitySnapshot.size <= AgentContractLimits.MAX_CAPABILITIES) {
            "Agent evaluation provider snapshot contains too many capabilities."
        }
        require(capabilitySnapshot.toSet().size == capabilitySnapshot.size) {
            "Agent evaluation provider snapshot capabilities must be unique."
        }
        requireNonNegativeTime(capturedAt, "Agent evaluation provider capture time must not be negative.")
        require(expiresAt > capturedAt) {
            "Agent evaluation provider snapshot expiry must follow its capture time."
        }
        this.capabilities = immutableAgentSet(capabilitySnapshot)
        val digest = AgentDigestBuilder("flowweft.agent.evaluation.provider-snapshot.v1")
            .add(providerId.value)
            .add(this.implementationVersion)
            .add(this.descriptorDigest)
            .add(capturedAt)
            .add(expiresAt)
            .add(this.capabilities.size)
        this.capabilities.map { capability -> capability.value }.sorted().forEach(digest::add)
        snapshotDigest = digest.finish()
    }

    fun supports(capabilityId: AgentCapabilityId): Boolean = capabilityId in capabilities

    fun isCurrent(atTime: Long): Boolean {
        requireNonNegativeTime(atTime, "Agent evaluation provider assessment time must not be negative.")
        return atTime in capturedAt until expiresAt
    }

    override fun toString(): String =
        "AgentEvaluationProviderSnapshot(providerId=$providerId, version=$implementationVersion)"
}

/** Expected retrieval behavior for a fixed regression case. Unauthorized evidence is never allowed. */
class AgentEvaluationRetrievalExpectation @JvmOverloads constructor(
    requiredEvidenceIds: Collection<Identifier>,
    val minimumRelevantEvidence: Int,
    val maximumMissingRequiredEvidence: Int = 0,
    val requireSecurityFilterReceipt: Boolean = true,
) {
    val requiredEvidenceIds: Set<Identifier>
    val bindingDigest: String

    init {
        val evidenceSnapshot = immutableAgentList(requiredEvidenceIds).map { evidenceId ->
            requireOpaqueIdentifier(evidenceId, "Agent evaluation expected evidence identifier is invalid.")
        }
        require(evidenceSnapshot.size <= AgentContractLimits.MAX_CITATIONS) {
            "Agent evaluation retrieval expectation contains too many evidence identifiers."
        }
        require(evidenceSnapshot.toSet().size == evidenceSnapshot.size) {
            "Agent evaluation expected evidence identifiers must be unique."
        }
        require(minimumRelevantEvidence >= 0) {
            "Agent evaluation minimum relevant evidence must not be negative."
        }
        require(maximumMissingRequiredEvidence in 0..evidenceSnapshot.size) {
            "Agent evaluation missing-evidence allowance is invalid."
        }
        this.requiredEvidenceIds = immutableAgentSet(evidenceSnapshot)
        val digest = AgentDigestBuilder("flowweft.agent.evaluation.retrieval-expectation.v1")
            .add(minimumRelevantEvidence)
            .add(maximumMissingRequiredEvidence)
            .add(requireSecurityFilterReceipt)
            .add(this.requiredEvidenceIds.size)
        this.requiredEvidenceIds.map { evidenceId -> evidenceId.value }.sorted().forEach(digest::add)
        bindingDigest = digest.finish()
    }
}

/** Expected citation coverage. Evidence identifiers are stable fixture references, never excerpts. */
class AgentEvaluationCitationExpectation @JvmOverloads constructor(
    requiredEvidenceIds: Collection<Identifier>,
    val minimumValidCitations: Int,
    val maximumUnsupportedClaims: Int = 0,
) {
    val requiredEvidenceIds: Set<Identifier>
    val bindingDigest: String

    init {
        val evidenceSnapshot = immutableAgentList(requiredEvidenceIds).map { evidenceId ->
            requireOpaqueIdentifier(evidenceId, "Agent evaluation citation evidence identifier is invalid.")
        }
        require(evidenceSnapshot.size <= AgentContractLimits.MAX_CITATIONS) {
            "Agent evaluation citation expectation contains too many evidence identifiers."
        }
        require(evidenceSnapshot.toSet().size == evidenceSnapshot.size) {
            "Agent evaluation citation evidence identifiers must be unique."
        }
        require(minimumValidCitations >= 0) {
            "Agent evaluation minimum valid citation count must not be negative."
        }
        require(maximumUnsupportedClaims >= 0) {
            "Agent evaluation unsupported-claim allowance must not be negative."
        }
        this.requiredEvidenceIds = immutableAgentSet(evidenceSnapshot)
        val digest = AgentDigestBuilder("flowweft.agent.evaluation.citation-expectation.v1")
            .add(minimumValidCitations)
            .add(maximumUnsupportedClaims)
            .add(this.requiredEvidenceIds.size)
        this.requiredEvidenceIds.map { evidenceId -> evidenceId.value }.sorted().forEach(digest::add)
        bindingDigest = digest.finish()
    }
}

enum class AgentEvaluationToolDecision {
    INVOKE,
    SKIP,
    REQUIRE_APPROVAL,
}

class AgentEvaluationToolExpectation @JvmOverloads constructor(
    val decision: AgentEvaluationToolDecision,
    val providerId: ProviderId? = null,
    val toolId: ToolId? = null,
    argumentsDigest: String? = null,
) {
    val argumentsDigest: String? = argumentsDigest?.let { digest ->
        requireSha256(digest, "Agent evaluation expected tool arguments digest is invalid.")
    }
    val bindingDigest: String

    init {
        val expectsTool = decision == AgentEvaluationToolDecision.INVOKE ||
            decision == AgentEvaluationToolDecision.REQUIRE_APPROVAL
        require(!expectsTool || providerId != null && toolId != null) {
            "Agent evaluation tool invocation expectations require provider and tool identifiers."
        }
        require(expectsTool || providerId == null && toolId == null && this.argumentsDigest == null) {
            "Agent evaluation SKIP expectations cannot identify a tool or arguments."
        }
        bindingDigest = AgentDigestBuilder("flowweft.agent.evaluation.tool-expectation.v1")
            .add(decision.name)
            .add(providerId?.value ?: "-")
            .add(toolId?.value ?: "-")
            .add(this.argumentsDigest ?: "-")
            .finish()
    }
}

enum class AgentEvaluationRefusalExpectation {
    MUST_ANSWER,
    MUST_REFUSE,
    NOT_APPLICABLE,
}

/** Provider-neutral expected result for one regression case. */
class AgentEvaluationExpectedOutcome @JvmOverloads constructor(
    val retrieval: AgentEvaluationRetrievalExpectation? = null,
    val citations: AgentEvaluationCitationExpectation? = null,
    val tool: AgentEvaluationToolExpectation? = null,
    val refusal: AgentEvaluationRefusalExpectation = AgentEvaluationRefusalExpectation.NOT_APPLICABLE,
    val maximumCostMicros: Long? = null,
    val maximumLatencyMillis: Long? = null,
) {
    val bindingDigest: String

    init {
        require(maximumCostMicros == null || maximumCostMicros >= 0L) {
            "Agent evaluation cost limit must not be negative."
        }
        require(maximumLatencyMillis == null || maximumLatencyMillis > 0L) {
            "Agent evaluation latency limit must be positive."
        }
        require(
            retrieval != null || citations != null || tool != null ||
                refusal != AgentEvaluationRefusalExpectation.NOT_APPLICABLE ||
                maximumCostMicros != null || maximumLatencyMillis != null,
        ) { "Agent evaluation expected outcome must define at least one assertion." }
        require(refusal != AgentEvaluationRefusalExpectation.MUST_REFUSE || tool?.decision != AgentEvaluationToolDecision.INVOKE) {
            "A mandatory refusal cannot also require direct tool invocation."
        }
        bindingDigest = AgentDigestBuilder("flowweft.agent.evaluation.expected-outcome.v1")
            .add(retrieval?.bindingDigest ?: "-")
            .add(citations?.bindingDigest ?: "-")
            .add(tool?.bindingDigest ?: "-")
            .add(refusal.name)
            .add(maximumCostMicros?.toString() ?: "-")
            .add(maximumLatencyMillis?.toString() ?: "-")
            .finish()
    }
}

/**
 * One fixed case. [fixtureId] is resolved by a trusted fixture store; only its canonical input
 * digest crosses this contract, so diagnostics cannot disclose the prompt or protected content.
 */
class AgentEvaluationCase(
    caseId: Identifier,
    fixtureId: Identifier,
    val capabilityId: AgentCapabilityId,
    inputDigest: String,
    val expected: AgentEvaluationExpectedOutcome,
    tags: Collection<String>,
) {
    val caseId: Identifier = requireOpaqueIdentifier(caseId, "Agent evaluation case identifier is invalid.")
    val fixtureId: Identifier = requireOpaqueIdentifier(fixtureId, "Agent evaluation fixture identifier is invalid.")
    val inputDigest: String = requireSha256(inputDigest, "Agent evaluation fixture input digest is invalid.")
    val tags: Set<String>
    val bindingDigest: String

    init {
        val tagSnapshot = immutableAgentList(tags).map { tag ->
            requireAgentCode(tag, "Agent evaluation case tag is invalid.")
        }
        require(tagSnapshot.size <= AgentContractLimits.MAX_CAPABILITIES) {
            "Agent evaluation case contains too many tags."
        }
        require(tagSnapshot.toSet().size == tagSnapshot.size) {
            "Agent evaluation case tags must be unique."
        }
        this.tags = immutableAgentSet(tagSnapshot)
        val digest = AgentDigestBuilder("flowweft.agent.evaluation.case.v1")
            .add(this.caseId.value)
            .add(this.fixtureId.value)
            .add(capabilityId.value)
            .add(this.inputDigest)
            .add(expected.bindingDigest)
            .add(this.tags.size)
        this.tags.sorted().forEach(digest::add)
        bindingDigest = digest.finish()
    }

    override fun toString(): String = "AgentEvaluationCase(caseId=<redacted>)"
}

/** Versioned, digest-bound fixed regression set. */
class AgentEvaluationSuite(
    suiteId: Identifier,
    name: String,
    version: String,
    cases: Collection<AgentEvaluationCase>,
    val createdAt: Long,
) {
    val suiteId: Identifier = requireOpaqueIdentifier(suiteId, "Agent evaluation suite identifier is invalid.")
    val name: String = requireAgentToken(
        name,
        AgentContractLimits.MAX_NAME_CODE_POINTS,
        "Agent evaluation suite name is invalid.",
    )
    val version: String = requireAgentToken(
        version,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent evaluation suite version is invalid.",
    )
    val cases: List<AgentEvaluationCase>
    val suiteDigest: String

    init {
        val caseSnapshot = immutableAgentList(cases)
        require(caseSnapshot.isNotEmpty()) { "Agent evaluation suite requires at least one case." }
        require(caseSnapshot.size <= AgentContractLimits.MAX_CITATIONS) {
            "Agent evaluation suite contains too many cases."
        }
        require(caseSnapshot.map { case -> case.caseId }.toSet().size == caseSnapshot.size) {
            "Agent evaluation suite case identifiers must be unique."
        }
        requireNonNegativeTime(createdAt, "Agent evaluation suite creation time must not be negative.")
        this.cases = caseSnapshot
        val digest = AgentDigestBuilder("flowweft.agent.evaluation.suite.v1")
            .add(this.suiteId.value)
            .add(this.name)
            .add(this.version)
            .add(createdAt)
            .add(this.cases.size)
        this.cases.forEach { case -> digest.add(case.bindingDigest) }
        suiteDigest = digest.finish()
    }

    override fun toString(): String = "AgentEvaluationSuite(name=$name, version=$version, cases=${cases.size})"
}
