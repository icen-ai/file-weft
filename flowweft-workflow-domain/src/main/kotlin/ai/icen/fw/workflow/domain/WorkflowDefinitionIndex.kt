package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowDefinition
import ai.icen.fw.workflow.api.WorkflowNodeDefinition
import ai.icen.fw.workflow.api.WorkflowNodeKind
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Immutable deterministic index over an API definition that already passed structural lint.
 *
 * Compilation performs no deployment, provider lookup or authorization. The engine separately
 * requires an exact trusted execution receipt before starting an instance.
 */
class WorkflowDefinitionIndex private constructor(
    val definition: WorkflowDefinition,
    private val nodesById: Map<String, WorkflowNodeDefinition>,
    private val outgoingByNodeId: Map<String, List<WorkflowTransitionDefinition>>,
    private val incomingByNodeId: Map<String, List<WorkflowTransitionDefinition>>,
    val startNode: WorkflowNodeDefinition,
) {
    val contentDigest: String = WorkflowDomainSupport.digest(
        "flowweft-workflow-domain-definition-index-v1",
    )
        .text(definition.tenantId)
        .text(definition.definitionId)
        .text(definition.ref.key)
        .text(definition.ref.version)
        .text(definition.ref.digest)
        .integer(definition.schemaVersion)
        .finish()

    fun node(nodeId: String): WorkflowNodeDefinition = nodesById[nodeId]
        ?: throw IllegalArgumentException("Workflow node is absent from the compiled definition.")

    fun findNode(nodeId: String): WorkflowNodeDefinition? = nodesById[nodeId]

    fun outgoing(nodeId: String): List<WorkflowTransitionDefinition> = outgoingByNodeId[nodeId]
        ?: throw IllegalArgumentException("Workflow node is absent from the compiled definition.")

    fun incoming(nodeId: String): List<WorkflowTransitionDefinition> = incomingByNodeId[nodeId]
        ?: throw IllegalArgumentException("Workflow node is absent from the compiled definition.")

    override fun toString(): String = "WorkflowDefinitionIndex(<redacted>)"

    companion object {
        @JvmStatic
        fun compile(definition: WorkflowDefinition): WorkflowDefinitionIndex {
            require(definition.ref.digest == definition.contentDigest) {
                "Workflow definition reference does not bind its content digest."
            }
            val nodes = LinkedHashMap<String, WorkflowNodeDefinition>(definition.nodes.size)
            val outgoing = LinkedHashMap<String, MutableList<WorkflowTransitionDefinition>>(definition.nodes.size)
            val incoming = LinkedHashMap<String, MutableList<WorkflowTransitionDefinition>>(definition.nodes.size)
            definition.nodes.forEach { node ->
                require(nodes.put(node.nodeId, node) == null) { "Workflow definition node ids are not unique." }
                outgoing[node.nodeId] = ArrayList()
                incoming[node.nodeId] = ArrayList()
            }
            definition.transitions.forEach { transition ->
                outgoing.getValue(transition.fromNodeId).add(transition)
                incoming.getValue(transition.toNodeId).add(transition)
            }
            val frozenOutgoing = LinkedHashMap<String, List<WorkflowTransitionDefinition>>(outgoing.size)
            val frozenIncoming = LinkedHashMap<String, List<WorkflowTransitionDefinition>>(incoming.size)
            outgoing.forEach { (nodeId, transitions) ->
                frozenOutgoing[nodeId] = Collections.unmodifiableList(ArrayList(transitions))
            }
            incoming.forEach { (nodeId, transitions) ->
                frozenIncoming[nodeId] = Collections.unmodifiableList(ArrayList(transitions))
            }
            val start = definition.nodes.single { node -> node.kind == WorkflowNodeKind.START }
            return WorkflowDefinitionIndex(
                definition,
                Collections.unmodifiableMap(nodes),
                Collections.unmodifiableMap(frozenOutgoing),
                Collections.unmodifiableMap(frozenIncoming),
                start,
            )
        }
    }
}
