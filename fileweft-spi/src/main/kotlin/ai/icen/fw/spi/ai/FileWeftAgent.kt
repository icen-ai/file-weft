package ai.icen.fw.spi.ai

/**
 * Optional AI extension boundary. Agents may analyze task context and return
 * suggestions, but they never receive a domain mutation interface.
 */
interface FileWeftAgent {
    fun capability(): AgentCapability

    fun execute(task: AgentTask): AgentResult
}
