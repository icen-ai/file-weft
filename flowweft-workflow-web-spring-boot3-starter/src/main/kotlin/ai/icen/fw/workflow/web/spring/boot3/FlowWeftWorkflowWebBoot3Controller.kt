package ai.icen.fw.workflow.web.spring.boot3

import ai.icen.fw.workflow.web.api.WorkflowInstanceControlOperation
import ai.icen.fw.workflow.web.api.WorkflowIncidentOperation
import ai.icen.fw.workflow.web.api.WorkflowWebApplicationResult
import ai.icen.fw.workflow.web.api.WorkflowWebResponse
import ai.icen.fw.workflow.web.api.WorkflowWebRoute
import ai.icen.fw.workflow.web.runtime.WorkflowWebControllerRuntime
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.servlet.http.HttpServletRequest

/**
 * Thin servlet adapter for Workflow v1. It performs only bounded transport conversion and calls
 * the framework-neutral controller runtime/application ports. It never reads identity headers.
 */
@RestController
@RequestMapping("/flowweft/v1/workflows")
class FlowWeftWorkflowWebBoot3Controller(
    private val runtime: WorkflowWebControllerRuntime,
    private val ports: FlowWeftWorkflowWebBoot3ApplicationPorts,
    codec: FlowWeftWorkflowWebBoot3JsonCodec,
) {
    private val requests = FlowWeftWorkflowWebBoot3RequestSupport(codec)

    @GetMapping("/capabilities")
    fun capabilities(request: HttpServletRequest): ResponseEntity<WorkflowWebResponse<*>> =
        requests.handle(request) { _, metadata ->
            requests.noQuery(request)
            runtime.executeRead(route("listWorkflowCapabilities"), metadata) { context ->
                ports.capabilities?.listCapabilities(context) ?: WorkflowWebApplicationResult.unsupported()
            }
        }

    @GetMapping("/definitions")
    fun definitions(request: HttpServletRequest): ResponseEntity<WorkflowWebResponse<*>> =
        requests.handle(request) { _, metadata ->
            val query = requests.page(request)
            runtime.executeRead(route("listWorkflowDefinitions"), metadata) { context ->
                ports.definitions?.list(context, query) ?: WorkflowWebApplicationResult.unsupported()
            }
        }

    @GetMapping("/definitions/{definitionId}")
    fun definition(
        @PathVariable("definitionId") definitionId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { _, metadata ->
        requests.noQuery(request)
        val id = requests.resource(definitionId)
        runtime.executeRead(route("getWorkflowDefinition"), metadata) { context ->
            ports.definitions?.get(context, id) ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @PutMapping("/definitions/{definitionId}/draft")
    fun putDefinitionDraft(
        @PathVariable("definitionId") definitionId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { body, metadata ->
        requests.noQuery(request)
        val id = requests.resource(definitionId)
        val command = requests.decode(body, WorkflowDefinitionDraftJson::class.java).toCommand()
        runtime.executeWrite(route("putWorkflowDefinitionDraft"), metadata) { context, preconditions ->
            ports.definitions?.putDraft(context, id, preconditions, command)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @PostMapping("/definitions/{definitionId}/publish")
    fun publishDefinition(
        @PathVariable("definitionId") definitionId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = definitionLifecycle(
        definitionId, request, "publishWorkflowDefinition",
    ) { context, id, preconditions, command ->
        ports.definitions?.publish(context, id, preconditions, command)
            ?: WorkflowWebApplicationResult.unsupported()
    }

    @PostMapping("/definitions/{definitionId}/retire")
    fun retireDefinition(
        @PathVariable("definitionId") definitionId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = definitionLifecycle(
        definitionId, request, "retireWorkflowDefinition",
    ) { context, id, preconditions, command ->
        ports.definitions?.retire(context, id, preconditions, command)
            ?: WorkflowWebApplicationResult.unsupported()
    }

    @PostMapping("/instances")
    fun startInstance(request: HttpServletRequest): ResponseEntity<WorkflowWebResponse<*>> =
        requests.handle(request) { body, metadata ->
            requests.noQuery(request)
            val command = requests.decode(body, WorkflowInstanceStartJson::class.java).toCommand()
            runtime.executeWrite(route("startWorkflowInstance"), metadata) { context, preconditions ->
                ports.instances?.start(context, preconditions, command)
                    ?: WorkflowWebApplicationResult.unsupported()
            }
        }

    @GetMapping("/instances/{instanceId}")
    fun instance(
        @PathVariable("instanceId") instanceId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { _, metadata ->
        requests.noQuery(request)
        val id = requests.resource(instanceId)
        runtime.executeRead(route("getWorkflowInstance"), metadata) { context ->
            ports.instances?.get(context, id) ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @PostMapping("/instances/{instanceId}/suspend")
    fun suspendInstance(
        @PathVariable("instanceId") instanceId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = controlInstance(
        instanceId, request, "suspendWorkflowInstance", WorkflowInstanceControlOperation.SUSPEND,
    )

    @PostMapping("/instances/{instanceId}/resume")
    fun resumeInstance(
        @PathVariable("instanceId") instanceId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = controlInstance(
        instanceId, request, "resumeWorkflowInstance", WorkflowInstanceControlOperation.RESUME,
    )

    @PostMapping("/instances/{instanceId}/cancel")
    fun cancelInstance(
        @PathVariable("instanceId") instanceId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = controlInstance(
        instanceId, request, "cancelWorkflowInstance", WorkflowInstanceControlOperation.CANCEL,
    )

    @PostMapping("/instances/{instanceId}/terminate")
    fun terminateInstance(
        @PathVariable("instanceId") instanceId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = controlInstance(
        instanceId, request, "terminateWorkflowInstance", WorkflowInstanceControlOperation.TERMINATE,
    )

    @GetMapping("/instances/{instanceId}/history")
    fun history(
        @PathVariable("instanceId") instanceId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { _, metadata ->
        val id = requests.resource(instanceId)
        val query = requests.page(request)
        runtime.executeRead(route("listWorkflowHistory"), metadata) { context ->
            ports.instances?.history(context, id, query) ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @GetMapping("/tasks")
    fun tasks(request: HttpServletRequest): ResponseEntity<WorkflowWebResponse<*>> =
        requests.handle(request) { _, metadata ->
            val query = requests.page(request)
            runtime.executeRead(route("listWorkflowTasks"), metadata) { context ->
                ports.tasks?.list(context, query) ?: WorkflowWebApplicationResult.unsupported()
            }
        }

    @GetMapping("/tasks/{taskId}")
    fun task(
        @PathVariable("taskId") taskId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { _, metadata ->
        requests.noQuery(request)
        val id = requests.resource(taskId)
        runtime.executeRead(route("getWorkflowTask"), metadata) { context ->
            ports.tasks?.get(context, id) ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @PostMapping("/tasks/{taskId}/claim")
    fun claimTask(
        @PathVariable("taskId") taskId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { body, metadata ->
        requests.noQuery(request)
        val id = requests.resource(taskId)
        val command = requests.decode(body, WorkflowTaskClaimJson::class.java).toCommand()
        runtime.executeWrite(route("claimWorkflowTask"), metadata) { context, preconditions ->
            ports.tasks?.claim(context, id, preconditions, command)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @PostMapping("/tasks/{taskId}/decisions")
    fun decideTask(
        @PathVariable("taskId") taskId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { body, metadata ->
        requests.noQuery(request)
        val id = requests.resource(taskId)
        val command = requests.decode(body, WorkflowTaskDecisionJson::class.java).toCommand()
        runtime.executeWrite(route("decideWorkflowTask"), metadata) { context, preconditions ->
            ports.tasks?.decide(context, id, preconditions, command)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @PostMapping("/tasks/{taskId}/delegate")
    fun delegateTask(
        @PathVariable("taskId") taskId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { body, metadata ->
        requests.noQuery(request)
        val id = requests.resource(taskId)
        val command = requests.decode(body, WorkflowTaskDelegateJson::class.java).toCommand()
        runtime.executeWrite(route("delegateWorkflowTask"), metadata) { context, preconditions ->
            ports.tasks?.delegate(context, id, preconditions, command)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @PostMapping("/tasks/{taskId}/add-sign")
    fun addTaskSigner(
        @PathVariable("taskId") taskId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { body, metadata ->
        requests.noQuery(request)
        val id = requests.resource(taskId)
        val command = requests.decode(body, WorkflowTaskAddSignJson::class.java).toCommand()
        runtime.executeWrite(route("addWorkflowTaskSigner"), metadata) { context, preconditions ->
            ports.tasks?.addSign(context, id, preconditions, command)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @PostMapping("/tasks/{taskId}/return")
    fun returnTask(
        @PathVariable("taskId") taskId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { body, metadata ->
        requests.noQuery(request)
        val id = requests.resource(taskId)
        val command = requests.decode(body, WorkflowTaskReturnJson::class.java).toCommand()
        runtime.executeWrite(route("returnWorkflowTask"), metadata) { context, preconditions ->
            ports.tasks?.returnTask(context, id, preconditions, command)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @GetMapping("/tasks/{taskId}/form")
    fun taskForm(
        @PathVariable("taskId") taskId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { _, metadata ->
        requests.noQuery(request)
        val id = requests.resource(taskId)
        runtime.executeRead(route("getWorkflowTaskForm"), metadata) { context ->
            ports.tasks?.getForm(context, id) ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @PostMapping("/tasks/{taskId}/form-submissions")
    fun submitTaskForm(
        @PathVariable("taskId") taskId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { body, metadata ->
        requests.noQuery(request)
        val id = requests.resource(taskId)
        val command = requests.decode(body, WorkflowFormSubmissionJson::class.java).toCommand()
        runtime.executeWrite(route("submitWorkflowTaskForm"), metadata) { context, preconditions ->
            ports.tasks?.submitForm(context, id, preconditions, command)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @GetMapping("/instances/{instanceId}/comments")
    fun comments(
        @PathVariable("instanceId") instanceId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { _, metadata ->
        val id = requests.resource(instanceId)
        val query = requests.page(request)
        runtime.executeRead(route("listWorkflowComments"), metadata) { context ->
            ports.collaboration?.listComments(context, id, query)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @PostMapping("/instances/{instanceId}/comments")
    fun createComment(
        @PathVariable("instanceId") instanceId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { body, metadata ->
        requests.noQuery(request)
        val id = requests.resource(instanceId)
        val command = requests.decode(body, WorkflowCommentDocumentJson::class.java).toCommand()
        runtime.executeWrite(route("createWorkflowComment"), metadata) { context, preconditions ->
            ports.collaboration?.createComment(context, id, preconditions, command)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @GetMapping("/incidents")
    fun incidents(request: HttpServletRequest): ResponseEntity<WorkflowWebResponse<*>> =
        requests.handle(request) { _, metadata ->
            val query = requests.incidentQuery(request)
            runtime.executeRead(route("listWorkflowIncidents"), metadata) { context ->
                ports.operations?.listIncidents(context, query)
                    ?: WorkflowWebApplicationResult.unsupported()
            }
        }

    @GetMapping("/incidents/{incidentId}")
    fun incident(
        @PathVariable("incidentId") incidentId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { _, metadata ->
        requests.noQuery(request)
        val id = requests.resource(incidentId)
        runtime.executeRead(route("getWorkflowIncident"), metadata) { context ->
            ports.operations?.getIncident(context, id)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @PostMapping("/incidents/{incidentId}/retry")
    fun retryIncident(@PathVariable("incidentId") incidentId: String, request: HttpServletRequest) =
        actOnIncident(incidentId, request, "retryWorkflowIncident", WorkflowIncidentOperation.RETRY)

    @PostMapping("/incidents/{incidentId}/skip")
    fun skipIncident(@PathVariable("incidentId") incidentId: String, request: HttpServletRequest) =
        actOnIncident(incidentId, request, "skipWorkflowIncident", WorkflowIncidentOperation.SKIP)

    @PostMapping("/incidents/{incidentId}/repair")
    fun repairIncident(@PathVariable("incidentId") incidentId: String, request: HttpServletRequest) =
        actOnIncident(incidentId, request, "repairWorkflowIncident", WorkflowIncidentOperation.REPAIR)

    @PostMapping("/migrations/dry-run")
    fun dryRunMigration(request: HttpServletRequest): ResponseEntity<WorkflowWebResponse<*>> =
        requests.handle(request) { body, metadata ->
            requests.noQuery(request)
            val command = requests.decode(body, WorkflowMigrationJson::class.java).toCommand()
            runtime.executeWrite(route("dryRunWorkflowMigration"), metadata) { context, preconditions ->
                ports.operations?.dryRunMigration(context, preconditions, command)
                    ?: WorkflowWebApplicationResult.unsupported()
            }
        }

    @PostMapping("/migrations")
    fun executeMigration(request: HttpServletRequest): ResponseEntity<WorkflowWebResponse<*>> =
        requests.handle(request) { body, metadata ->
            requests.noQuery(request)
            val command = requests.decode(body, WorkflowMigrationJson::class.java).toCommand()
            runtime.executeWrite(route("executeWorkflowMigration"), metadata) { context, preconditions ->
                ports.operations?.executeMigration(context, preconditions, command)
                    ?: WorkflowWebApplicationResult.unsupported()
            }
        }

    @GetMapping("/migrations/{migrationId}")
    fun migration(
        @PathVariable("migrationId") migrationId: String,
        request: HttpServletRequest,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { _, metadata ->
        requests.noQuery(request)
        val id = requests.resource(migrationId)
        runtime.executeRead(route("getWorkflowMigration"), metadata) { context ->
            ports.operations?.getMigration(context, id)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    @GetMapping("/doctor")
    fun doctor(request: HttpServletRequest): ResponseEntity<WorkflowWebResponse<*>> =
        requests.handle(request) { _, metadata ->
            requests.noQuery(request)
            runtime.executeRead(route("getWorkflowDoctor"), metadata) { context ->
                ports.operations?.doctor(context)
                    ?: WorkflowWebApplicationResult.unsupported()
            }
        }

    private fun definitionLifecycle(
        definitionId: String,
        request: HttpServletRequest,
        operationId: String,
        invocation: (
            ai.icen.fw.workflow.web.api.WorkflowWebTrustedContext,
            ai.icen.fw.workflow.web.api.WorkflowWebResourceId,
            ai.icen.fw.workflow.web.api.WorkflowWebWritePreconditions,
            ai.icen.fw.workflow.web.api.WorkflowDefinitionLifecycleCommand,
        ) -> ai.icen.fw.workflow.web.api.WorkflowWebApplicationResult<ai.icen.fw.workflow.web.api.WorkflowWebCommandReceiptDto>,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { body, metadata ->
        requests.noQuery(request)
        val id = requests.resource(definitionId)
        val command = requests.decode(body, WorkflowLifecycleJson::class.java).toCommand()
        runtime.executeWrite(route(operationId), metadata) { context, preconditions ->
            invocation(context, id, preconditions, command)
        }
    }

    private fun controlInstance(
        instanceId: String,
        request: HttpServletRequest,
        operationId: String,
        operation: WorkflowInstanceControlOperation,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { body, metadata ->
        requests.noQuery(request)
        val id = requests.resource(instanceId)
        val command = requests.decode(body, WorkflowInstanceControlJson::class.java).toCommand()
        runtime.executeWrite(route(operationId), metadata) { context, preconditions ->
            ports.instances?.control(context, id, operation, preconditions, command)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    private fun actOnIncident(
        incidentId: String,
        request: HttpServletRequest,
        operationId: String,
        action: WorkflowIncidentOperation,
    ): ResponseEntity<WorkflowWebResponse<*>> = requests.handle(request) { body, metadata ->
        requests.noQuery(request)
        val id = requests.resource(incidentId)
        val command = requests.decode(body, WorkflowIncidentActionJson::class.java).toCommand()
        runtime.executeWrite(route(operationId), metadata) { context, preconditions ->
            ports.operations?.actOnIncident(context, id, action, preconditions, command)
                ?: WorkflowWebApplicationResult.unsupported()
        }
    }

    companion object {
        private val routes: Map<String, WorkflowWebRoute> = WorkflowWebRoute.all().associateBy { it.operationId }

        private fun route(operationId: String): WorkflowWebRoute =
            requireNotNull(routes[operationId]) { "Unknown Workflow route." }
    }
}
