package ai.icen.fw.agent.web.spring.boot3

import ai.icen.fw.agent.web.api.AgentWebApplicationResult
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.servlet.http.HttpServletRequest

/**
 * Thin transport adapter for Agent Web v1. It validates bounded HTTP input, obtains identity only
 * from the host trusted-context port, and delegates every operation to an application port.
 */
@RestController
@RequestMapping("/flowweft/v1/agent")
class FlowWeftAgentWebBoot3Controller(
    private val ports: FlowWeftAgentWebBoot3ApplicationPorts,
    contexts: ai.icen.fw.agent.web.api.AgentWebTrustedContextProvider,
    codec: FlowWeftAgentWebBoot3JsonCodec,
) {
    private val requests = FlowWeftAgentWebBoot3RequestSupport(codec, contexts)

    @PostMapping("/conversations")
    fun createConversation(request: HttpServletRequest): ResponseEntity<*> =
        requests.write(
            request,
            preparation = { body ->
                requests.noQuery(request)
                requests.decode(body, AgentWebConversationCreateJson::class.java).toCommand()
            },
            version = { it.summary.stateVersion },
        ) { command, context, preconditions ->
            ports.conversations?.create(context, preconditions, command) ?: AgentWebApplicationResult.unsupported()
        }

    @GetMapping("/conversations")
    fun listConversations(request: HttpServletRequest): ResponseEntity<*> = requests.read(
        request,
        preparation = { requests.page(request) },
    ) { context, query -> ports.conversations?.list(context, query) ?: AgentWebApplicationResult.unsupported() }

    @GetMapping("/conversations/{conversationId}")
    fun getConversation(
        @PathVariable("conversationId") conversationId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.read(
        request,
        preparation = {
            requests.noQuery(request)
            requests.identifier(conversationId)
        },
        version = { it.summary.stateVersion },
    ) { context, id ->
        ports.conversations?.get(context, id)
            ?: AgentWebApplicationResult.unsupported()
    }

    @PostMapping("/conversations/{conversationId}/runs")
    fun createRun(
        @PathVariable("conversationId") conversationId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.write(
        request,
        preparation = { body ->
            requests.noQuery(request)
            requests.identifier(conversationId) to
                requests.decode(body, AgentWebRunCreateJson::class.java).toCommand()
        },
        version = { it.stateVersion },
    ) { prepared, context, preconditions ->
        ports.conversations?.startRun(
            context,
            prepared.first,
            preconditions,
            prepared.second,
        ) ?: AgentWebApplicationResult.unsupported()
    }

    @GetMapping("/conversations/{conversationId}/runs")
    fun listRunsForConversation(
        @PathVariable("conversationId") conversationId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.read(
        request,
        preparation = { requests.identifier(conversationId) to requests.page(request) },
    ) { context, prepared ->
        ports.conversations?.listRuns(
            context,
            prepared.first,
            prepared.second,
        ) ?: AgentWebApplicationResult.unsupported()
    }

    @GetMapping("/runs/{runId}")
    fun getRun(
        @PathVariable("runId") runId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.read(
        request,
        preparation = {
            requests.noQuery(request)
            requests.identifier(runId)
        },
        version = { it.stateVersion },
    ) { context, id ->
        ports.runs?.get(context, id) ?: AgentWebApplicationResult.unsupported()
    }

    @GetMapping("/runs/{runId}/messages")
    fun listRunMessages(
        @PathVariable("runId") runId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.read(
        request,
        preparation = { requests.identifier(runId) to requests.page(request) },
    ) { context, prepared ->
        ports.runs?.listMessages(context, prepared.first, prepared.second)
            ?: AgentWebApplicationResult.unsupported()
    }

    @GetMapping("/runs/{runId}/events")
    fun listRunEvents(
        @PathVariable("runId") runId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.events(
        request,
        preparation = { requests.identifier(runId) to requests.page(request) },
    ) { context, prepared ->
        ports.runs?.listEvents(context, prepared.first, prepared.second)
            ?: AgentWebApplicationResult.unsupported()
    }

    @PostMapping("/runs/{runId}/cancel")
    fun cancelRun(
        @PathVariable("runId") runId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.write(
        request,
        preparation = { body ->
            requests.noQuery(request)
            requests.identifier(runId) to requests.decode(body, AgentWebRunCancelJson::class.java).toCommand()
        },
        version = { it.resourceVersion },
    ) { prepared, context, preconditions ->
        ports.runs?.cancel(context, prepared.first, preconditions, prepared.second)
            ?: AgentWebApplicationResult.unsupported()
    }

    @GetMapping("/runs/{runId}/citations")
    fun listRunCitations(
        @PathVariable("runId") runId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.read(
        request,
        preparation = { requests.identifier(runId) to requests.page(request) },
    ) { context, prepared ->
        ports.runs?.listCitations(context, prepared.first, prepared.second)
            ?: AgentWebApplicationResult.unsupported()
    }

    @GetMapping("/tool-confirmations")
    fun listToolConfirmations(request: HttpServletRequest): ResponseEntity<*> = requests.read(
        request,
        preparation = { requests.page(request) },
    ) { context, query -> ports.confirmations?.inbox(context, query) ?: AgentWebApplicationResult.unsupported() }

    @GetMapping("/tool-confirmations/{requestId}")
    fun getToolConfirmation(
        @PathVariable("requestId") requestId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.read(
        request,
        preparation = {
            requests.noQuery(request)
            requests.identifier(requestId)
        },
        version = { it.stateVersion },
    ) { context, id ->
        ports.confirmations?.get(context, id) ?: AgentWebApplicationResult.unsupported()
    }

    @PostMapping("/tool-confirmations/{requestId}/approve")
    fun approveToolConfirmation(
        @PathVariable("requestId") requestId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = confirmationDecision(requestId, request, true)

    @PostMapping("/tool-confirmations/{requestId}/reject")
    fun rejectToolConfirmation(
        @PathVariable("requestId") requestId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = confirmationDecision(requestId, request, false)

    @GetMapping("/providers/capabilities")
    fun listProviderCapabilities(request: HttpServletRequest): ResponseEntity<*> = requests.read(
        request,
        preparation = { requests.page(request) },
    ) { context, query ->
        ports.configuration?.listProviderCapabilities(context, query) ?: AgentWebApplicationResult.unsupported()
    }

    @GetMapping("/provider-configurations")
    fun listProviderConfigurations(request: HttpServletRequest): ResponseEntity<*> = requests.read(
        request,
        preparation = { requests.page(request) },
    ) { context, query ->
        ports.configuration?.listConfigurations(context, query) ?: AgentWebApplicationResult.unsupported()
    }

    @GetMapping("/provider-configurations/{profileId}")
    fun getProviderConfiguration(
        @PathVariable("profileId") profileId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.read(
        request,
        preparation = {
            requests.noQuery(request)
            requests.identifier(profileId)
        },
        version = { it.stateVersion },
    ) { context, id ->
        ports.configuration?.getConfiguration(context, id)
            ?: AgentWebApplicationResult.unsupported()
    }

    @PutMapping("/provider-configurations/{profileId}")
    fun putProviderConfiguration(
        @PathVariable("profileId") profileId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.write(
        request,
        preparation = { body ->
            requests.noQuery(request)
            requests.identifier(profileId) to
                requests.decode(body, AgentWebProviderConfigurationJson::class.java).toCommand()
        },
        version = { it.stateVersion },
    ) { prepared, context, preconditions ->
        ports.configuration?.putConfiguration(
            context,
            prepared.first,
            preconditions,
            prepared.second,
        ) ?: AgentWebApplicationResult.unsupported()
    }

    @GetMapping("/doctor")
    fun doctor(request: HttpServletRequest): ResponseEntity<*> = requests.read(
        request,
        preparation = { requests.noQuery(request) },
    ) { context, _ ->
        ports.configuration?.doctor(context) ?: AgentWebApplicationResult.unsupported()
    }

    @GetMapping("/evaluations/datasets")
    fun listEvaluationDatasets(request: HttpServletRequest): ResponseEntity<*> = requests.read(
        request,
        preparation = { requests.page(request) },
    ) { context, query -> ports.evaluations?.listDatasets(context, query) ?: AgentWebApplicationResult.unsupported() }

    @GetMapping("/evaluations/datasets/{suiteId}")
    fun getEvaluationDataset(
        @PathVariable("suiteId") suiteId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.read(
        request,
        preparation = { requests.datasetReference(request, suiteId) },
    ) { context, dataset ->
        ports.evaluations?.getDataset(context, dataset)
            ?: AgentWebApplicationResult.unsupported()
    }

    @PostMapping("/evaluations/runs")
    fun triggerEvaluationRun(request: HttpServletRequest): ResponseEntity<*> =
        requests.write(
            request,
            preparation = { body ->
                requests.noQuery(request)
                requests.decode(body, AgentWebEvaluationTriggerJson::class.java).toCommand()
            },
            version = { it.stateVersion },
        ) { command, context, preconditions ->
            ports.evaluations?.trigger(context, preconditions, command) ?: AgentWebApplicationResult.unsupported()
        }

    @GetMapping("/evaluations/runs")
    fun listEvaluationRuns(request: HttpServletRequest): ResponseEntity<*> = requests.read(
        request,
        preparation = { requests.page(request) },
    ) { context, query -> ports.evaluations?.listRuns(context, query) ?: AgentWebApplicationResult.unsupported() }

    @GetMapping("/evaluations/runs/{evaluationId}")
    fun getEvaluationRun(
        @PathVariable("evaluationId") evaluationId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.read(
        request,
        preparation = {
            requests.noQuery(request)
            requests.identifier(evaluationId)
        },
        version = { it.stateVersion },
    ) { context, id ->
        ports.evaluations?.getRun(context, id)
            ?: AgentWebApplicationResult.unsupported()
    }

    @GetMapping("/evaluations/runs/{evaluationId}/results")
    fun getEvaluationResult(
        @PathVariable("evaluationId") evaluationId: String,
        request: HttpServletRequest,
    ): ResponseEntity<*> = requests.read(
        request,
        preparation = {
            requests.noQuery(request)
            requests.identifier(evaluationId)
        },
        version = { it.stateVersion },
    ) { context, id ->
        ports.evaluations?.getResult(context, id)
            ?: AgentWebApplicationResult.unsupported()
    }

    private fun confirmationDecision(
        requestId: String,
        request: HttpServletRequest,
        approve: Boolean,
    ): ResponseEntity<*> = requests.write(
        request,
        preparation = { body ->
            requests.noQuery(request)
            val id = requests.identifier(requestId)
            val json = requests.decode(body, AgentWebToolConfirmationDecisionJson::class.java)
            id to (if (approve) json.approve(id) else json.reject(id))
        },
        version = { it.stateVersion },
    ) { prepared, context, preconditions ->
        (if (approve) {
            ports.confirmations?.approve(context, prepared.first, preconditions, prepared.second)
        } else {
            ports.confirmations?.reject(context, prepared.first, preconditions, prepared.second)
        }) ?: AgentWebApplicationResult.unsupported()
    }
}
