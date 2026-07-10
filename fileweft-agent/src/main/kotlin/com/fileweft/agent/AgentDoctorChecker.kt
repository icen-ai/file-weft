package com.fileweft.agent

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorStatus
import com.fileweft.spi.ai.FileWeftAgent
import com.fileweft.spi.doctor.DoctorChecker

/**
 * Reports which optional Agent capabilities are available to the durable task
 * worker. It intentionally performs no Agent execution: diagnosis must not
 * create a remote AI request or mutate a document.
 */
class AgentDoctorChecker(
    agents: List<FileWeftAgent>,
) : DoctorChecker {
    private val capabilities = agents.map { it.capability().name }.sorted()

    init {
        require(capabilities.distinct().size == capabilities.size) {
            "Only one agent may be registered for each capability."
        }
    }

    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult =
        if (capabilities.isEmpty()) {
            DoctorCheckResult(
                checkerName = NAME,
                status = DoctorStatus.SKIPPED,
                reason = "No optional Agent capability is installed.",
                repairSuggestion = "Register a FileWeftAgent and an AgentTaskTrigger when Agent analysis is required.",
            )
        } else {
            DoctorCheckResult(
                checkerName = NAME,
                status = DoctorStatus.HEALTHY,
                reason = "Optional Agent capabilities are registered for durable task execution.",
                evidence = mapOf(
                    "capabilityCount" to capabilities.size.toString(),
                    "capabilities" to capabilities.joinToString(","),
                ),
            )
        }

    companion object {
        const val NAME = "agent"
    }
}
