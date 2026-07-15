package ai.icen.fw.workflow.api

/**
 * Immutable neutral workflow node with a stable [nodeId] and bounded human-readable metadata.
 *
 * Structural built-ins bind versioned empty descriptor and payload domains. Provider-backed and
 * [extension] nodes require caller-supplied descriptor and payload digests so two semantics cannot
 * collapse to the same [contentDigest]. The digests do not carry payload and are not execution permits.
 * Unknown kinds and [WorkflowNodeKind.EXTENSION] must fail closed unless a trusted runtime provider
 * accepts the exact kind and both digests.
 */
class WorkflowNodeDefinition private constructor(
    nodeId: String,
    val kind: WorkflowNodeKind,
    title: String,
    description: String?,
    val humanTaskPolicy: WorkflowHumanTaskPolicy?,
    parallelPairNodeId: String?,
    descriptorDigest: String,
    payloadDigest: String,
) {
    val nodeId: String = WorkflowContractSupport.requireMachineCode(
        nodeId,
        "Workflow node identifier is invalid.",
    )
    val title: String = WorkflowContractSupport.requireText(
        title,
        WorkflowContractSupport.MAX_TITLE_UTF8_BYTES,
        "Workflow node title is invalid or exceeds the limit.",
    )
    val description: String? = description?.let { value ->
        WorkflowContractSupport.requireMultilineText(
            value,
            WorkflowContractSupport.MAX_DESCRIPTION_UTF8_BYTES,
            "Workflow node description is invalid or exceeds the limit.",
        )
    }
    val parallelPairNodeId: String? = parallelPairNodeId?.let { value ->
        WorkflowContractSupport.requireMachineCode(value, "Workflow parallel pair node identifier is invalid.")
    }
    val descriptorDigest: String = WorkflowContractSupport.requireCanonicalSha256(
        descriptorDigest,
        "Workflow node descriptor digest must be a canonical lower-case SHA-256 digest.",
    )
    val payloadDigest: String = WorkflowContractSupport.requireCanonicalSha256(
        payloadDigest,
        "Workflow node payload digest must be a canonical lower-case SHA-256 digest.",
    )
    val contentDigest: String

    init {
        when (kind) {
            WorkflowNodeKind.HUMAN_TASK -> require(
                humanTaskPolicy != null && this.parallelPairNodeId == null &&
                    this.descriptorDigest == WorkflowContractSupport.EMPTY_NODE_DESCRIPTOR_DIGEST &&
                    this.payloadDigest == WorkflowContractSupport.EMPTY_NODE_PAYLOAD_DIGEST,
            ) { "Workflow human-task nodes require only a typed human-task policy." }

            WorkflowNodeKind.PARALLEL_SPLIT,
            WorkflowNodeKind.PARALLEL_JOIN -> require(
                humanTaskPolicy == null && this.parallelPairNodeId != null &&
                    this.parallelPairNodeId != this.nodeId &&
                    this.descriptorDigest == WorkflowContractSupport.EMPTY_NODE_DESCRIPTOR_DIGEST &&
                    this.payloadDigest == WorkflowContractSupport.EMPTY_NODE_PAYLOAD_DIGEST,
            ) { "Workflow parallel nodes require one distinct paired node identifier." }

            WorkflowNodeKind.EXTENSION -> require(
                humanTaskPolicy == null && this.parallelPairNodeId == null,
            ) { "Workflow extension nodes accept only descriptor and payload digest bindings." }

            WorkflowNodeKind.SERVICE_TASK,
            WorkflowNodeKind.DECISION,
            WorkflowNodeKind.TIMER_WAIT,
            WorkflowNodeKind.SUBPROCESS -> require(
                humanTaskPolicy == null && this.parallelPairNodeId == null,
            ) { "Provider-backed workflow nodes accept only descriptor and payload digest bindings." }

            WorkflowNodeKind.START,
            WorkflowNodeKind.END,
            WorkflowNodeKind.EXCLUSIVE_GATEWAY -> require(
                humanTaskPolicy == null && this.parallelPairNodeId == null &&
                    this.descriptorDigest == WorkflowContractSupport.EMPTY_NODE_DESCRIPTOR_DIGEST &&
                    this.payloadDigest == WorkflowContractSupport.EMPTY_NODE_PAYLOAD_DIGEST,
            ) { "Structural workflow nodes cannot carry provider payload bindings." }

            else -> require(humanTaskPolicy == null && this.parallelPairNodeId == null) {
                "Custom workflow nodes accept only descriptor and payload digest bindings."
            }
        }

        val writer = WorkflowContractSupport.digest(WorkflowContractSupport.NODE_DIGEST_DOMAIN)
            .text(this.nodeId)
            .text(kind.code)
            .text(this.title)
            .optionalText(this.description)
            .text(this.descriptorDigest)
            .text(this.payloadDigest)
            .optionalText(humanTaskPolicy?.contentDigest)
            .optionalText(this.parallelPairNodeId)
        contentDigest = writer.finish()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowNodeDefinition &&
            nodeId == other.nodeId &&
            kind == other.kind &&
            title == other.title &&
            description == other.description &&
            humanTaskPolicy == other.humanTaskPolicy &&
            parallelPairNodeId == other.parallelPairNodeId &&
            descriptorDigest == other.descriptorDigest &&
            payloadDigest == other.payloadDigest

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + (humanTaskPolicy?.hashCode() ?: 0)
        result = 31 * result + (parallelPairNodeId?.hashCode() ?: 0)
        result = 31 * result + descriptorDigest.hashCode()
        result = 31 * result + payloadDigest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowNodeDefinition(<redacted>)"

    companion object {
        /** Creates START, END or EXCLUSIVE_GATEWAY, which have no payload in schema v1. */
        @JvmStatic
        fun of(
            nodeId: String,
            kind: WorkflowNodeKind,
            title: String,
            description: String?,
        ): WorkflowNodeDefinition {
            require(isStructuralBuiltIn(kind)) {
                "This workflow node kind requires its dedicated typed factory."
            }
            return builtIn(nodeId, kind, title, description, null, null)
        }

        @JvmStatic
        fun humanTask(
            nodeId: String,
            title: String,
            description: String?,
            policy: WorkflowHumanTaskPolicy,
        ): WorkflowNodeDefinition = builtIn(
            nodeId,
            WorkflowNodeKind.HUMAN_TASK,
            title,
            description,
            policy,
            null,
        )

        /** Binds a service descriptor and payload supplied by the trusted publish envelope. */
        @JvmStatic
        fun serviceTask(
            nodeId: String,
            title: String,
            description: String?,
            descriptorDigest: String,
            payloadDigest: String,
        ): WorkflowNodeDefinition = providerBacked(
            nodeId,
            WorkflowNodeKind.SERVICE_TASK,
            title,
            description,
            descriptorDigest,
            payloadDigest,
        )

        /** Binds a decision descriptor and payload supplied by the trusted publish envelope. */
        @JvmStatic
        fun decision(
            nodeId: String,
            title: String,
            description: String?,
            descriptorDigest: String,
            payloadDigest: String,
        ): WorkflowNodeDefinition = providerBacked(
            nodeId,
            WorkflowNodeKind.DECISION,
            title,
            description,
            descriptorDigest,
            payloadDigest,
        )

        /** Binds a durable timer descriptor and payload supplied by the trusted publish envelope. */
        @JvmStatic
        fun timerWait(
            nodeId: String,
            title: String,
            description: String?,
            descriptorDigest: String,
            payloadDigest: String,
        ): WorkflowNodeDefinition = providerBacked(
            nodeId,
            WorkflowNodeKind.TIMER_WAIT,
            title,
            description,
            descriptorDigest,
            payloadDigest,
        )

        /** Binds an exact subprocess descriptor and payload supplied by the trusted publish envelope. */
        @JvmStatic
        fun subprocess(
            nodeId: String,
            title: String,
            description: String?,
            descriptorDigest: String,
            payloadDigest: String,
        ): WorkflowNodeDefinition = providerBacked(
            nodeId,
            WorkflowNodeKind.SUBPROCESS,
            title,
            description,
            descriptorDigest,
            payloadDigest,
        )

        @JvmStatic
        fun parallelSplit(
            nodeId: String,
            pairedJoinNodeId: String,
            title: String,
            description: String?,
        ): WorkflowNodeDefinition = builtIn(
            nodeId,
            WorkflowNodeKind.PARALLEL_SPLIT,
            title,
            description,
            null,
            pairedJoinNodeId,
        )

        @JvmStatic
        fun parallelJoin(
            nodeId: String,
            pairedSplitNodeId: String,
            title: String,
            description: String?,
        ): WorkflowNodeDefinition = builtIn(
            nodeId,
            WorkflowNodeKind.PARALLEL_JOIN,
            title,
            description,
            null,
            pairedSplitNodeId,
        )

        /**
         * Represents provider-owned semantics without embedding executable configuration.
         * The descriptor and payload are resolved outside this API by their exact digests.
         */
        @JvmStatic
        fun extension(
            nodeId: String,
            kind: WorkflowNodeKind,
            title: String,
            description: String?,
            descriptorDigest: String,
            payloadDigest: String,
        ): WorkflowNodeDefinition {
            require(kind == WorkflowNodeKind.EXTENSION || !isBuiltIn(kind)) {
                "Built-in workflow node kinds require their typed factory."
            }
            return WorkflowNodeDefinition(
                nodeId,
                kind,
                title,
                description,
                null,
                null,
                descriptorDigest,
                payloadDigest,
            )
        }

        private fun builtIn(
            nodeId: String,
            kind: WorkflowNodeKind,
            title: String,
            description: String?,
            policy: WorkflowHumanTaskPolicy?,
            pairedNodeId: String?,
        ): WorkflowNodeDefinition = WorkflowNodeDefinition(
            nodeId,
            kind,
            title,
            description,
            policy,
            pairedNodeId,
            WorkflowContractSupport.EMPTY_NODE_DESCRIPTOR_DIGEST,
            WorkflowContractSupport.EMPTY_NODE_PAYLOAD_DIGEST,
        )

        private fun providerBacked(
            nodeId: String,
            kind: WorkflowNodeKind,
            title: String,
            description: String?,
            descriptorDigest: String,
            payloadDigest: String,
        ): WorkflowNodeDefinition = WorkflowNodeDefinition(
            nodeId,
            kind,
            title,
            description,
            null,
            null,
            descriptorDigest,
            payloadDigest,
        )

        private fun isStructuralBuiltIn(kind: WorkflowNodeKind): Boolean =
            kind == WorkflowNodeKind.START ||
                kind == WorkflowNodeKind.END ||
                kind == WorkflowNodeKind.EXCLUSIVE_GATEWAY

        private fun isBuiltIn(kind: WorkflowNodeKind): Boolean =
            isStructuralBuiltIn(kind) ||
                kind == WorkflowNodeKind.HUMAN_TASK ||
                kind == WorkflowNodeKind.SERVICE_TASK ||
                kind == WorkflowNodeKind.DECISION ||
                kind == WorkflowNodeKind.PARALLEL_SPLIT ||
                kind == WorkflowNodeKind.PARALLEL_JOIN ||
                kind == WorkflowNodeKind.TIMER_WAIT ||
                kind == WorkflowNodeKind.SUBPROCESS ||
                kind == WorkflowNodeKind.EXTENSION
    }
}
