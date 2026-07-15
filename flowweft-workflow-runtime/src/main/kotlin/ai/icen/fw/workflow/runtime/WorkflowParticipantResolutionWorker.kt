package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowParticipantResolver
import ai.icen.fw.workflow.spi.WorkflowOrganizationAuthority

/**
 * Production composition entry point for participant-resolution jobs.
 *
 * Hosts schedule [poll] only after supplying their trusted call context and the two provider
 * SPIs. The composition intentionally stays independent from Spring so the Workflow runtime can
 * be embedded without a Boot starter.
 */
class WorkflowParticipantResolutionWorker @JvmOverloads constructor(
    queuePort: WorkflowReadyEffectJobPort,
    persistencePort: WorkflowRuntimePersistencePort,
    authorizationPort: WorkflowRuntimeAuthorizationPort,
    participantResolver: WorkflowParticipantResolver,
    organizationAuthority: WorkflowOrganizationAuthority,
    durableRuntime: WorkflowDurableRuntime,
    clock: WorkflowWorkerClock,
    providerId: String,
    providerRevision: String,
    callWindowMillis: Long = 30_000L,
    maximumPrincipals: Int = 256,
    retryDelayMillis: Long = 5_000L,
    iterationBudget: Int = 256,
) {
    private val delegate = WorkflowEffectWorker(
        queuePort,
        persistencePort,
        WorkflowEffectCoordinator(authorizationPort, persistencePort),
        WorkflowParticipantResolutionEffectHandler(
            participantResolver,
            organizationAuthority,
            durableRuntime,
            clock,
            providerId,
            providerRevision,
            callWindowMillis,
            maximumPrincipals,
            retryDelayMillis,
            iterationBudget,
        ),
        clock,
    )

    fun poll(
        callContext: WorkflowTrustedCallContext,
        workerId: String,
        claimId: String,
        now: Long,
        leaseExpiresAt: Long,
        maximumJobs: Int,
    ): WorkflowEffectWorkerBatchResult = delegate.poll(
        callContext,
        workerId,
        claimId,
        now,
        leaseExpiresAt,
        maximumJobs,
    )
}
