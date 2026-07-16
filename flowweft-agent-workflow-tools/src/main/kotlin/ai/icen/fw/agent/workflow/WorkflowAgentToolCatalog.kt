package ai.icen.fw.agent.workflow

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentToolCatalog
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.AgentToolDescriptorProvider
import ai.icen.fw.agent.api.AgentToolRisk
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
import java.nio.charset.StandardCharsets
import java.util.Collections

/** Fixed operation classification; discovery never implies that the current principal is authorized. */
class WorkflowAgentOperation private constructor(
    val toolId: ToolId,
    action: String,
    resourceType: String,
    val risk: AgentToolRisk,
    val confirmationRequired: Boolean,
    internal val category: WorkflowAgentOperationCategory,
) {
    val action: String = workflowAgentCode(action, "authorization action")
    val resourceType: String = workflowAgentCode(resourceType, "resource type")

    override fun equals(other: Any?): Boolean = other is WorkflowAgentOperation && toolId == other.toolId

    override fun hashCode(): Int = toolId.hashCode()

    override fun toString(): String = "WorkflowAgentOperation(toolId=${toolId.value})"

    companion object {
        @JvmField
        val SAVE_DEFINITION_DRAFT = operation(
            "workflow.definition.save-draft",
            "workflow.definition.save-draft",
            "workflow-definition",
            AgentToolRisk.REVERSIBLE_WRITE,
            false,
            WorkflowAgentOperationCategory.DEFINITION,
        )

        @JvmField
        val PUBLISH_DEFINITION = operation(
            "workflow.definition.publish",
            "workflow.definition.publish",
            "workflow-definition",
            AgentToolRisk.IRREVERSIBLE_OR_EXTERNAL_SIDE_EFFECT,
            true,
            WorkflowAgentOperationCategory.DEFINITION,
        )

        @JvmField
        val RETIRE_DEFINITION = operation(
            "workflow.definition.retire",
            "workflow.definition.retire",
            "workflow-definition",
            AgentToolRisk.IRREVERSIBLE_OR_EXTERNAL_SIDE_EFFECT,
            true,
            WorkflowAgentOperationCategory.DEFINITION,
        )

        @JvmField
        val START_INSTANCE = operation(
            "workflow.instance.start",
            "workflow.instance.start",
            "workflow-instance",
            AgentToolRisk.IRREVERSIBLE_OR_EXTERNAL_SIDE_EFFECT,
            true,
            WorkflowAgentOperationCategory.INSTANCE,
        )

        @JvmField
        val SUSPEND_INSTANCE = operation(
            "workflow.instance.suspend",
            "workflow.instance.suspend",
            "workflow-instance",
            AgentToolRisk.REVERSIBLE_WRITE,
            false,
            WorkflowAgentOperationCategory.INSTANCE,
        )

        @JvmField
        val RESUME_INSTANCE = operation(
            "workflow.instance.resume",
            "workflow.instance.resume",
            "workflow-instance",
            AgentToolRisk.IRREVERSIBLE_OR_EXTERNAL_SIDE_EFFECT,
            true,
            WorkflowAgentOperationCategory.INSTANCE,
        )

        @JvmField
        val CANCEL_INSTANCE = operation(
            "workflow.instance.cancel",
            "workflow.instance.cancel",
            "workflow-instance",
            AgentToolRisk.IRREVERSIBLE_OR_EXTERNAL_SIDE_EFFECT,
            true,
            WorkflowAgentOperationCategory.INSTANCE,
        )

        @JvmField
        val TERMINATE_INSTANCE = operation(
            "workflow.instance.terminate",
            "workflow.instance.terminate",
            "workflow-instance",
            AgentToolRisk.IRREVERSIBLE_OR_EXTERNAL_SIDE_EFFECT,
            true,
            WorkflowAgentOperationCategory.INSTANCE,
        )

        @JvmField
        val APPROVE_HUMAN_TASK = humanTask("approve", true)

        @JvmField
        val REJECT_HUMAN_TASK = humanTask("reject", true)

        @JvmField
        val CLAIM_HUMAN_TASK = humanTask("claim", false)

        @JvmField
        val UNCLAIM_HUMAN_TASK = humanTask("unclaim", false)

        @JvmField
        val DELEGATE_HUMAN_TASK = humanTask("delegate", true)

        @JvmField
        val TRANSFER_HUMAN_TASK = humanTask("transfer", true)

        @JvmField
        val ADD_SIGN_HUMAN_TASK = humanTask("add-sign", true)

        @JvmField
        val RETURN_HUMAN_TASK = humanTask("return", true)

        @JvmField
        val REPAIR_INCIDENT = operation(
            "workflow.incident.repair",
            "workflow.incident.repair",
            "workflow-incident",
            AgentToolRisk.IRREVERSIBLE_OR_EXTERNAL_SIDE_EFFECT,
            true,
            WorkflowAgentOperationCategory.INCIDENT,
        )

        @JvmField
        val BUILT_INS: List<WorkflowAgentOperation> = Collections.unmodifiableList(
            listOf(
                SAVE_DEFINITION_DRAFT,
                PUBLISH_DEFINITION,
                RETIRE_DEFINITION,
                START_INSTANCE,
                SUSPEND_INSTANCE,
                RESUME_INSTANCE,
                CANCEL_INSTANCE,
                TERMINATE_INSTANCE,
                APPROVE_HUMAN_TASK,
                REJECT_HUMAN_TASK,
                CLAIM_HUMAN_TASK,
                UNCLAIM_HUMAN_TASK,
                DELEGATE_HUMAN_TASK,
                TRANSFER_HUMAN_TASK,
                ADD_SIGN_HUMAN_TASK,
                RETURN_HUMAN_TASK,
                REPAIR_INCIDENT,
            ),
        )

        @JvmStatic
        fun find(toolId: ToolId): WorkflowAgentOperation? = BUILT_INS.firstOrNull { it.toolId == toolId }

        private fun humanTask(action: String, confirmationRequired: Boolean): WorkflowAgentOperation = operation(
            "workflow.task.$action",
            "workflow.task.$action",
            "workflow-human-task",
            if (confirmationRequired) AgentToolRisk.IRREVERSIBLE_OR_EXTERNAL_SIDE_EFFECT
            else AgentToolRisk.REVERSIBLE_WRITE,
            confirmationRequired,
            WorkflowAgentOperationCategory.HUMAN_TASK,
        )

        private fun operation(
            toolId: String,
            action: String,
            resourceType: String,
            risk: AgentToolRisk,
            confirmationRequired: Boolean,
            category: WorkflowAgentOperationCategory,
        ): WorkflowAgentOperation = WorkflowAgentOperation(
            ToolId(toolId),
            action,
            resourceType,
            risk,
            confirmationRequired,
            category,
        )
    }
}

internal enum class WorkflowAgentOperationCategory {
    DEFINITION,
    INSTANCE,
    HUMAN_TASK,
    INCIDENT,
}

/** Immutable production catalog with schemas and descriptor digests fixed by this module version. */
class WorkflowAgentToolCatalog : AgentToolDescriptorProvider {
    private val descriptors: List<AgentToolDescriptor> = Collections.unmodifiableList(
        WorkflowAgentOperation.BUILT_INS.map(::descriptor),
    )
    private val catalog = AgentToolCatalog(PROVIDER_ID, descriptors)

    /** Digest over the ordered capability and exact descriptor digests, suitable for deployment pinning. */
    val catalogDigest: String = WorkflowAgentDigest("flowweft.agent.workflow.catalog.v1")
        .add(CAPABILITY_ID.value)
        .add(descriptors.size)
        .also { digest -> descriptors.forEach { digest.add(it.descriptorDigest) } }
        .finish()

    init {
        require(catalogDigest == CATALOG_DIGEST_V1) {
            "Workflow Agent tool catalog drifted without a protocol version change."
        }
    }

    override fun providerId(): ProviderId = PROVIDER_ID

    override fun descriptors(context: AgentRunContext): AgentToolCatalog = catalog

    fun descriptor(toolId: ToolId): AgentToolDescriptor? = catalog.find(toolId)

    fun operation(toolId: ToolId): WorkflowAgentOperation? = WorkflowAgentOperation.find(toolId)

    companion object {
        @JvmField
        val PROVIDER_ID: ProviderId = ProviderId("flowweft.workflow.local")

        @JvmField
        val CAPABILITY_ID: AgentCapabilityId = AgentCapabilityId("flowweft.workflow.tools.v1")

        const val CATALOG_DIGEST_V1: String =
            "9538a458b9f99b3c1a055f0c52770b78a2ebb8d6c6d6b334757585bbcd249bb5"

        private fun descriptor(operation: WorkflowAgentOperation): AgentToolDescriptor {
            val schema = schema(operation)
            return AgentToolDescriptor(
                PROVIDER_ID,
                operation.toolId,
                displayName(operation),
                "Executes one exact Workflow application use case with current-principal authorization.",
                operation.risk,
                schema,
                workflowAgentSha256(schema),
                setOf(CAPABILITY_ID),
                true,
                WORKFLOW_AGENT_MAX_RESULT_BYTES,
                0L,
                60_000L,
            )
        }

        private fun displayName(operation: WorkflowAgentOperation): String = operation.toolId.value
            .split('.', '-')
            .joinToString(" ") { part -> part.replaceFirstChar { character -> character.uppercase() } }

        private fun schema(operation: WorkflowAgentOperation): ByteArray = (
            "{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\"," +
                "\"additionalProperties\":false," +
                "\"properties\":{" +
                "\"definitionDigest\":{\"pattern\":\"^[0-9a-f]{64}\$\",\"type\":\"string\"}," +
                "\"definitionId\":{\"maxLength\":256,\"minLength\":1,\"type\":\"string\"}," +
                "\"definitionVersion\":{\"maxLength\":128,\"minLength\":1,\"type\":\"string\"}," +
                "\"executionNonce\":{\"maxLength\":256,\"minLength\":1,\"type\":\"string\"}," +
                "\"expectedDefinitionStateVersion\":{\"minimum\":0,\"type\":\"integer\"}," +
                "\"expectedIncidentVersion\":{\"minimum\":0,\"type\":\"integer\"}," +
                "\"expectedInstanceVersion\":{\"minimum\":0,\"type\":\"integer\"}," +
                "\"expectedWorkItemVersion\":{\"minimum\":0,\"type\":\"integer\"}," +
                "\"idempotencyKey\":{\"maxLength\":256,\"minLength\":1,\"type\":\"string\"}," +
                "\"incidentId\":{\"maxLength\":256,\"minLength\":1,\"type\":\"string\"}," +
                "\"instanceId\":{\"maxLength\":256,\"minLength\":1,\"type\":\"string\"}," +
                "\"operation\":{\"const\":\"${operation.toolId.value}\",\"type\":\"string\"}," +
                "\"payload\":{\"type\":\"object\"}," +
                "\"purpose\":{\"maxLength\":256,\"minLength\":1,\"type\":\"string\"}," +
                "\"resourceId\":{\"maxLength\":256,\"minLength\":1,\"type\":\"string\"}," +
                "\"resourceType\":{\"const\":\"${operation.resourceType}\",\"type\":\"string\"}," +
                "\"workItemId\":{\"maxLength\":256,\"minLength\":1,\"type\":\"string\"}" +
                "}," +
                "\"required\":[\"definitionDigest\",\"definitionId\",\"definitionVersion\"," +
                "\"executionNonce\",\"expectedDefinitionStateVersion\",\"expectedIncidentVersion\"," +
                "\"expectedInstanceVersion\",\"expectedWorkItemVersion\",\"idempotencyKey\"," +
                "\"incidentId\",\"instanceId\",\"operation\",\"payload\",\"purpose\"," +
                "\"resourceId\",\"resourceType\",\"workItemId\"],\"type\":\"object\"}"
            ).toByteArray(StandardCharsets.UTF_8)
    }
}
