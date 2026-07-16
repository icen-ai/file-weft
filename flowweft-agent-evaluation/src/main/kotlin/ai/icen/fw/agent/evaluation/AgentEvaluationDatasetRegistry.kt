package ai.icen.fw.agent.evaluation

import ai.icen.fw.agent.api.AgentEvaluationSuite
import ai.icen.fw.core.id.Identifier
import java.util.concurrent.ConcurrentHashMap

/** Exact immutable reference to one versioned regression dataset. */
class AgentEvaluationDatasetReference(
    suiteId: Identifier,
    version: String,
    suiteDigest: String,
) {
    val suiteId: Identifier = requireEvaluationIdentifier(suiteId, "Agent evaluation dataset identifier is invalid.")
    val version: String = requireEvaluationToken(version, "Agent evaluation dataset version is invalid.")
    val suiteDigest: String = requireEvaluationDigest(suiteDigest, "Agent evaluation dataset digest is invalid.")
    val bindingDigest: String = AgentEvaluationDigest("flowweft.agent.evaluation.dataset-reference.v1")
        .add(this.suiteId.value)
        .add(this.version)
        .add(this.suiteDigest)
        .finish()

    fun matches(suite: AgentEvaluationSuite): Boolean =
        suiteId == suite.suiteId && version == suite.version && suiteDigest == suite.suiteDigest

    override fun equals(other: Any?): Boolean = other is AgentEvaluationDatasetReference && bindingDigest == other.bindingDigest
    override fun hashCode(): Int = bindingDigest.hashCode()
    override fun toString(): String = "AgentEvaluationDatasetReference(<redacted>)"

    companion object {
        @JvmStatic
        fun from(suite: AgentEvaluationSuite): AgentEvaluationDatasetReference =
            AgentEvaluationDatasetReference(suite.suiteId, suite.version, suite.suiteDigest)
    }
}

enum class AgentEvaluationDatasetRegistration {
    REGISTERED,
    ALREADY_REGISTERED,
}

interface AgentEvaluationDatasetRegistry {
    fun find(suiteId: Identifier, version: String): AgentEvaluationSuite?
}

/**
 * Process-local registry for tests, embedded deployments and control-plane assembly.
 * Reusing a suite/version with different bytes is rejected instead of silently replacing history.
 */
class InMemoryAgentEvaluationDatasetRegistry @JvmOverloads constructor(
    initialSuites: Collection<AgentEvaluationSuite> = emptyList(),
) : AgentEvaluationDatasetRegistry {
    private val suites = ConcurrentHashMap<DatasetKey, AgentEvaluationSuite>()

    init {
        initialSuites.forEach(::register)
    }

    fun register(suite: AgentEvaluationSuite): AgentEvaluationDatasetRegistration {
        val key = DatasetKey(suite.suiteId.value, suite.version)
        val existing = suites.putIfAbsent(key, suite)
        if (existing == null) return AgentEvaluationDatasetRegistration.REGISTERED
        require(existing.suiteDigest == suite.suiteDigest) {
            "Agent evaluation dataset version is already bound to a different digest."
        }
        return AgentEvaluationDatasetRegistration.ALREADY_REGISTERED
    }

    override fun find(suiteId: Identifier, version: String): AgentEvaluationSuite? = suites[
        DatasetKey(
            requireEvaluationIdentifier(suiteId, "Agent evaluation dataset identifier is invalid.").value,
            requireEvaluationToken(version, "Agent evaluation dataset version is invalid."),
        ),
    ]

    fun find(reference: AgentEvaluationDatasetReference): AgentEvaluationSuite? =
        find(reference.suiteId, reference.version)?.takeIf(reference::matches)

    private class DatasetKey(
        private val suiteId: String,
        private val version: String,
    ) {
        override fun equals(other: Any?): Boolean = other is DatasetKey && suiteId == other.suiteId && version == other.version
        override fun hashCode(): Int = 31 * suiteId.hashCode() + version.hashCode()
    }
}
