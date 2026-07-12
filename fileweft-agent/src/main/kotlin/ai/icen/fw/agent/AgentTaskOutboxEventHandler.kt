package ai.icen.fw.agent

import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.ai.AgentCapability
import ai.icen.fw.spi.ai.AgentTaskTrigger
import ai.icen.fw.spi.event.OutboxEventHandler
import ai.icen.fw.spi.event.OutboxHandlingResult
import ai.icen.fw.spi.event.OutboxHandlingStatus

/**
 * Turns explicitly matched committed events into durable agent work. It only
 * writes local tasks; agent execution remains in the separate task worker.
 */
class AgentTaskOutboxEventHandler(
    triggers: List<AgentTaskTrigger>,
    private val scheduler: AgentTaskScheduler,
    private val tasks: TaskRepository,
    private val transaction: ApplicationTransaction,
) : OutboxEventHandler {
    private val triggers: List<AgentTaskTrigger> = ArrayList(triggers)

    override fun supports(event: OutboxEvent): Boolean = triggers.any { it.supports(event) }

    override fun handle(event: OutboxEvent): OutboxHandlingResult = try {
        // Triggers are extension-owned and may consult a remote policy service.
        // Freeze their scheduling decision before opening the local task
        // transaction; only idempotent task persistence belongs in that scope.
        val plans = triggers
            .asSequence()
            .filter { trigger -> trigger.supports(event) }
            .flatMap { trigger ->
                val businessId = trigger.businessId(event)
                trigger.capabilities(event).distinct().asSequence().map { capability ->
                    AgentTaskSchedulePlan(capability, businessId)
                }
            }
            .toList()
        transaction.execute {
            plans.forEach { plan ->
                scheduler.schedule(event, plan.capability, tasks, plan.businessId)
            }
        }
        OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED)
    } catch (failure: Exception) {
        OutboxHandlingResult(OutboxHandlingStatus.RETRYABLE_FAILURE, "Agent task scheduling failed.")
    }

    private class AgentTaskSchedulePlan(
        val capability: AgentCapability,
        val businessId: Identifier?,
    )
}
