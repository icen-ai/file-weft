package ai.icen.fw.workflow.api

import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Immutable, versioned and structurally linted neutral workflow definition.
 *
 * [tenantId], [definitionId] and [key] are tenant-relative caller claims. [contentDigest] binds
 * tenant id, definition id, key, version, schema version, text, and the ordered graph; it is not a
 * publication/deployment envelope digest. Lifecycle [status] is intentionally excluded, so promoting identical DRAFT
 * content to PUBLISHED does not change [ref]; only a trusted runtime publish receipt can prove the
 * exact content passed provider, authorization and deployment gates. Unknown kinds, predicate
 * sources or extension values remain representable but are non-executable without explicit runtime
 * support.
 *
 * Lint provides bounded neutral graph safety, including single-entry/single-exit, strictly nested
 * parallel regions, not full BPMN conformance. It accepts cycles that retain an exit to an end
 * node; a runtime must additionally enforce durable iteration/job budgets.
 */
class WorkflowDefinition private constructor(
    tenantId: String,
    definitionId: String,
    key: String,
    version: String,
    schemaVersion: Int,
    val status: WorkflowDefinitionStatus,
    title: String,
    description: String?,
    nodes: Collection<WorkflowNodeDefinition>,
    transitions: Collection<WorkflowTransitionDefinition>,
) {
    val tenantId: String = WorkflowContractSupport.requireText(
        tenantId,
        WorkflowContractSupport.MAX_REFERENCE_ID_UTF8_BYTES,
        "Workflow definition tenant identifier is invalid.",
    )
    val definitionId: String = WorkflowContractSupport.requireText(
        definitionId,
        WorkflowContractSupport.MAX_REFERENCE_ID_UTF8_BYTES,
        "Workflow definition identifier is invalid.",
    )
    val key: String = WorkflowContractSupport.requireText(
        key,
        WorkflowContractSupport.MAX_DEFINITION_KEY_UTF8_BYTES,
        "Workflow definition key is invalid.",
    )
    val version: String = WorkflowContractSupport.requireText(
        version,
        WorkflowContractSupport.MAX_DEFINITION_VERSION_UTF8_BYTES,
        "Workflow definition version is invalid.",
    )
    val schemaVersion: Int = schemaVersion.also { value ->
        require(value in 1..WorkflowContractSupport.MAX_SCHEMA_VERSION) {
            "Workflow definition schema version is invalid."
        }
    }
    val title: String = WorkflowContractSupport.requireText(
        title,
        WorkflowContractSupport.MAX_TITLE_UTF8_BYTES,
        "Workflow definition title is invalid or exceeds the limit.",
    )
    val description: String? = description?.let { value ->
        WorkflowContractSupport.requireMultilineText(
            value,
            WorkflowContractSupport.MAX_DESCRIPTION_UTF8_BYTES,
            "Workflow definition description is invalid or exceeds the limit.",
        )
    }
    val nodes: List<WorkflowNodeDefinition> = WorkflowContractSupport.immutableList(
        nodes,
        WorkflowContractSupport.MAX_DEFINITION_NODES,
        "Workflow definition nodes are invalid or exceed the limit.",
    )
    val transitions: List<WorkflowTransitionDefinition> = WorkflowContractSupport.immutableList(
        transitions,
        WorkflowContractSupport.MAX_DEFINITION_TRANSITIONS,
        "Workflow definition transitions are invalid or exceed the limit.",
    )
    val contentDigest: String
    val ref: WorkflowDefinitionRef

    init {
        lint(this.nodes, this.transitions)

        val writer = WorkflowContractSupport.digest(WorkflowContractSupport.DEFINITION_CONTENT_DIGEST_DOMAIN)
            .text(this.tenantId)
            .text(this.definitionId)
            .text(this.key)
            .text(this.version)
            .integer(this.schemaVersion)
            .text(this.title)
            .optionalText(this.description)
            .integer(this.nodes.size)
        this.nodes.forEach { node -> writer.text(node.contentDigest) }
        writer.integer(this.transitions.size)
        this.transitions.forEach { transition -> writer.text(transition.contentDigest) }
        contentDigest = writer.finish()
        ref = WorkflowDefinitionRef.of(this.key, this.version, contentDigest)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowDefinition &&
            tenantId == other.tenantId &&
            definitionId == other.definitionId &&
            key == other.key &&
            version == other.version &&
            schemaVersion == other.schemaVersion &&
            status == other.status &&
            title == other.title &&
            description == other.description &&
            nodes == other.nodes &&
            transitions == other.transitions

    override fun hashCode(): Int {
        var result = tenantId.hashCode()
        result = 31 * result + definitionId.hashCode()
        result = 31 * result + key.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + status.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + nodes.hashCode()
        result = 31 * result + transitions.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowDefinition(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            definitionId: String,
            key: String,
            version: String,
            schemaVersion: Int,
            status: WorkflowDefinitionStatus,
            title: String,
            description: String?,
            nodes: Collection<WorkflowNodeDefinition>,
            transitions: Collection<WorkflowTransitionDefinition>,
        ): WorkflowDefinition = WorkflowDefinition(
            tenantId,
            definitionId,
            key,
            version,
            schemaVersion,
            status,
            title,
            description,
            nodes,
            transitions,
        )

        private fun lint(
            nodes: List<WorkflowNodeDefinition>,
            transitions: List<WorkflowTransitionDefinition>,
        ) {
            require(nodes.size >= 2) { "Workflow definitions require at least two nodes." }
            require(transitions.isNotEmpty()) { "Workflow definitions require at least one transition." }

            val nodesById = LinkedHashMap<String, WorkflowNodeDefinition>(nodes.size)
            nodes.forEach { node ->
                require(nodesById.put(node.nodeId, node) == null) {
                    "Workflow definition node identifiers must be unique."
                }
            }

            val starts = nodes.filter { node -> node.kind == WorkflowNodeKind.START }
            val ends = nodes.filter { node -> node.kind == WorkflowNodeKind.END }
            require(starts.size == 1) { "Workflow definitions require exactly one start node." }
            require(ends.isNotEmpty()) { "Workflow definitions require at least one end node." }

            val incoming = LinkedHashMap<String, MutableList<WorkflowTransitionDefinition>>(nodes.size)
            val outgoing = LinkedHashMap<String, MutableList<WorkflowTransitionDefinition>>(nodes.size)
            nodes.forEach { node ->
                incoming[node.nodeId] = ArrayList()
                outgoing[node.nodeId] = ArrayList()
            }

            val transitionIds = LinkedHashSet<String>()
            val directedEdges = LinkedHashSet<String>()
            transitions.forEach { transition ->
                require(transitionIds.add(transition.transitionId)) {
                    "Workflow definition transition identifiers must be unique."
                }
                require(nodesById.containsKey(transition.fromNodeId) && nodesById.containsKey(transition.toNodeId)) {
                    "Workflow transition endpoints must reference definition nodes."
                }
                require(directedEdges.add(transition.fromNodeId + '\u0000' + transition.toNodeId)) {
                    "Workflow definitions cannot contain duplicate directed edges."
                }
                outgoing.getValue(transition.fromNodeId).add(transition)
                incoming.getValue(transition.toNodeId).add(transition)
            }

            nodes.forEach { node ->
                val nodeIncoming = incoming.getValue(node.nodeId)
                val nodeOutgoing = outgoing.getValue(node.nodeId)
                when (node.kind) {
                    WorkflowNodeKind.START -> {
                        require(nodeIncoming.isEmpty() && nodeOutgoing.size == 1) {
                            "Workflow start nodes require no incoming and exactly one outgoing transition."
                        }
                        require(
                            nodeOutgoing.single().predicate == null &&
                                nodeOutgoing.single().trigger == WorkflowTransitionTrigger.COMPLETED,
                        ) {
                            "Workflow start transitions require the completed trigger without a predicate."
                        }
                    }

                    WorkflowNodeKind.END -> require(nodeIncoming.isNotEmpty() && nodeOutgoing.isEmpty()) {
                        "Workflow end nodes require incoming and no outgoing transitions."
                    }

                    WorkflowNodeKind.PARALLEL_SPLIT -> {
                        require(nodeIncoming.size == 1 && nodeOutgoing.size >= 2) {
                            "Workflow parallel splits require one incoming and at least two outgoing transitions."
                        }
                        require(nodeOutgoing.all { transition ->
                            transition.predicate == null &&
                                transition.trigger == WorkflowTransitionTrigger.COMPLETED
                        }) {
                            "Workflow parallel split transitions require the completed trigger without predicates."
                        }
                    }

                    WorkflowNodeKind.PARALLEL_JOIN -> {
                        require(nodeIncoming.size >= 2 && nodeOutgoing.size == 1) {
                            "Workflow parallel joins require at least two incoming and one outgoing transition."
                        }
                        require(
                            nodeOutgoing.single().predicate == null &&
                                nodeOutgoing.single().trigger == WorkflowTransitionTrigger.COMPLETED,
                        ) {
                            "Workflow parallel join transitions require the completed trigger without a predicate."
                        }
                    }

                    WorkflowNodeKind.EXCLUSIVE_GATEWAY -> lintExclusiveGateway(nodeIncoming, nodeOutgoing)

                    WorkflowNodeKind.HUMAN_TASK -> lintHumanTask(nodeIncoming, nodeOutgoing)

                    else -> {
                        require(nodeIncoming.size == 1 && nodeOutgoing.size == 1) {
                            "Workflow task, decision, wait, subprocess and extension nodes require one incoming and one outgoing transition."
                        }
                        require(
                            nodeOutgoing.single().predicate == null &&
                                nodeOutgoing.single().trigger == WorkflowTransitionTrigger.COMPLETED,
                        ) {
                            "Ordinary workflow nodes require the completed trigger without a predicate."
                        }
                    }
                }
            }

            val forwardReachable = reachable(
                listOf(starts.single().nodeId),
                outgoing,
                true,
            )
            require(forwardReachable.size == nodes.size) {
                "Every workflow node must be reachable from the start node."
            }
            val reverseReachable = reachable(
                ends.map { node -> node.nodeId },
                incoming,
                false,
            )
            require(reverseReachable.size == nodes.size) {
                "Every workflow node and cycle must retain a path to an end node."
            }

            lintParallelPairs(
                nodes,
                nodesById,
                starts.single().nodeId,
                incoming,
                outgoing,
                ends.map { node -> node.nodeId }.toSet(),
            )
        }

        private fun lintExclusiveGateway(
            incoming: List<WorkflowTransitionDefinition>,
            outgoing: List<WorkflowTransitionDefinition>,
        ) {
            val split = incoming.size == 1 && outgoing.size >= 2
            val merge = incoming.size >= 2 && outgoing.size == 1
            require(split || merge) {
                "Workflow exclusive gateways must be either a split or a merge."
            }
            if (split) {
                require(outgoing.all { transition ->
                    transition.trigger == WorkflowTransitionTrigger.COMPLETED
                }) {
                    "Workflow exclusive gateway transitions require the completed trigger."
                }
                val conditionalCount = outgoing.count { transition -> transition.predicate != null }
                val defaultIndexes = outgoing.indices.filter { index -> outgoing[index].predicate == null }
                require(conditionalCount >= 1 && defaultIndexes.size <= 1) {
                    "Workflow exclusive splits require predicates and at most one default transition."
                }
                require(defaultIndexes.isEmpty() || defaultIndexes.single() == outgoing.lastIndex) {
                    "Workflow exclusive default transitions must be last in definition order."
                }
            } else {
                require(
                    outgoing.single().predicate == null &&
                        outgoing.single().trigger == WorkflowTransitionTrigger.COMPLETED,
                ) {
                    "Workflow exclusive merge transitions require the completed trigger without a predicate."
                }
            }
        }

        private fun lintHumanTask(
            incoming: List<WorkflowTransitionDefinition>,
            outgoing: List<WorkflowTransitionDefinition>,
        ) {
            require(incoming.size == 1 && outgoing.size >= 2) {
                "Workflow human tasks require one incoming and at least approved and rejected transitions."
            }
            require(outgoing.all { transition -> transition.predicate == null }) {
                "Workflow human task outcome transitions cannot be conditional."
            }
            require(outgoing.none { transition ->
                transition.trigger == WorkflowTransitionTrigger.COMPLETED
            }) {
                "Workflow human tasks cannot use an ambiguous completed transition."
            }
            require(outgoing.map { transition -> transition.trigger.code }.toSet().size == outgoing.size) {
                "Workflow human task transition triggers must be unique."
            }
            require(
                outgoing.count { transition ->
                    transition.trigger == WorkflowTransitionTrigger.APPROVED
                } == 1 &&
                    outgoing.count { transition ->
                        transition.trigger == WorkflowTransitionTrigger.REJECTED
                    } == 1,
            ) {
                "Workflow human tasks require exactly one approved and one rejected transition."
            }
        }

        private fun lintParallelPairs(
            nodes: List<WorkflowNodeDefinition>,
            nodesById: Map<String, WorkflowNodeDefinition>,
            startNodeId: String,
            incoming: Map<String, List<WorkflowTransitionDefinition>>,
            outgoing: Map<String, List<WorkflowTransitionDefinition>>,
            endNodeIds: Set<String>,
        ) {
            nodes.filter { node -> node.kind == WorkflowNodeKind.PARALLEL_JOIN }.forEach { join ->
                val splitId = join.parallelPairNodeId
                    ?: throw IllegalArgumentException("Workflow parallel join pair is missing.")
                val split = nodesById[splitId]
                require(split != null && split.kind == WorkflowNodeKind.PARALLEL_SPLIT &&
                    split.parallelPairNodeId == join.nodeId
                ) {
                    "Workflow parallel join/split pairs must be reciprocal and kind-correct."
                }
            }

            val nodeIds = nodes.map { node -> node.nodeId }
            val dominators = dominanceSets(nodeIds, setOf(startNodeId), incoming, false)
            val postDominators = dominanceSets(nodeIds, endNodeIds, outgoing, true)
            val regions = ArrayList<ParallelRegion>()

            nodes.filter { node -> node.kind == WorkflowNodeKind.PARALLEL_SPLIT }.forEach { split ->
                val joinId = split.parallelPairNodeId
                    ?: throw IllegalArgumentException("Workflow parallel split pair is missing.")
                val join = nodesById[joinId]
                require(join != null && join.kind == WorkflowNodeKind.PARALLEL_JOIN &&
                    join.parallelPairNodeId == split.nodeId
                ) {
                    "Workflow parallel split/join pairs must be reciprocal and kind-correct."
                }

                require(dominators.getValue(joinId).contains(split.nodeId)) {
                    "Workflow parallel regions require their split to dominate the paired join."
                }
                require(postDominators.getValue(split.nodeId).contains(joinId)) {
                    "Workflow parallel regions require their join to post-dominate the paired split."
                }

                val members = parallelRegionMembers(split.nodeId, joinId, incoming, outgoing)
                require(members.contains(split.nodeId) && members.contains(joinId)) {
                    "Workflow parallel regions require a connected split-to-join region."
                }
                members.forEach { memberNodeId ->
                    require(dominators.getValue(memberNodeId).contains(split.nodeId)) {
                        "Workflow parallel regions cannot have an external entry."
                    }
                    require(postDominators.getValue(memberNodeId).contains(joinId)) {
                        "Workflow parallel regions cannot have an external exit."
                    }
                    if (memberNodeId != split.nodeId) {
                        require(incoming.getValue(memberNodeId).all { transition ->
                            members.contains(transition.fromNodeId)
                        }) {
                            "Workflow parallel regions cannot have an external entry."
                        }
                    }
                    if (memberNodeId != joinId) {
                        require(outgoing.getValue(memberNodeId).all { transition ->
                            members.contains(transition.toNodeId)
                        }) {
                            "Workflow parallel regions cannot have an external exit."
                        }
                    }
                }

                val branchOwnerByNodeId = LinkedHashMap<String, String>()
                outgoing.getValue(split.nodeId).forEach { branch ->
                    require(canReach(branch.toNodeId, joinId, outgoing)) {
                        "Every workflow parallel branch must reach its paired join."
                    }
                    require(!canReachEndWithout(branch.toNodeId, joinId, endNodeIds, outgoing)) {
                        "Workflow parallel branches cannot bypass their paired join."
                    }
                    val branchMembers = reachableBeforeBoundary(
                        branch.toNodeId,
                        joinId,
                        outgoing,
                        true,
                    )
                    branchMembers.remove(joinId)
                    branchMembers.forEach { branchNodeId ->
                        require(members.contains(branchNodeId)) {
                            "Workflow parallel branches cannot leave and re-enter their paired region."
                        }
                        val existingOwner = branchOwnerByNodeId[branchNodeId]
                        require(existingOwner == null || existingOwner == branch.transitionId) {
                            "Workflow parallel branches cannot merge before their paired join."
                        }
                        branchOwnerByNodeId[branchNodeId] = branch.transitionId
                    }
                }
                incoming.getValue(joinId).forEach { joinInput ->
                    require(members.contains(joinInput.fromNodeId) &&
                        canReachBeforeStop(split.nodeId, joinInput.fromNodeId, joinId, outgoing)
                    ) {
                        "Workflow parallel join inputs must originate within the paired split region."
                    }
                }
                regions.add(ParallelRegion(members))
            }

            regions.indices.forEach { firstIndex ->
                for (secondIndex in firstIndex + 1 until regions.size) {
                    val first = regions[firstIndex]
                    val second = regions[secondIndex]
                    if (first.members.any { nodeId -> second.members.contains(nodeId) }) {
                        val firstStrictlyContainsSecond =
                            first.members.size > second.members.size && first.members.containsAll(second.members)
                        val secondStrictlyContainsFirst =
                            second.members.size > first.members.size && second.members.containsAll(first.members)
                        require(firstStrictlyContainsSecond || secondStrictlyContainsFirst) {
                            "Workflow parallel regions must be disjoint or strictly nested; crossing pairs are invalid."
                        }
                    }
                }
            }
        }

        private fun dominanceSets(
            nodeIds: List<String>,
            roots: Set<String>,
            edgesTowardRoots: Map<String, List<WorkflowTransitionDefinition>>,
            useTransitionTarget: Boolean,
        ): Map<String, Set<String>> {
            val allNodeIds = LinkedHashSet<String>(nodeIds)
            val dominanceByNodeId = LinkedHashMap<String, Set<String>>(nodeIds.size)
            nodeIds.forEach { nodeId ->
                dominanceByNodeId[nodeId] = if (roots.contains(nodeId)) {
                    linkedSetOf(nodeId)
                } else {
                    LinkedHashSet(allNodeIds)
                }
            }

            var changed: Boolean
            do {
                changed = false
                nodeIds.forEach { nodeId ->
                    if (!roots.contains(nodeId)) {
                        val adjacent = edgesTowardRoots.getValue(nodeId)
                        require(adjacent.isNotEmpty()) { "Workflow dominance graph is malformed." }
                        var common: LinkedHashSet<String>? = null
                        adjacent.forEach { transition ->
                            val adjacentNodeId = if (useTransitionTarget) {
                                transition.toNodeId
                            } else {
                                transition.fromNodeId
                            }
                            val adjacentDominance = dominanceByNodeId.getValue(adjacentNodeId)
                            if (common == null) {
                                common = LinkedHashSet(adjacentDominance)
                            } else {
                                common!!.retainAll(adjacentDominance)
                            }
                        }
                        val replacement = common ?: LinkedHashSet()
                        replacement.add(nodeId)
                        if (replacement != dominanceByNodeId.getValue(nodeId)) {
                            dominanceByNodeId[nodeId] = replacement
                            changed = true
                        }
                    }
                }
            } while (changed)

            return dominanceByNodeId
        }

        private fun parallelRegionMembers(
            splitNodeId: String,
            joinNodeId: String,
            incoming: Map<String, List<WorkflowTransitionDefinition>>,
            outgoing: Map<String, List<WorkflowTransitionDefinition>>,
        ): MutableSet<String> {
            val reachableFromSplit = reachableBeforeBoundary(
                splitNodeId,
                joinNodeId,
                outgoing,
                true,
            )
            val canReachJoin = reachableBeforeBoundary(
                joinNodeId,
                splitNodeId,
                incoming,
                false,
            )
            reachableFromSplit.retainAll(canReachJoin)
            return reachableFromSplit
        }

        private fun reachableBeforeBoundary(
            seedNodeId: String,
            boundaryNodeId: String,
            edges: Map<String, List<WorkflowTransitionDefinition>>,
            forward: Boolean,
        ): LinkedHashSet<String> {
            val visited = LinkedHashSet<String>()
            val queue = ArrayDeque<String>()
            visited.add(seedNodeId)
            queue.addLast(seedNodeId)
            while (!queue.isEmpty()) {
                val current = queue.removeFirst()
                if (current == boundaryNodeId) continue
                edges.getValue(current).forEach { transition ->
                    val next = if (forward) transition.toNodeId else transition.fromNodeId
                    if (visited.add(next)) queue.addLast(next)
                }
            }
            return visited
        }

        private class ParallelRegion(
            val members: Set<String>,
        )

        private fun reachable(
            seeds: Collection<String>,
            edges: Map<String, List<WorkflowTransitionDefinition>>,
            forward: Boolean,
        ): Set<String> {
            val visited = LinkedHashSet<String>()
            val queue = ArrayDeque<String>()
            seeds.forEach { seed ->
                if (visited.add(seed)) queue.addLast(seed)
            }
            while (!queue.isEmpty()) {
                val current = queue.removeFirst()
                edges.getValue(current).forEach { transition ->
                    val next = if (forward) transition.toNodeId else transition.fromNodeId
                    if (visited.add(next)) queue.addLast(next)
                }
            }
            return visited
        }

        private fun canReach(
            startNodeId: String,
            targetNodeId: String,
            outgoing: Map<String, List<WorkflowTransitionDefinition>>,
        ): Boolean {
            if (startNodeId == targetNodeId) return true
            val visited = LinkedHashSet<String>()
            val queue = ArrayDeque<String>()
            visited.add(startNodeId)
            queue.addLast(startNodeId)
            while (!queue.isEmpty()) {
                val current = queue.removeFirst()
                outgoing.getValue(current).forEach { transition ->
                    if (transition.toNodeId == targetNodeId) return true
                    if (visited.add(transition.toNodeId)) queue.addLast(transition.toNodeId)
                }
            }
            return false
        }

        private fun canReachEndWithout(
            startNodeId: String,
            stopNodeId: String,
            endNodeIds: Set<String>,
            outgoing: Map<String, List<WorkflowTransitionDefinition>>,
        ): Boolean {
            if (startNodeId == stopNodeId) return false
            val visited = LinkedHashSet<String>()
            val queue = ArrayDeque<String>()
            visited.add(startNodeId)
            queue.addLast(startNodeId)
            while (!queue.isEmpty()) {
                val current = queue.removeFirst()
                if (endNodeIds.contains(current)) return true
                outgoing.getValue(current).forEach { transition ->
                    val next = transition.toNodeId
                    if (next != stopNodeId && visited.add(next)) queue.addLast(next)
                }
            }
            return false
        }

        private fun canReachBeforeStop(
            startNodeId: String,
            targetNodeId: String,
            stopNodeId: String,
            outgoing: Map<String, List<WorkflowTransitionDefinition>>,
        ): Boolean {
            if (startNodeId == targetNodeId) return true
            if (startNodeId == stopNodeId) return false
            val visited = LinkedHashSet<String>()
            val queue = ArrayDeque<String>()
            visited.add(startNodeId)
            queue.addLast(startNodeId)
            while (!queue.isEmpty()) {
                val current = queue.removeFirst()
                outgoing.getValue(current).forEach { transition ->
                    val next = transition.toNodeId
                    if (next == targetNodeId) return true
                    if (next != stopNodeId && visited.add(next)) queue.addLast(next)
                }
            }
            return false
        }
    }
}
