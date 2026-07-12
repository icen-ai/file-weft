package ai.icen.fw.application.agent

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.task.BackgroundTaskStatus
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.spi.ai.AgentExecutionStatus
import ai.icen.fw.spi.ai.AgentSuggestionConfirmation
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import java.time.Clock

/**
 * User-facing boundary for accepting an agent suggestion. This records consent
 * only; a separate explicit application use case must apply any domain change.
 */
class ConfirmAgentSuggestionService private constructor(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val results: AgentResultRepository,
    private val identifiers: IdentifierGenerator,
    private val transaction: ApplicationTransaction,
    private val clock: Clock,
    private val auditTrail: AuditTrail?,
    private val tasks: TaskRepository?,
    private val fenced: Boolean,
) {
    /** Retains the original confirmation ABI and pre-task-fence semantics. */
    constructor(
        tenantProvider: TenantProvider,
        userRealmProvider: UserRealmProvider,
        authorizationProvider: AuthorizationProvider,
        results: AgentResultRepository,
        identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction,
        clock: Clock,
        auditTrail: AuditTrail? = null,
    ) : this(
        tenantProvider, userRealmProvider, authorizationProvider, results, identifiers,
        transaction, clock, auditTrail, null, false,
    )

    /** Strong path that exposes suggestions only after their durable task succeeds. */
    constructor(
        tenantProvider: TenantProvider,
        userRealmProvider: UserRealmProvider,
        authorizationProvider: AuthorizationProvider,
        results: AgentResultRepository,
        identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction,
        clock: Clock,
        auditTrail: AuditTrail?,
        tasks: TaskRepository,
    ) : this(
        tenantProvider, userRealmProvider, authorizationProvider, results, identifiers,
        transaction, clock, auditTrail, tasks, true,
    )

    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    init {
        require(fenced == (tasks != null)) {
            "Agent suggestion confirmation fencing requires a task repository."
        }
    }

    fun confirm(taskId: Identifier, suggestionId: Identifier): AgentSuggestionConfirmation {
        val tenant = tenantProvider.currentTenant()
        val operator = authorization.requireAction(tenant.tenantId, taskId, RESOURCE_TYPE, CONFIRM_ACTION)
        return transaction.execute {
            requireSuccessfulTask(tenant.tenantId, taskId)
            val result = results.findByTask(tenant.tenantId, taskId)
                ?: throw NoSuchElementException("Agent result ${taskId.value} was not found in the current tenant.")
            require(result.result.status == AgentExecutionStatus.SUCCEEDED) {
                "Only successful agent results can be confirmed."
            }
            require(result.result.suggestions.any { it.id == suggestionId }) {
                "Suggestion does not belong to the supplied agent result."
            }
            val confirmedAt = clock.millis()
            val persisted = results.saveConfirmation(
                PersistedAgentSuggestionConfirmation(
                    id = identifiers.nextId(),
                    tenantId = tenant.tenantId,
                    taskId = taskId,
                    suggestionId = suggestionId,
                    confirmedBy = operator.id,
                    confirmedAt = confirmedAt,
                ),
            )
            auditTrail?.record(
                tenantId = tenant.tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = taskId,
                action = CONFIRM_ACTION,
                operatorId = operator.id,
                operatorName = operator.displayName,
                details = mapOf("suggestionId" to suggestionId.value),
            )
            AgentSuggestionConfirmation(persisted.taskId, persisted.suggestionId, persisted.confirmedBy, persisted.confirmedAt)
        }
    }

    private fun requireSuccessfulTask(tenantId: Identifier, taskId: Identifier) {
        if (!fenced) return
        val task = requireNotNull(tasks).findById(tenantId, taskId)
            ?: throw NoSuchElementException("Agent task ${taskId.value} was not found in the current tenant.")
        check(task.status == BackgroundTaskStatus.SUCCESS) {
            "Agent suggestions can be confirmed only after their durable task succeeds."
        }
    }

    companion object {
        const val RESOURCE_TYPE = "AGENT_RESULT"
        const val CONFIRM_ACTION = "agent:suggestion:confirm"
    }
}
