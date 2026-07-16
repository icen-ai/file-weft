package ai.icen.fw.agent.workflow

import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.workflow.web.api.WorkflowWebVersionTag
import ai.icen.fw.workflow.web.api.WorkflowWebWritePreconditions

/**
 * Canonical public-use-case envelope. Tenant and actor are intentionally absent and can only come
 * from the trusted executable Agent chain. The payload is later decoded into existing Workflow
 * application DTOs by the category adapter; it can never select a repository or implementation.
 */
class WorkflowAgentPublicCommand private constructor(
    val useCase: WorkflowAgentUseCaseDescriptor,
    applicationContractVersion: String,
    resourceId: String,
    val expectedResourceVersion: Long,
    idempotencyKey: String,
    executionNonce: String,
    purpose: String,
    payload: ByteArray,
    arguments: ByteArray,
) {
    val applicationContractVersion: String = workflowAgentText(
        applicationContractVersion,
        128,
        "public application contract version",
    )
    val resourceId: String = workflowAgentId(resourceId, "public resource id")
    val idempotencyKey: String = workflowAgentText(idempotencyKey, 128, "public idempotency key")
    val executionNonce: String = workflowAgentId(executionNonce, "public execution nonce")
    val purpose: String = workflowAgentText(purpose, 512, "public purpose")
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
        require(this.applicationContractVersion == WorkflowAgentPublicToolDirectory.APPLICATION_CONTRACT_VERSION) {
            "Workflow Agent public application contract version is unsupported."
        }
        require(expectedResourceVersion >= 0L) { "Workflow Agent expected resource version is invalid." }
        // Agent READ and WRITE calls both carry stronger version/idempotency replay evidence. Only
        // WRITE bindings later expose these values as HTTP-style application preconditions.
        WorkflowWebWritePreconditions.parse(
            this.idempotencyKey,
            WorkflowWebVersionTag.of(expectedResourceVersion).toHeaderValue(),
        )
        resourceRevision = WorkflowAgentDigest("flowweft.agent.workflow.public-resource-revision.v1")
            .add(useCase.descriptorDigest)
            .add(this.applicationContractVersion)
            .add(this.resourceId)
            .add(expectedResourceVersion)
            .add(payloadDigest)
            .finish()
        commandDigest = WorkflowAgentDigest("flowweft.agent.workflow.public-command.v1")
            .add(resourceRevision)
            .add(this.idempotencyKey)
            .add(this.executionNonce)
            .add(this.purpose)
            .add(argumentsDigest)
            .finish()
    }

    override fun toString(): String =
        "WorkflowAgentPublicCommand(operationId=${useCase.operationId}, <redacted>)"

    companion object {
        private val EXPECTED_FIELDS = setOf(
            "applicationContractVersion",
            "executionNonce",
            "expectedResourceVersion",
            "idempotencyKey",
            "operationId",
            "payload",
            "purpose",
            "resourceId",
            "resourceType",
        )

        @JvmStatic
        fun decode(
            directory: WorkflowAgentPublicToolDirectory,
            toolId: ToolId,
            arguments: ByteArray,
        ): WorkflowAgentPublicCommand {
            val useCase = directory.entry(toolId)
                ?: throw IllegalArgumentException("Workflow Agent public use case is unsupported.")
            val root = WorkflowAgentCanonicalJson.parseCanonicalObject(arguments)
            root.requireExactKeys(EXPECTED_FIELDS)
            require(root.string("applicationContractVersion") ==
                WorkflowAgentPublicToolDirectory.APPLICATION_CONTRACT_VERSION &&
                root.string("operationId") == useCase.operationId &&
                root.string("resourceType") == useCase.resourceType
            ) { "Workflow Agent public command does not match its directory descriptor." }
            return WorkflowAgentPublicCommand(
                useCase,
                root.string("applicationContractVersion"),
                root.string("resourceId"),
                root.long("expectedResourceVersion"),
                root.string("idempotencyKey"),
                root.string("executionNonce"),
                root.string("purpose"),
                WorkflowAgentCanonicalJson.encode(root.objectValue("payload")),
                arguments,
            )
        }
    }
}

/** Authorization target derived only from the canonical envelope, never from model prose. */
class WorkflowAgentPublicAuthorizationTarget private constructor(
    val command: WorkflowAgentPublicCommand,
) {
    val action: String = command.useCase.action
    val resourceType: String = command.useCase.resourceType
    val resourceId: String = command.resourceId
    val resourceRevision: String = command.resourceRevision
    val purpose: String = command.purpose
    val idempotencyKey: String = command.idempotencyKey
    val confirmationRequired: Boolean = command.useCase.confirmationRequired

    override fun toString(): String =
        "WorkflowAgentPublicAuthorizationTarget(action=$action, <redacted>)"

    companion object {
        @JvmStatic
        fun decode(
            directory: WorkflowAgentPublicToolDirectory,
            toolId: ToolId,
            arguments: ByteArray,
        ): WorkflowAgentPublicAuthorizationTarget = WorkflowAgentPublicAuthorizationTarget(
            WorkflowAgentPublicCommand.decode(directory, toolId, arguments),
        )
    }
}
