package ai.icen.fw.agent.web.api

/** Versioned route catalog shared by controller adapters, BFF clients and generated documentation. */
class AgentWebRoute private constructor(
    method: String,
    pathTemplate: String,
    operationId: String,
    capabilityId: String,
    val idempotencyRequired: Boolean,
    val ifMatchRequired: Boolean,
) {
    val method: String = agentWebCode(method, "Agent Web route method")
    val pathTemplate: String = agentWebText(pathTemplate, 512, "Agent Web route path")
    val operationId: String = agentWebCode(operationId, "Agent Web route operation")
    val capabilityId: String = agentWebCode(capabilityId, "Agent Web route capability")

    init {
        require(pathTemplate.startsWith(BASE_PATH + "/")) { "Agent Web routes must remain under $BASE_PATH." }
        require(idempotencyRequired == ifMatchRequired) {
            "Every Agent Web mutation requires both Idempotency-Key and If-Match."
        }
        require((method == "GET") != idempotencyRequired) {
            "Agent Web GET routes cannot require mutation headers and mutations must require them."
        }
    }

    companion object {
        const val CONTRACT_VERSION: String = "flowweft.agent.web.v1"
        const val BASE_PATH: String = "/flowweft/v1/agent"

        private fun read(path: String, operation: String, capability: String): AgentWebRoute =
            AgentWebRoute("GET", path, operation, capability, false, false)

        private fun write(
            method: String,
            path: String,
            operation: String,
            capability: String,
        ): AgentWebRoute = AgentWebRoute(method, path, operation, capability, true, true)

        @JvmStatic
        fun all(): List<AgentWebRoute> = ROUTES

        private val ROUTES: List<AgentWebRoute> = agentWebList(
            listOf(
                write("POST", "$BASE_PATH/conversations", "createAgentConversation", "agent.conversation.create"),
                read("$BASE_PATH/conversations", "listAgentConversations", "agent.conversation.read"),
                read("$BASE_PATH/conversations/{conversationId}", "getAgentConversation", "agent.conversation.read"),
                write("POST", "$BASE_PATH/conversations/{conversationId}/runs", "createAgentRun", "agent.run.create"),
                read("$BASE_PATH/conversations/{conversationId}/runs", "listAgentRuns", "agent.run.read"),
                read("$BASE_PATH/runs/{runId}", "getAgentRun", "agent.run.read"),
                read("$BASE_PATH/runs/{runId}/messages", "listAgentRunMessages", "agent.message.read"),
                read("$BASE_PATH/runs/{runId}/events", "listAgentRunEvents", "agent.event.read"),
                write("POST", "$BASE_PATH/runs/{runId}/cancel", "cancelAgentRun", "agent.run.cancel"),
                read("$BASE_PATH/runs/{runId}/citations", "listAgentRunCitations", "agent.citation.read"),
                read("$BASE_PATH/tool-confirmations", "listAgentToolConfirmations", "agent.confirmation.read"),
                read(
                    "$BASE_PATH/tool-confirmations/{requestId}",
                    "getAgentToolConfirmation",
                    "agent.confirmation.read",
                ),
                write(
                    "POST",
                    "$BASE_PATH/tool-confirmations/{requestId}/approve",
                    "approveAgentToolConfirmation",
                    "agent.confirmation.approve",
                ),
                write(
                    "POST",
                    "$BASE_PATH/tool-confirmations/{requestId}/reject",
                    "rejectAgentToolConfirmation",
                    "agent.confirmation.reject",
                ),
                read("$BASE_PATH/providers/capabilities", "listAgentProviderCapabilities", "agent.provider.read"),
                read("$BASE_PATH/provider-configurations", "listAgentProviderConfigurations", "agent.config.read"),
                read(
                    "$BASE_PATH/provider-configurations/{profileId}",
                    "getAgentProviderConfiguration",
                    "agent.config.read",
                ),
                write(
                    "PUT",
                    "$BASE_PATH/provider-configurations/{profileId}",
                    "putAgentProviderConfiguration",
                    "agent.config.write",
                ),
                read("$BASE_PATH/doctor", "getAgentDoctor", "agent.doctor.read"),
                read("$BASE_PATH/evaluations/datasets", "listAgentEvaluationDatasets", "agent.evaluation.read"),
                read(
                    "$BASE_PATH/evaluations/datasets/{suiteId}",
                    "getAgentEvaluationDataset",
                    "agent.evaluation.read",
                ),
                write("POST", "$BASE_PATH/evaluations/runs", "triggerAgentEvaluationRun", "agent.evaluation.trigger"),
                read("$BASE_PATH/evaluations/runs", "listAgentEvaluationRuns", "agent.evaluation.read"),
                read(
                    "$BASE_PATH/evaluations/runs/{evaluationId}",
                    "getAgentEvaluationRun",
                    "agent.evaluation.read",
                ),
                read(
                    "$BASE_PATH/evaluations/runs/{evaluationId}/results",
                    "getAgentEvaluationResult",
                    "agent.evaluation.read",
                ),
            ),
            64,
            "Agent Web routes",
        ).also { routes ->
            require(routes.map { route -> route.operationId }.toSet().size == routes.size) {
                "Agent Web route operation identifiers must be unique."
            }
            require(routes.map { route -> route.method + " " + route.pathTemplate }.toSet().size == routes.size) {
                "Agent Web route method/path pairs must be unique."
            }
        }
    }
}
