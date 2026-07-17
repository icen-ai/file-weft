package ai.icen.fw.agent.evaluation

import ai.icen.fw.agent.api.AgentEvaluationCase
import ai.icen.fw.agent.api.AgentEvaluationObservation
import ai.icen.fw.agent.api.AgentEvaluationObservationContext
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.AgentEvaluationSuite
import ai.icen.fw.core.id.Identifier

/** Trusted tenant/principal binding. It grants no authority and is never rendered in reports. */
class AgentEvaluationSubjectBinding(
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    authorizationRevision: String,
) {
    val tenantId: Identifier = requireEvaluationIdentifier(tenantId, "Agent evaluation tenant is invalid.")
    val principalId: Identifier = requireEvaluationIdentifier(principalId, "Agent evaluation principal is invalid.")
    val principalType: String = requireEvaluationCode(principalType, "Agent evaluation principal type is invalid.")
    val authorizationRevision: String = requireEvaluationToken(
        authorizationRevision,
        "Agent evaluation authorization revision is invalid.",
    )
    val bindingDigest: String = AgentEvaluationDigest("flowweft.agent.evaluation.subject.v1")
        .add(this.tenantId.value)
        .add(this.principalType)
        .add(this.principalId.value)
        .add(this.authorizationRevision)
        .finish()

    fun matches(context: AgentEvaluationObservationContext): Boolean =
        tenantId == context.tenantId && principalId == context.principalId &&
            principalType == context.principalType && authorizationRevision == context.authorizationRevision

    override fun equals(other: Any?): Boolean = other is AgentEvaluationSubjectBinding && bindingDigest == other.bindingDigest
    override fun hashCode(): Int = bindingDigest.hashCode()
    override fun toString(): String = "AgentEvaluationSubjectBinding(<redacted>)"

    companion object {
        @JvmStatic
        fun from(context: AgentEvaluationObservationContext): AgentEvaluationSubjectBinding = AgentEvaluationSubjectBinding(
            context.tenantId,
            context.principalId,
            context.principalType,
            context.authorizationRevision,
        )
    }
}

/**
 * Payload-free evidence for one fixed case. The prompt/input and generated output stay behind the
 * trusted runtime boundary; only fixture references, canonical digests and bounded observations cross it.
 */
class AgentEvaluationEvidenceBatch(
    val context: AgentEvaluationObservationContext,
    fixtureId: Identifier,
    inputDigest: String,
    outputDigest: String,
    observations: Collection<AgentEvaluationObservation>,
    val completedAt: Long,
) {
    val fixtureId: Identifier = requireEvaluationIdentifier(fixtureId, "Agent evaluation fixture reference is invalid.")
    val inputDigest: String = requireEvaluationDigest(inputDigest, "Agent evaluation input digest is invalid.")
    val outputDigest: String = requireEvaluationDigest(outputDigest, "Agent evaluation output digest is invalid.")
    val observations: List<AgentEvaluationObservation>
    val evidenceDigest: String

    init {
        val snapshot = immutableEvaluationList(
            observations,
            AgentEvaluationLimits.MAX_OBSERVATIONS,
            "Agent evaluation evidence contains too many observations.",
        )
        require(snapshot.isNotEmpty()) { "Agent evaluation evidence requires at least one observation." }
        require(snapshot.map { observation -> observation.kind() }.toSet().size == snapshot.size) {
            "Agent evaluation evidence observation kinds must be unique."
        }
        snapshot.forEach { observation ->
            require(observation.context().bindingDigest == context.bindingDigest) {
                "Agent evaluation evidence escaped its trusted observation context."
            }
            requireEvaluationDigest(
                observation.bindingDigest(),
                "Agent evaluation observation digest is invalid.",
            )
        }
        require(completedAt >= context.observedAt) { "Agent evaluation evidence completion time is invalid." }
        this.observations = snapshot
        val digest = AgentEvaluationDigest("flowweft.agent.evaluation.evidence-batch.v1")
            .add(context.bindingDigest)
            .add(this.fixtureId.value)
            .add(this.inputDigest)
            .add(this.outputDigest)
            .add(completedAt)
            .add(this.observations.size)
        this.observations.sortedBy { observation -> observation.kind().name }.forEach { observation ->
            digest.add(observation.kind().name).add(observation.bindingDigest())
        }
        evidenceDigest = digest.finish()
    }

    fun requireMatches(
        suite: AgentEvaluationSuite,
        case: AgentEvaluationCase,
        subject: AgentEvaluationSubjectBinding,
        providerSnapshot: AgentEvaluationProviderSnapshot,
    ) {
        context.requireMatches(suite, case)
        require(context.observedAt >= suite.createdAt) {
            "Agent evaluation evidence predates its fixed dataset version."
        }
        require(fixtureId == case.fixtureId && inputDigest == case.inputDigest) {
            "Agent evaluation evidence does not match the fixed case input."
        }
        require(subject.matches(context)) { "Agent evaluation evidence does not match its trusted subject." }
        require(context.providerSnapshotDigest == providerSnapshot.snapshotDigest) {
            "Agent evaluation evidence does not match its pinned provider snapshot."
        }
        require(providerSnapshot.isCurrent(context.observedAt) && providerSnapshot.isCurrent(completedAt)) {
            "Agent evaluation evidence was captured outside its pinned provider snapshot lifetime."
        }
    }

    override fun toString(): String =
        "AgentEvaluationEvidenceBatch(observationCount=${observations.size}, <redacted>)"
}
