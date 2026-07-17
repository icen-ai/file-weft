package ai.icen.fw.agent.workflow

import ai.icen.fw.agent.api.ToolId

/**
 * Exact, canonical command decoded before policy planning and decoded again before execution.
 * Tenant and principal are intentionally absent: they can only come from the trusted Agent chain.
 */
class WorkflowAgentCommand private constructor(
    val operation: WorkflowAgentOperation,
    resourceId: String,
    definitionId: String,
    definitionVersion: String,
    definitionDigest: String,
    instanceId: String,
    workItemId: String,
    incidentId: String,
    val expectedDefinitionStateVersion: Long,
    val expectedInstanceVersion: Long,
    val expectedWorkItemVersion: Long,
    val expectedIncidentVersion: Long,
    idempotencyKey: String,
    executionNonce: String,
    purpose: String,
    payload: ByteArray,
    arguments: ByteArray,
) {
    val resourceId: String = workflowAgentId(resourceId, "resource id")
    val definitionId: String = workflowAgentId(definitionId, "definition id")
    val definitionVersion: String = workflowAgentText(definitionVersion, 256, "definition version")
    val definitionDigest: String = workflowAgentRequireSha256(definitionDigest, "definition")
    val instanceId: String = workflowAgentId(instanceId, "instance id")
    val workItemId: String = workflowAgentId(workItemId, "work-item id")
    val incidentId: String = workflowAgentId(incidentId, "incident id")
    val idempotencyKey: String = workflowAgentText(idempotencyKey, 512, "idempotency key")
    val executionNonce: String = workflowAgentId(executionNonce, "execution nonce")
    val purpose: String = workflowAgentText(purpose, 512, "purpose")
    private val payloadSnapshot: ByteArray = payload.copyOf()
    private val argumentsSnapshot: ByteArray = arguments.copyOf()
    val payloadDigest: String = workflowAgentSha256(payloadSnapshot)
    val argumentsDigest: String = workflowAgentSha256(argumentsSnapshot)
    val resourceRevision: String
    val commandDigest: String

    val payload: ByteArray
        get() = payloadSnapshot.copyOf()

    val arguments: ByteArray
        get() = argumentsSnapshot.copyOf()

    init {
        require(expectedDefinitionStateVersion >= 0L && expectedInstanceVersion >= 0L &&
            expectedWorkItemVersion >= 0L && expectedIncidentVersion >= 0L
        ) { "Workflow Agent expected versions must not be negative." }
        validateOperationShape()
        resourceRevision = WorkflowAgentDigest("flowweft.agent.workflow.resource-revision.v1")
            .add(operation.toolId.value)
            .add(operation.resourceType)
            .add(this.resourceId)
            .add(this.definitionId)
            .add(this.definitionVersion)
            .add(this.definitionDigest)
            .add(this.instanceId)
            .add(this.workItemId)
            .add(this.incidentId)
            .add(expectedDefinitionStateVersion)
            .add(expectedInstanceVersion)
            .add(expectedWorkItemVersion)
            .add(expectedIncidentVersion)
            .add(payloadDigest)
            .finish()
        commandDigest = WorkflowAgentDigest("flowweft.agent.workflow.command.v1")
            .add(resourceRevision)
            .add(idempotencyKey)
            .add(executionNonce)
            .add(this.purpose)
            .add(argumentsDigest)
            .finish()
    }

    private fun validateOperationShape() {
        when (operation.category) {
            WorkflowAgentOperationCategory.DEFINITION -> {
                require(resourceId == definitionId && instanceId == NONE && workItemId == NONE && incidentId == NONE) {
                    "Workflow Agent definition command target is invalid."
                }
                require(expectedInstanceVersion == 0L && expectedWorkItemVersion == 0L &&
                    expectedIncidentVersion == 0L
                ) { "Workflow Agent definition command carries unrelated versions." }
            }
            WorkflowAgentOperationCategory.INSTANCE -> {
                require(resourceId == instanceId && instanceId != NONE && workItemId == NONE && incidentId == NONE) {
                    "Workflow Agent instance command target is invalid."
                }
                require(expectedDefinitionStateVersion == 0L && expectedWorkItemVersion == 0L &&
                    expectedIncidentVersion == 0L
                ) { "Workflow Agent instance command carries unrelated versions." }
            }
            WorkflowAgentOperationCategory.HUMAN_TASK -> {
                require(resourceId == workItemId && instanceId != NONE && workItemId != NONE && incidentId == NONE) {
                    "Workflow Agent human-task command target is invalid."
                }
                require(expectedDefinitionStateVersion == 0L && expectedIncidentVersion == 0L) {
                    "Workflow Agent human-task command carries unrelated versions."
                }
            }
            WorkflowAgentOperationCategory.INCIDENT -> {
                require(resourceId == incidentId && instanceId != NONE && workItemId == NONE && incidentId != NONE) {
                    "Workflow Agent incident command target is invalid."
                }
                require(expectedDefinitionStateVersion == 0L && expectedWorkItemVersion == 0L) {
                    "Workflow Agent incident command carries unrelated versions."
                }
            }
        }
    }

    override fun toString(): String = "WorkflowAgentCommand(operation=${operation.toolId.value}, <redacted>)"

    companion object {
        const val NONE: String = "-"

        private val EXPECTED_FIELDS = setOf(
            "definitionDigest",
            "definitionId",
            "definitionVersion",
            "executionNonce",
            "expectedDefinitionStateVersion",
            "expectedIncidentVersion",
            "expectedInstanceVersion",
            "expectedWorkItemVersion",
            "idempotencyKey",
            "incidentId",
            "instanceId",
            "operation",
            "payload",
            "purpose",
            "resourceId",
            "resourceType",
            "workItemId",
        )

        @JvmStatic
        fun decode(toolId: ToolId, arguments: ByteArray): WorkflowAgentCommand {
            val operation = WorkflowAgentOperation.find(toolId)
                ?: throw IllegalArgumentException("Workflow Agent tool is unsupported.")
            val root = WorkflowAgentCanonicalJson.parseCanonicalObject(arguments)
            root.requireExactKeys(EXPECTED_FIELDS)
            require(root.string("operation") == operation.toolId.value &&
                root.string("resourceType") == operation.resourceType
            ) { "Workflow Agent command operation does not match its descriptor." }
            val payload = WorkflowAgentCanonicalJson.encode(root.objectValue("payload"))
            return WorkflowAgentCommand(
                operation,
                root.string("resourceId"),
                root.string("definitionId"),
                root.string("definitionVersion"),
                root.string("definitionDigest"),
                root.string("instanceId"),
                root.string("workItemId"),
                root.string("incidentId"),
                root.long("expectedDefinitionStateVersion"),
                root.long("expectedInstanceVersion"),
                root.long("expectedWorkItemVersion"),
                root.long("expectedIncidentVersion"),
                root.string("idempotencyKey"),
                root.string("executionNonce"),
                root.string("purpose"),
                payload,
                arguments,
            )
        }
    }
}

/** Values used by [ai.icen.fw.agent.runtime.AgentToolExecutionPlan] without trusting model scope. */
class WorkflowAgentAuthorizationTarget private constructor(
    val command: WorkflowAgentCommand,
) {
    val action: String = command.operation.action
    val resourceType: String = command.operation.resourceType
    val resourceId: String = command.resourceId
    val resourceRevision: String = command.resourceRevision
    val purpose: String = command.purpose
    val idempotencyKey: String = command.idempotencyKey
    val confirmationRequired: Boolean = command.operation.confirmationRequired

    override fun toString(): String = "WorkflowAgentAuthorizationTarget(action=$action, <redacted>)"

    companion object {
        @JvmStatic
        fun decode(toolId: ToolId, arguments: ByteArray): WorkflowAgentAuthorizationTarget =
            WorkflowAgentAuthorizationTarget(WorkflowAgentCommand.decode(toolId, arguments))
    }
}
