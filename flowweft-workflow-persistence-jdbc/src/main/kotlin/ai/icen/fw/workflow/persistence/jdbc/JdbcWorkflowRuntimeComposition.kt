package ai.icen.fw.workflow.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowParticipantResolver
import ai.icen.fw.workflow.runtime.WorkflowDurableRuntime
import ai.icen.fw.workflow.runtime.WorkflowEffectDispatchPort
import ai.icen.fw.workflow.runtime.WorkflowParticipantResolutionWorker
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationPort
import ai.icen.fw.workflow.runtime.WorkflowRuntimeHumanCollaborationAuthorizationPort
import ai.icen.fw.workflow.runtime.WorkflowWorkerClock
import ai.icen.fw.workflow.spi.WorkflowOrganizationAuthority
import javax.sql.DataSource

/**
 * Spring-independent production composition for the durable JDBC runtime and its participant
 * worker. Hosts still own migration execution, scheduling, authorization and provider SPIs; this
 * entry point only prevents subtly different manual wiring of the two JDBC ports.
 */
class JdbcWorkflowRuntimeComposition @JvmOverloads constructor(
    dataSource: DataSource,
    authorizationPort: WorkflowRuntimeAuthorizationPort,
    dispatchPort: WorkflowEffectDispatchPort,
    participantResolver: WorkflowParticipantResolver,
    organizationAuthority: WorkflowOrganizationAuthority,
    clock: WorkflowWorkerClock,
    providerId: String,
    providerRevision: String,
    dialect: WorkflowJdbcDialect? = null,
    collaborationAuthorizationPort: WorkflowRuntimeHumanCollaborationAuthorizationPort? = null,
    callWindowMillis: Long = 30_000L,
    maximumPrincipals: Int = 256,
    retryDelayMillis: Long = 5_000L,
    iterationBudget: Int = 256,
) {
    val persistence: JdbcWorkflowRuntimePersistence = JdbcWorkflowRuntimePersistence(dataSource, dialect)
    val readyEffectJobs: JdbcWorkflowReadyEffectJobQueue = JdbcWorkflowReadyEffectJobQueue(dataSource, dialect)
    val runtime: WorkflowDurableRuntime = if (collaborationAuthorizationPort == null) {
        WorkflowDurableRuntime(authorizationPort, persistence, dispatchPort)
    } else {
        WorkflowDurableRuntime(
            authorizationPort,
            persistence,
            dispatchPort,
            collaborationAuthorizationPort,
        )
    }
    val participantResolutionWorker: WorkflowParticipantResolutionWorker =
        WorkflowParticipantResolutionWorker(
            readyEffectJobs,
            persistence,
            authorizationPort,
            participantResolver,
            organizationAuthority,
            runtime,
            clock,
            providerId,
            providerRevision,
            callWindowMillis,
            maximumPrincipals,
            retryDelayMillis,
            iterationBudget,
        )

    override fun toString(): String = "JdbcWorkflowRuntimeComposition(<redacted>)"
}
