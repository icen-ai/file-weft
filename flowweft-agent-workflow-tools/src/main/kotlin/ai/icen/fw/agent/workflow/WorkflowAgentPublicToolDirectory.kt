package ai.icen.fw.agent.workflow

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentToolCatalog
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.AgentToolDescriptorProvider
import ai.icen.fw.agent.api.AgentToolRisk
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.workflow.web.api.WorkflowWebRoute
import java.nio.charset.StandardCharsets
import java.util.Collections

enum class WorkflowAgentUseCaseCategory {
    DEFINITION,
    INSTANCE,
    TASK,
    COLLABORATION,
    OPERATIONS,
}

enum class WorkflowAgentUseCaseAccess {
    READ,
    WRITE,
}

/**
 * Agent projection of one existing public Workflow application use case. [route] remains the source
 * of truth for operation/capability identity; discovery never grants authority to invoke it.
 */
class WorkflowAgentUseCaseDescriptor internal constructor(
    val route: WorkflowWebRoute,
    val category: WorkflowAgentUseCaseCategory,
    val access: WorkflowAgentUseCaseAccess,
    resourceType: String,
    val risk: AgentToolRisk,
    val confirmationRequired: Boolean,
    val toolDescriptor: AgentToolDescriptor,
) {
    val operationId: String = workflowAgentCode(route.operationId, "public use-case operation")
    val action: String = workflowAgentCode(route.capabilityId, "public use-case action")
    val resourceType: String = workflowAgentCode(resourceType, "public use-case resource type")
    val toolId: ToolId = toolDescriptor.toolId
    val descriptorDigest: String

    init {
        require((access == WorkflowAgentUseCaseAccess.WRITE) == route.idempotencyRequired &&
            route.idempotencyRequired == route.ifMatchRequired
        ) { "Workflow Agent public use-case access does not match its public route." }
        require(toolDescriptor.providerId == WorkflowAgentPublicToolDirectory.PROVIDER_ID &&
            toolDescriptor.risk == risk
        ) { "Workflow Agent public use-case tool descriptor does not match its classification." }
        require(!confirmationRequired || risk == AgentToolRisk.IRREVERSIBLE_OR_EXTERNAL_SIDE_EFFECT) {
            "Workflow Agent confirmation is only valid for a high-risk use case."
        }
        descriptorDigest = WorkflowAgentDigest("flowweft.agent.workflow.public-use-case.v1")
            .add(WorkflowAgentPublicToolDirectory.DIRECTORY_VERSION)
            .add(WorkflowAgentPublicToolDirectory.APPLICATION_CONTRACT_VERSION)
            .add(route.method)
            .add(route.pathTemplate)
            .add(operationId)
            .add(action)
            .add(category.name)
            .add(access.name)
            .add(this.resourceType)
            .add(risk.name)
            .add(confirmationRequired)
            .add(toolDescriptor.descriptorDigest)
            .finish()
    }

    override fun toString(): String =
        "WorkflowAgentUseCaseDescriptor(operationId=$operationId, category=$category, access=$access)"
}

/**
 * Versioned deterministic directory derived from [WorkflowWebRoute.all]. It exposes all public
 * Workflow application use cases even in workflow-only deployments and performs no authorization.
 */
class WorkflowAgentPublicToolDirectory : AgentToolDescriptorProvider {
    val entries: List<WorkflowAgentUseCaseDescriptor> = Collections.unmodifiableList(
        WorkflowWebRoute.all().map(::entry),
    )
    private val byToolId: Map<ToolId, WorkflowAgentUseCaseDescriptor> = entries.associateBy { item -> item.toolId }
    private val byOperationId: Map<String, WorkflowAgentUseCaseDescriptor> = entries.associateBy { item -> item.operationId }
    private val catalog = AgentToolCatalog(PROVIDER_ID, entries.map { item -> item.toolDescriptor })
    val directoryDigest: String

    init {
        require(entries.isNotEmpty() && byToolId.size == entries.size && byOperationId.size == entries.size) {
            "Workflow Agent public use-case directory is empty or ambiguous."
        }
        val digest = WorkflowAgentDigest("flowweft.agent.workflow.public-directory.v1")
            .add(DIRECTORY_VERSION)
            .add(APPLICATION_CONTRACT_VERSION)
            .add(PROVIDER_ID.value)
            .add(CAPABILITY_ID.value)
            .add(entries.size)
        entries.forEach { item -> digest.add(item.descriptorDigest) }
        directoryDigest = digest.finish()
    }

    override fun providerId(): ProviderId = PROVIDER_ID

    /** Full discovery is metadata only; a registry snapshot determines which use cases are installed. */
    override fun descriptors(context: AgentRunContext): AgentToolCatalog = catalog

    fun entry(toolId: ToolId): WorkflowAgentUseCaseDescriptor? = byToolId[toolId]

    fun entry(operationId: String): WorkflowAgentUseCaseDescriptor? = byOperationId[
        workflowAgentCode(operationId, "public use-case operation"),
    ]

    companion object {
        const val DIRECTORY_VERSION: String = "1.0"
        const val APPLICATION_CONTRACT_VERSION: String = "flowweft.workflow.web.application.v1"

        @JvmField
        val PROVIDER_ID: ProviderId = ProviderId("flowweft.workflow.public")

        @JvmField
        val CAPABILITY_ID: AgentCapabilityId = AgentCapabilityId("flowweft.workflow.public-tools.v1")

        private val HIGH_RISK_OPERATIONS = setOf(
            "publishWorkflowDefinition",
            "retireWorkflowDefinition",
            "startWorkflowInstance",
            "resumeWorkflowInstance",
            "cancelWorkflowInstance",
            "terminateWorkflowInstance",
            "decideWorkflowTask",
            "delegateWorkflowTask",
            "addWorkflowTaskSigner",
            "returnWorkflowTask",
            "retryWorkflowIncident",
            "skipWorkflowIncident",
            "repairWorkflowIncident",
            "executeWorkflowMigration",
        )

        private fun entry(route: WorkflowWebRoute): WorkflowAgentUseCaseDescriptor {
            val category = category(route)
            val access = if (route.idempotencyRequired) WorkflowAgentUseCaseAccess.WRITE else WorkflowAgentUseCaseAccess.READ
            val confirmation = route.operationId in HIGH_RISK_OPERATIONS
            val risk = when {
                access == WorkflowAgentUseCaseAccess.READ -> AgentToolRisk.READ_ONLY
                confirmation -> AgentToolRisk.IRREVERSIBLE_OR_EXTERNAL_SIDE_EFFECT
                else -> AgentToolRisk.REVERSIBLE_WRITE
            }
            val schema = schema(route, resourceType(route))
            val descriptor = AgentToolDescriptor(
                PROVIDER_ID,
                ToolId("workflow.public.${route.operationId}"),
                route.operationId,
                "Invokes one public Workflow application use case with current-principal authorization.",
                risk,
                schema,
                workflowAgentSha256(schema),
                setOf(CAPABILITY_ID, AgentCapabilityId(route.capabilityId)),
                true,
                WORKFLOW_AGENT_MAX_RESULT_BYTES,
                0L,
                60_000L,
            )
            return WorkflowAgentUseCaseDescriptor(
                route,
                category,
                access,
                resourceType(route),
                risk,
                confirmation,
                descriptor,
            )
        }

        private fun category(route: WorkflowWebRoute): WorkflowAgentUseCaseCategory = when {
            route.capabilityId.startsWith("workflow.definition.") -> WorkflowAgentUseCaseCategory.DEFINITION
            route.capabilityId.startsWith("workflow.instance.") ||
                route.capabilityId.startsWith("workflow.history.") -> WorkflowAgentUseCaseCategory.INSTANCE
            route.capabilityId.startsWith("workflow.task.") ||
                route.capabilityId.startsWith("workflow.form.") -> WorkflowAgentUseCaseCategory.TASK
            route.capabilityId.startsWith("workflow.comment.") -> WorkflowAgentUseCaseCategory.COLLABORATION
            else -> WorkflowAgentUseCaseCategory.OPERATIONS
        }

        private fun resourceType(route: WorkflowWebRoute): String = when {
            route.capabilityId.startsWith("workflow.definition.") -> "workflow-definition"
            route.capabilityId.startsWith("workflow.instance.") ||
                route.capabilityId.startsWith("workflow.history.") ||
                route.capabilityId.startsWith("workflow.comment.") -> "workflow-instance"
            route.capabilityId.startsWith("workflow.task.") ||
                route.capabilityId.startsWith("workflow.form.") -> "workflow-human-task"
            route.capabilityId.startsWith("workflow.incident.") -> "workflow-incident"
            route.capabilityId.startsWith("workflow.migration.") -> "workflow-migration"
            else -> "workflow-system"
        }

        private fun schema(route: WorkflowWebRoute, resourceType: String): ByteArray = (
            "{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\"," +
                "\"additionalProperties\":false," +
                "\"properties\":{" +
                "\"applicationContractVersion\":{\"const\":\"$APPLICATION_CONTRACT_VERSION\",\"type\":\"string\"}," +
                "\"executionNonce\":{\"maxLength\":256,\"minLength\":1,\"type\":\"string\"}," +
                "\"expectedResourceVersion\":{\"minimum\":0,\"type\":\"integer\"}," +
                "\"idempotencyKey\":{\"maxLength\":128,\"minLength\":1,\"type\":\"string\"}," +
                "\"operationId\":{\"const\":\"${route.operationId}\",\"type\":\"string\"}," +
                "\"payload\":{\"type\":\"object\"}," +
                "\"purpose\":{\"maxLength\":512,\"minLength\":1,\"type\":\"string\"}," +
                "\"resourceId\":{\"maxLength\":512,\"minLength\":1,\"type\":\"string\"}," +
                "\"resourceType\":{\"const\":\"$resourceType\",\"type\":\"string\"}" +
                "},\"required\":[\"applicationContractVersion\",\"executionNonce\"," +
                "\"expectedResourceVersion\",\"idempotencyKey\",\"operationId\",\"payload\"," +
                "\"purpose\",\"resourceId\",\"resourceType\"],\"type\":\"object\"}"
            ).toByteArray(StandardCharsets.UTF_8)
    }
}
