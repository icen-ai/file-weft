package com.fileweft.agent

import com.fileweft.application.task.TaskRepository
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.event.OutboxEvent
import com.fileweft.spi.ai.AgentTaskTrigger
import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.event.OutboxHandlingResult
import com.fileweft.spi.event.OutboxHandlingStatus

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
        transaction.execute {
            triggers.filter { it.supports(event) }.forEach { trigger ->
                trigger.capabilities(event).distinct().forEach { capability ->
                    scheduler.schedule(event, capability, tasks, trigger.businessId(event))
                }
            }
        }
        OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED)
    } catch (failure: Exception) {
        OutboxHandlingResult(OutboxHandlingStatus.RETRYABLE_FAILURE, "Agent task scheduling failed.")
    }
}
