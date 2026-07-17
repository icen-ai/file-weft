package ai.icen.fw.agent.workflow

import ai.icen.fw.agent.api.AgentExecutableToolInvocation
import ai.icen.fw.agent.api.AgentPolicyOutcome
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentToolCatalog
import ai.icen.fw.agent.api.AgentToolDescriptorProvider
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.workflow.web.api.WorkflowCollaborationWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowDefinitionWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowInstanceWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowOperationsWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowTaskWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowWebCapabilityApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowWebResourceId
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContext
import ai.icen.fw.workflow.web.api.WorkflowWebVersionTag
import ai.icen.fw.workflow.web.api.WorkflowWebWritePreconditions
import java.util.Collections

enum class WorkflowAgentPublicUseCaseAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

/** Safe installation projection. It contains no bean name, endpoint, exception or credential. */
class WorkflowAgentPublicUseCaseRegistration internal constructor(
    val useCase: WorkflowAgentUseCaseDescriptor,
    val availability: WorkflowAgentPublicUseCaseAvailability,
    reasonCode: String?,
) {
    val reasonCode: String? = reasonCode?.let { value -> workflowAgentCode(value, "registration reason") }
    val registrationDigest: String

    init {
        require((availability == WorkflowAgentPublicUseCaseAvailability.AVAILABLE) == (this.reasonCode == null)) {
            "Workflow Agent public use-case availability and reason do not agree."
        }
        registrationDigest = WorkflowAgentDigest("flowweft.agent.workflow.public-registration.v1")
            .add(useCase.descriptorDigest)
            .add(availability.name)
            .add(this.reasonCode ?: "-")
            .finish()
    }

    override fun toString(): String =
        "WorkflowAgentPublicUseCaseRegistration(operationId=${useCase.operationId}, availability=$availability)"
}

class WorkflowAgentPublicRegistrySnapshot internal constructor(
    val directoryVersion: String,
    directoryDigest: String,
    registrations: Collection<WorkflowAgentPublicUseCaseRegistration>,
) {
    val directoryDigest: String = workflowAgentRequireSha256(directoryDigest, "public directory")
    val registrations: List<WorkflowAgentPublicUseCaseRegistration> = Collections.unmodifiableList(
        ArrayList(registrations),
    )
    val availableCount: Int = this.registrations.count { item ->
        item.availability == WorkflowAgentPublicUseCaseAvailability.AVAILABLE
    }
    val snapshotDigest: String

    init {
        require(this.registrations.isNotEmpty() &&
            this.registrations.map { item -> item.useCase.toolId }.toSet().size == this.registrations.size
        ) { "Workflow Agent public registry snapshot is empty or ambiguous." }
        val digest = WorkflowAgentDigest("flowweft.agent.workflow.public-registry-snapshot.v1")
            .add(directoryVersion)
            .add(this.directoryDigest)
            .add(this.registrations.size)
        this.registrations.forEach { item -> digest.add(item.registrationDigest) }
        snapshotDigest = digest.finish()
    }

    override fun toString(): String =
        "WorkflowAgentPublicRegistrySnapshot(availableCount=$availableCount, total=${registrations.size})"
}

/**
 * Registers the existing framework-neutral Workflow application ports by category. No repository,
 * persistence handle or raw runtime mutation interface can be registered here.
 */
class WorkflowAgentPublicApplicationPortRegistry @JvmOverloads constructor(
    val directory: WorkflowAgentPublicToolDirectory,
    val definitions: WorkflowDefinitionWebApplicationPort? = null,
    val instances: WorkflowInstanceWebApplicationPort? = null,
    val tasks: WorkflowTaskWebApplicationPort? = null,
    val collaboration: WorkflowCollaborationWebApplicationPort? = null,
    val operations: WorkflowOperationsWebApplicationPort? = null,
    val capabilities: WorkflowWebCapabilityApplicationPort? = null,
) : AgentToolDescriptorProvider {
    private val registrations: List<WorkflowAgentPublicUseCaseRegistration> = Collections.unmodifiableList(
        directory.entries.map { useCase ->
            if (registered(useCase)) {
                WorkflowAgentPublicUseCaseRegistration(
                    useCase,
                    WorkflowAgentPublicUseCaseAvailability.AVAILABLE,
                    null,
                )
            } else {
                WorkflowAgentPublicUseCaseRegistration(
                    useCase,
                    WorkflowAgentPublicUseCaseAvailability.UNAVAILABLE,
                    "APPLICATION_PORT_UNAVAILABLE",
                )
            }
        },
    )
    private val availableEntries: List<WorkflowAgentUseCaseDescriptor> = registrations
        .filter { item -> item.availability == WorkflowAgentPublicUseCaseAvailability.AVAILABLE }
        .map { item -> item.useCase }
    private val catalog = AgentToolCatalog(
        WorkflowAgentPublicToolDirectory.PROVIDER_ID,
        availableEntries.map { item -> item.toolDescriptor },
    )
    val registryDigest: String = WorkflowAgentDigest("flowweft.agent.workflow.public-registry.v1")
        .add(directory.directoryDigest)
        .add(registrations.size)
        .also { digest -> registrations.forEach { item -> digest.add(item.registrationDigest) } }
        .finish()

    override fun providerId(): ProviderId = WorkflowAgentPublicToolDirectory.PROVIDER_ID

    /** Only installed use cases are offered to a model; [snapshot] still diagnoses every absent port. */
    override fun descriptors(context: AgentRunContext): AgentToolCatalog = catalog

    fun snapshot(): WorkflowAgentPublicRegistrySnapshot = WorkflowAgentPublicRegistrySnapshot(
        WorkflowAgentPublicToolDirectory.DIRECTORY_VERSION,
        directory.directoryDigest,
        registrations,
    )

    fun isRegistered(toolId: ToolId): Boolean = availableEntries.any { item -> item.toolId == toolId }

    fun requireRegistered(toolId: ToolId): WorkflowAgentUseCaseDescriptor {
        val useCase = directory.entry(toolId)
            ?: throw IllegalArgumentException("Workflow Agent public use case is unsupported.")
        require(registered(useCase)) { "Workflow Agent public application port is unavailable." }
        return useCase
    }

    fun bind(executable: AgentExecutableToolInvocation, boundAt: Long): WorkflowAgentPublicInvocationBinding {
        requireRegistered(executable.invocation.toolId)
        return WorkflowAgentPublicInvocationBinding.bind(directory, executable, boundAt)
    }

    private fun registered(useCase: WorkflowAgentUseCaseDescriptor): Boolean = when {
        useCase.operationId == "listWorkflowCapabilities" -> capabilities != null
        useCase.category == WorkflowAgentUseCaseCategory.DEFINITION -> definitions != null
        useCase.category == WorkflowAgentUseCaseCategory.INSTANCE -> instances != null
        useCase.category == WorkflowAgentUseCaseCategory.TASK -> tasks != null
        useCase.category == WorkflowAgentUseCaseCategory.COLLABORATION -> collaboration != null
        else -> operations != null
    }

    override fun toString(): String =
        "WorkflowAgentPublicApplicationPortRegistry(available=${availableEntries.size}, total=${registrations.size})"
}

/**
 * Final permission-bound call view handed to a category adapter. The adapter must decode [payload]
 * into the existing Workflow Web API command/query type and use [trustedContext], [resourceId] and
 * [writePreconditions] rather than accepting tenant, actor or target identity from the payload.
 */
class WorkflowAgentPublicInvocationBinding private constructor(
    val useCase: WorkflowAgentUseCaseDescriptor,
    val command: WorkflowAgentPublicCommand,
    val tenantId: Identifier,
    val principalId: Identifier,
    val principalType: String,
    val runId: Identifier,
    val stepId: Identifier,
    val invocationId: Identifier,
    val executionContextId: Identifier,
    val authorizationProviderId: ProviderId,
    authorizationRevision: String,
    val authorizationExpiresAt: Long,
    val maximumCostMicros: Long,
    val maximumDurationMillis: Long,
    val remainingDurationMillis: Long,
    val deadlineAt: Long,
    val boundAt: Long,
    val trustedContext: WorkflowWebTrustedContext,
    val resourceId: WorkflowWebResourceId,
    val writePreconditions: WorkflowWebWritePreconditions?,
    bindingDigest: String,
) {
    val authorizationRevision: String = workflowAgentText(
        authorizationRevision,
        512,
        "public authorization revision",
    )
    val bindingDigest: String = workflowAgentRequireSha256(bindingDigest, "public invocation binding")

    val payload: ByteArray
        get() = command.payload

    override fun toString(): String =
        "WorkflowAgentPublicInvocationBinding(operationId=${useCase.operationId}, <redacted>)"

    companion object {
        @JvmStatic
        fun bind(
            directory: WorkflowAgentPublicToolDirectory,
            executable: AgentExecutableToolInvocation,
            boundAt: Long,
        ): WorkflowAgentPublicInvocationBinding {
            val invocation = executable.invocation
            val useCase = directory.entry(invocation.toolId)
                ?: throw IllegalArgumentException("Workflow Agent public use case is unsupported.")
            val descriptor = useCase.toolDescriptor
            executable.requireExecutor(WorkflowAgentPublicToolDirectory.PROVIDER_ID, useCase.toolId)
            require(boundAt >= executable.preparedAt && boundAt < invocation.deadlineAt &&
                boundAt < executable.finalAuthorizationDecision.expiresAt
            ) { "Workflow Agent public invocation is outside its executable lifetime." }
            executable.consumption.requireMatches(invocation, boundAt)
            executable.dispatchFenceConsumption.requireMatches(executable.dispatchFenceRequest, boundAt)
            executable.finalAuthorizationDecision.requireAllowedFor(executable.finalAuthorizationRequest, boundAt)
            require(invocation.descriptorDigest == descriptor.descriptorDigest &&
                invocation.schemaDigest == descriptor.schemaDigest && invocation.descriptor.risk == descriptor.risk
            ) { "Workflow Agent public descriptor drifted after authorization." }

            val command = WorkflowAgentPublicCommand.decode(directory, invocation.toolId, invocation.arguments)
            require(invocation.arguments.contentEquals(command.arguments) &&
                invocation.argumentsDigest == command.argumentsDigest &&
                invocation.idempotencyKey == command.idempotencyKey &&
                invocation.authorizationAction == useCase.action &&
                invocation.authorizationResourceType == useCase.resourceType &&
                invocation.authorizationResourceId.value == command.resourceId &&
                invocation.authorizationResourceRevision == command.resourceRevision &&
                invocation.authorizationPurpose == command.purpose
            ) { "Workflow Agent public command changed after authorization." }

            if (useCase.confirmationRequired) {
                val approvalRequest = requireNotNull(invocation.approvalRequest) {
                    "Workflow Agent high-risk public use case requires exact confirmation."
                }
                val approvalDecision = requireNotNull(invocation.approvalDecision) {
                    "Workflow Agent high-risk public use case requires an approved confirmation."
                }
                require(invocation.policyDecision.outcome == AgentPolicyOutcome.REQUIRE_APPROVAL &&
                    approvalRequest.operatorId == invocation.principalId &&
                    approvalRequest.operatorType == invocation.principalType &&
                    approvalDecision.operatorId == invocation.principalId &&
                    approvalDecision.operatorType == invocation.principalType
                ) { "Workflow Agent public confirmation does not belong to the current principal." }
                approvalDecision.requireApprovedFor(
                    approvalRequest,
                    invocation.proposal,
                    invocation.policyDecision,
                    boundAt,
                )
            }

            require(executable.maximumCostMicros == descriptor.maximumCostMicros &&
                executable.maximumDurationMillis <= descriptor.maximumDurationMillis
            ) { "Workflow Agent public invocation budget drifted after authorization." }
            val authorityExpiresAt = minOf(executable.finalAuthorizationDecision.expiresAt, invocation.deadlineAt)
            val reservationExpiresAt = executable.preparedAt + executable.maximumDurationMillis
            val remainingDuration = minOf(authorityExpiresAt, reservationExpiresAt) - boundAt
            require(remainingDuration > 0L) { "Workflow Agent public invocation has no remaining duration budget." }
            val digest = WorkflowAgentDigest("flowweft.agent.workflow.public-invocation-binding.v1")
                .add(directory.directoryDigest)
                .add(useCase.descriptorDigest)
                .add(command.commandDigest)
                .add(executable.executionBindingDigest)
                .add(invocation.tenantId.value)
                .add(invocation.principalType)
                .add(invocation.principalId.value)
                .add(invocation.runId.value)
                .add(invocation.stepId.value)
                .add(invocation.invocationId.value)
                .add(invocation.executionContextId.value)
                .add(executable.consumption.receiptId.value)
                .add(executable.dispatchFenceConsumption.receiptId.value)
                .add(executable.finalAuthorizationDecision.providerId.value)
                .add(executable.finalAuthorizationDecision.decisionId.value)
                .add(executable.finalAuthorizationDecision.authorizationRevision)
                .add(authorityExpiresAt)
                .add(executable.maximumCostMicros)
                .add(executable.maximumDurationMillis)
                .add(reservationExpiresAt)
                .add(remainingDuration)
                .add(boundAt)
                .finish()
            val trusted = WorkflowWebTrustedContext.authenticated(
                invocation.tenantId.value,
                invocation.principalType,
                invocation.principalId.value,
                executable.finalAuthorizationDecision.decisionId.value,
                digest,
            )
            val preconditions = if (useCase.access == WorkflowAgentUseCaseAccess.WRITE) {
                WorkflowWebWritePreconditions.parse(
                    command.idempotencyKey,
                    WorkflowWebVersionTag.of(command.expectedResourceVersion).toHeaderValue(),
                )
            } else {
                null
            }
            return WorkflowAgentPublicInvocationBinding(
                useCase,
                command,
                invocation.tenantId,
                invocation.principalId,
                invocation.principalType,
                invocation.runId,
                invocation.stepId,
                invocation.invocationId,
                invocation.executionContextId,
                executable.finalAuthorizationDecision.providerId,
                executable.finalAuthorizationDecision.authorizationRevision,
                authorityExpiresAt,
                executable.maximumCostMicros,
                executable.maximumDurationMillis,
                remainingDuration,
                invocation.deadlineAt,
                boundAt,
                trusted,
                WorkflowWebResourceId.of(command.resourceId),
                preconditions,
                digest,
            )
        }
    }
}
