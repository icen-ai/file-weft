package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletionStage

/** Authorization-safe citation metadata. It intentionally excludes excerpts, titles and storage keys. */
class AgentCitation @JvmOverloads constructor(
    citationId: Identifier,
    tenantId: Identifier,
    documentId: Identifier,
    documentVersionId: Identifier,
    evidenceId: Identifier,
    contentDigest: String,
    val startOffset: Long? = null,
    val endOffset: Long? = null,
    val pageNumber: Int? = null,
) {
    val citationId: Identifier = requireOpaqueIdentifier(citationId, "Agent citation identifier is invalid.")
    val tenantId: Identifier = requireOpaqueIdentifier(tenantId, "Agent citation tenant identifier is invalid.")
    val documentId: Identifier = requireOpaqueIdentifier(documentId, "Agent citation document identifier is invalid.")
    val documentVersionId: Identifier = requireOpaqueIdentifier(
        documentVersionId,
        "Agent citation document version identifier is invalid.",
    )
    val evidenceId: Identifier = requireOpaqueIdentifier(evidenceId, "Agent citation evidence identifier is invalid.")
    val contentDigest: String = requireSha256(contentDigest, "Agent citation content digest is invalid.")

    init {
        require((startOffset == null) == (endOffset == null)) {
            "Agent citation offsets must be provided together."
        }
        if (startOffset != null && endOffset != null) {
            require(startOffset >= 0 && endOffset > startOffset) { "Agent citation offsets are invalid." }
        }
        require(pageNumber == null || pageNumber > 0) { "Agent citation page number must be positive." }
    }
}

class AgentCitationContentBlock(
    val citation: AgentCitation,
) : AgentSizedContentBlock {
    override fun kind(): String = KIND

    override fun origin(): AgentContentOrigin = AgentContentOrigin.RETRIEVAL

    override fun bindingDigest(): String = AgentDigestBuilder("flowweft.agent.content.citation.v1")
        .add(KIND)
        .add(origin().name)
        .add(citation.citationId.value)
        .add(citation.tenantId.value)
        .add(citation.documentId.value)
        .add(citation.documentVersionId.value)
        .add(citation.evidenceId.value)
        .add(citation.contentDigest)
        .add(citation.startOffset?.toString() ?: "-")
        .add(citation.endOffset?.toString() ?: "-")
        .add(citation.pageNumber?.toString() ?: "-")
        .finish()

    override fun canonicalPayloadSizeBytes(): Long = listOf(
        citation.citationId.value,
        citation.tenantId.value,
        citation.documentId.value,
        citation.documentVersionId.value,
        citation.evidenceId.value,
        citation.contentDigest,
        citation.startOffset?.toString() ?: "-",
        citation.endOffset?.toString() ?: "-",
        citation.pageNumber?.toString() ?: "-",
    ).fold(0L) { total, value ->
        Math.addExact(total, value.toByteArray(StandardCharsets.UTF_8).size.toLong())
    }

    companion object {
        const val KIND: String = "citation"
    }
}

/** Immutable supported-criteria snapshot for an evaluator. */
class AgentEvaluatorDescriptor(
    val providerId: ProviderId,
    criteria: Collection<AgentCapabilityId>,
    val maximumCitations: Int,
) {
    val criteria: Set<AgentCapabilityId>

    init {
        val criteriaSnapshot = immutableAgentList(criteria)
        require(criteriaSnapshot.isNotEmpty()) { "Agent evaluator must declare at least one criterion." }
        require(criteriaSnapshot.size <= AgentContractLimits.MAX_CAPABILITIES) {
            "Agent evaluator declares too many criteria."
        }
        require(criteriaSnapshot.toSet().size == criteriaSnapshot.size) { "Agent evaluator criteria must be unique." }
        require(maximumCitations in 0..AgentContractLimits.MAX_CITATIONS) {
            "Agent evaluator citation limit is invalid."
        }
        this.criteria = immutableAgentSet(criteriaSnapshot)
    }
}

/** Evaluation is diagnostic only and must never be used as an authorization decision. */
class AgentEvaluationRequest(
    requestId: Identifier,
    tenantId: Identifier,
    runId: Identifier,
    val output: AgentMessage,
    citations: List<AgentCitation>,
    criteria: Collection<AgentCapabilityId>,
    val requestedAt: Long,
    val deadlineAt: Long,
    val cancellationToken: AgentCancellationToken,
) {
    val requestId: Identifier = requireOpaqueIdentifier(requestId, "Agent evaluation request identifier is invalid.")
    val tenantId: Identifier = requireOpaqueIdentifier(tenantId, "Agent evaluation tenant identifier is invalid.")
    val runId: Identifier = requireOpaqueIdentifier(runId, "Agent evaluation run identifier is invalid.")
    val citations: List<AgentCitation>
    val criteria: Set<AgentCapabilityId>

    init {
        val citationSnapshot = immutableAgentList(citations)
        val criteriaSnapshot = immutableAgentList(criteria)
        require(output.role == AgentMessageRole.ASSISTANT) { "Agent evaluation output must be an assistant message." }
        output.requireBindingIntact()
        require(citationSnapshot.size <= AgentContractLimits.MAX_CITATIONS) {
            "Agent evaluation contains too many citations."
        }
        require(citationSnapshot.map { citation -> citation.citationId }.distinct().size == citationSnapshot.size) {
            "Agent evaluation citation identifiers must be unique."
        }
        require(citationSnapshot.all { citation -> citation.tenantId == this.tenantId }) {
            "Agent evaluation citations must belong to the request tenant."
        }
        require(criteriaSnapshot.isNotEmpty()) { "Agent evaluation requires at least one criterion." }
        require(criteriaSnapshot.size <= AgentContractLimits.MAX_CAPABILITIES) {
            "Agent evaluation contains too many criteria."
        }
        require(criteriaSnapshot.toSet().size == criteriaSnapshot.size) { "Agent evaluation criteria must be unique." }
        requireNonNegativeTime(requestedAt, "Agent evaluation request time must not be negative.")
        require(deadlineAt > requestedAt) { "Agent evaluation deadline must follow request time." }
        this.citations = citationSnapshot
        this.criteria = immutableAgentSet(criteriaSnapshot)
    }

    fun requireSupportedBy(descriptor: AgentEvaluatorDescriptor) {
        output.requireBindingIntact()
        require(descriptor.criteria.containsAll(criteria)) {
            "Agent evaluator does not support every requested criterion."
        }
        require(citations.size <= descriptor.maximumCitations) {
            "Agent evaluation exceeds the selected evaluator citation limit."
        }
    }
}

enum class AgentEvaluationOutcome {
    PASS,
    FAIL,
    INCONCLUSIVE,
}

class AgentEvaluationFinding(
    val criterion: AgentCapabilityId,
    val outcome: AgentEvaluationOutcome,
    code: String,
) {
    val code: String = requireAgentCode(code, "Agent evaluation finding code is invalid.")
}

class AgentEvaluationResult @JvmOverloads constructor(
    requestId: Identifier,
    val providerId: ProviderId,
    scores: Map<AgentCapabilityId, Double>,
    findings: List<AgentEvaluationFinding>,
    val completedAt: Long,
    safeSummary: String? = null,
) {
    val requestId: Identifier = requireOpaqueIdentifier(requestId, "Agent evaluation result request identifier is invalid.")
    val scores: Map<AgentCapabilityId, Double>
    val findings: List<AgentEvaluationFinding>
    val safeSummary: String? = requireOptionalAgentContent(
        safeSummary,
        AgentContractLimits.MAX_DESCRIPTION_CODE_POINTS,
        "Agent evaluation summary is invalid.",
    )

    init {
        val scoreSnapshot = immutableAgentMap(scores)
        val findingSnapshot = immutableAgentList(findings)
        require(scoreSnapshot.isNotEmpty()) { "Agent evaluation result requires at least one score." }
        require(scoreSnapshot.size <= AgentContractLimits.MAX_CAPABILITIES) {
            "Agent evaluation result contains too many scores."
        }
        scoreSnapshot.forEach { (_, score) ->
            require(!score.isNaN() && !score.isInfinite() && score in 0.0..1.0) {
                "Agent evaluation score must be finite and between 0 and 1."
            }
        }
        require(findingSnapshot.size <= AgentContractLimits.MAX_CAPABILITIES) {
            "Agent evaluation result contains too many findings."
        }
        require(findingSnapshot.map { finding -> finding.criterion }.distinct().size == findingSnapshot.size) {
            "Agent evaluation finding criteria must be unique."
        }
        requireNonNegativeTime(completedAt, "Agent evaluation completion time must not be negative.")
        this.scores = scoreSnapshot
        this.findings = findingSnapshot
    }

    fun requireValidFor(request: AgentEvaluationRequest, descriptor: AgentEvaluatorDescriptor) {
        request.requireSupportedBy(descriptor)
        require(requestId == request.requestId) { "Agent evaluation result request identifier does not match." }
        require(providerId == descriptor.providerId) { "Agent evaluation result provider does not match." }
        require(scores.keys == request.criteria) { "Agent evaluation result scores do not match requested criteria." }
        require(findings.all { finding -> finding.criterion in request.criteria }) {
            "Agent evaluation result contains an unrequested finding."
        }
        require(completedAt in request.requestedAt..request.deadlineAt) {
            "Agent evaluation completed outside the request lifetime."
        }
    }
}

interface AgentEvaluationEvent {
    val requestId: Identifier
    val sequence: Long
    val occurredAt: Long
}

class AgentEvaluationProgressEvent(
    requestId: Identifier,
    override val sequence: Long,
    override val occurredAt: Long,
    completedCriteria: Collection<AgentCapabilityId>,
    val progressPercent: Int,
) : AgentEvaluationEvent {
    override val requestId: Identifier = requireOpaqueIdentifier(
        requestId,
        "Agent evaluation event request identifier is invalid.",
    )
    val completedCriteria: Set<AgentCapabilityId>

    init {
        val criteriaSnapshot = immutableAgentList(completedCriteria)
        requirePositiveSequence(sequence, "Agent evaluation event sequence must be positive.")
        requireNonNegativeTime(occurredAt, "Agent evaluation event time must not be negative.")
        require(progressPercent in 0..100) { "Agent evaluation progress percent must be between 0 and 100." }
        require(criteriaSnapshot.size <= AgentContractLimits.MAX_CAPABILITIES) {
            "Agent evaluation event contains too many criteria."
        }
        require(criteriaSnapshot.toSet().size == criteriaSnapshot.size) {
            "Agent evaluation event criteria must be unique."
        }
        this.completedCriteria = immutableAgentSet(criteriaSnapshot)
    }
}

interface AgentEvaluationObserver {
    fun onEvent(event: AgentEvaluationEvent)

    companion object {
        @JvmField
        val NOOP: AgentEvaluationObserver = object : AgentEvaluationObserver {
            override fun onEvent(event: AgentEvaluationEvent) = Unit
        }
    }
}

interface AgentEvaluationCall {
    fun requestId(): Identifier

    fun completion(): CompletionStage<AgentEvaluationResult>

    fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean>
}

interface AgentEvaluator {
    fun descriptor(): AgentEvaluatorDescriptor

    fun start(request: AgentEvaluationRequest, observer: AgentEvaluationObserver): AgentEvaluationCall
}
