package ai.icen.fw.agent.evaluation

import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.ProviderId
import java.util.concurrent.ConcurrentHashMap

/** Local, secret-free provider inventory. Implementations must not probe a network from this method. */
fun interface AgentEvaluationProviderInventory {
    fun current(providerId: ProviderId): AgentEvaluationProviderSnapshot?
}

/** Process-local inventory used by the minimal runner and embedded hosts. */
class InMemoryAgentEvaluationProviderInventory @JvmOverloads constructor(
    initialSnapshots: Collection<AgentEvaluationProviderSnapshot> = emptyList(),
) : AgentEvaluationProviderInventory {
    private val snapshots = ConcurrentHashMap<ProviderId, AgentEvaluationProviderSnapshot>()

    init {
        initialSnapshots.forEach(::publish)
    }

    /** Publishes a new immutable revision; callers pin the exact snapshot digest in each run. */
    fun publish(snapshot: AgentEvaluationProviderSnapshot) {
        snapshots[snapshot.providerId] = snapshot
    }

    fun remove(providerId: ProviderId): Boolean = snapshots.remove(providerId) != null

    override fun current(providerId: ProviderId): AgentEvaluationProviderSnapshot? = snapshots[providerId]
}

interface AgentEvaluationEvaluatorRegistry {
    fun find(evaluatorId: ProviderId, implementationVersion: String): AgentEvaluationCaseEvaluator?
}

/** Exact-revision evaluator registry. One id/version can never be rebound to another implementation digest. */
class InMemoryAgentEvaluationEvaluatorRegistry @JvmOverloads constructor(
    initialEvaluators: Collection<AgentEvaluationCaseEvaluator> = emptyList(),
) : AgentEvaluationEvaluatorRegistry {
    private val evaluators = ConcurrentHashMap<EvaluatorKey, AgentEvaluationCaseEvaluator>()

    init {
        initialEvaluators.forEach(::register)
    }

    fun register(evaluator: AgentEvaluationCaseEvaluator): Boolean {
        val descriptor = evaluator.descriptor()
        val key = EvaluatorKey(descriptor.evaluatorId, descriptor.implementationVersion)
        val existing = evaluators.putIfAbsent(key, evaluator) ?: return true
        require(existing.descriptor().bindingDigest == descriptor.bindingDigest) {
            "Agent evaluation evaluator revision is already bound to a different descriptor."
        }
        return false
    }

    override fun find(evaluatorId: ProviderId, implementationVersion: String): AgentEvaluationCaseEvaluator? = evaluators[
        EvaluatorKey(
            evaluatorId,
            requireEvaluationToken(implementationVersion, "Agent evaluation evaluator version is invalid."),
        ),
    ]

    private class EvaluatorKey(
        private val evaluatorId: ProviderId,
        private val version: String,
    ) {
        override fun equals(other: Any?): Boolean = other is EvaluatorKey &&
            evaluatorId == other.evaluatorId && version == other.version
        override fun hashCode(): Int = 31 * evaluatorId.hashCode() + version.hashCode()
    }
}
