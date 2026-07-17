package ai.icen.fw.workflow.domain

/**
 * Bounded deterministic identifiers supplied by the runtime for one command.
 * The domain never reads UUID generators. Extra ids are ignored; insufficient ids reject the
 * command atomically without returning a partially mutated state.
 */
class WorkflowExecutionIds private constructor(
    tokenIds: Collection<String>,
    nodeExecutionIds: Collection<String>,
    workItemIds: Collection<String>,
    effectIds: Collection<String>,
    eventIds: Collection<String>,
    parallelScopeIds: Collection<String>,
) {
    val tokenIds: List<String> = validated(tokenIds, "token")
    val nodeExecutionIds: List<String> = validated(nodeExecutionIds, "node execution")
    val workItemIds: List<String> = validated(workItemIds, "work item")
    val effectIds: List<String> = validated(effectIds, "effect")
    val eventIds: List<String> = validated(eventIds, "event")
    val parallelScopeIds: List<String> = validated(parallelScopeIds, "parallel scope")
    val contentDigest: String

    init {
        val all = this.tokenIds +
            this.nodeExecutionIds +
            this.workItemIds +
            this.effectIds +
            this.eventIds +
            this.parallelScopeIds
        require(all.toSet().size == all.size) { "Workflow command identifiers must be globally unique." }
        val writer = WorkflowDomainSupport.digest("flowweft-workflow-domain-execution-ids-v1")
        writeList(writer, this.tokenIds)
        writeList(writer, this.nodeExecutionIds)
        writeList(writer, this.workItemIds)
        writeList(writer, this.effectIds)
        writeList(writer, this.eventIds)
        writeList(writer, this.parallelScopeIds)
        contentDigest = writer.finish()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowExecutionIds &&
            tokenIds == other.tokenIds &&
            nodeExecutionIds == other.nodeExecutionIds &&
            workItemIds == other.workItemIds &&
            effectIds == other.effectIds &&
            eventIds == other.eventIds &&
            parallelScopeIds == other.parallelScopeIds

    override fun hashCode(): Int {
        var result = tokenIds.hashCode()
        result = 31 * result + nodeExecutionIds.hashCode()
        result = 31 * result + workItemIds.hashCode()
        result = 31 * result + effectIds.hashCode()
        result = 31 * result + eventIds.hashCode()
        result = 31 * result + parallelScopeIds.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowExecutionIds(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tokenIds: Collection<String>,
            nodeExecutionIds: Collection<String>,
            workItemIds: Collection<String>,
            effectIds: Collection<String>,
            eventIds: Collection<String>,
            parallelScopeIds: Collection<String>,
        ): WorkflowExecutionIds = WorkflowExecutionIds(
            tokenIds,
            nodeExecutionIds,
            workItemIds,
            effectIds,
            eventIds,
            parallelScopeIds,
        )

        @JvmStatic
        fun empty(): WorkflowExecutionIds = WorkflowExecutionIds(
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )

        private fun validated(values: Collection<String>, label: String): List<String> {
            val copy = WorkflowDomainSupport.immutableList(
                values,
                WorkflowDomainSupport.MAX_COMMAND_IDS,
                "Workflow $label identifiers are invalid or exceed the limit.",
            ).map { value ->
                WorkflowDomainSupport.requireText(
                    value,
                    WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
                    "Workflow $label identifier is invalid.",
                )
            }
            require(copy.toSet().size == copy.size) { "Workflow $label identifiers must be unique." }
            return java.util.Collections.unmodifiableList(ArrayList(copy))
        }

        private fun writeList(writer: WorkflowDomainSupport.DigestWriter, values: List<String>) {
            writer.integer(values.size)
            values.forEach(writer::text)
        }
    }
}
