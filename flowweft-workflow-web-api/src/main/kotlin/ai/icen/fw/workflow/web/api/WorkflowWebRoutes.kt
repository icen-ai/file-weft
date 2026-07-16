package ai.icen.fw.workflow.web.api

/** Versioned route catalog shared by controller adapters, documentation and capability discovery. */
class WorkflowWebRoute private constructor(
    method: String,
    pathTemplate: String,
    operationId: String,
    capabilityId: String,
    val idempotencyRequired: Boolean,
    val ifMatchRequired: Boolean,
) {
    val method: String = requiredCode(method, "Workflow route method", 16)
    val pathTemplate: String = requiredText(pathTemplate, "Workflow route path", 512)
    val operationId: String = requiredText(operationId, "Workflow route operation id", 128)
    val capabilityId: String = requiredText(capabilityId, "Workflow route capability id", 128)

    init {
        require(pathTemplate.startsWith(BASE_PATH + "/")) { "Workflow routes must remain under $BASE_PATH." }
        require(idempotencyRequired == ifMatchRequired) {
            "Every Workflow mutation requires both Idempotency-Key and If-Match."
        }
        require((method == "GET") != idempotencyRequired) {
            "Workflow GET routes cannot require mutation headers and mutations must require them."
        }
    }

    companion object {
        const val BASE_PATH: String = "/flowweft/v1"
        const val IDEMPOTENCY_HEADER: String = "Idempotency-Key"
        const val IF_MATCH_HEADER: String = "If-Match"

        private fun read(path: String, operation: String, capability: String): WorkflowWebRoute =
            WorkflowWebRoute("GET", path, operation, capability, false, false)

        private fun write(path: String, operation: String, capability: String): WorkflowWebRoute =
            WorkflowWebRoute("POST", path, operation, capability, true, true)

        @JvmStatic
        fun all(): List<WorkflowWebRoute> = ROUTES

        private val ROUTES: List<WorkflowWebRoute> = immutableList(
            listOf(
                read("$BASE_PATH/workflows/definitions", "listWorkflowDefinitions", "workflow.definition.read"),
                read("$BASE_PATH/workflows/capabilities", "listWorkflowCapabilities", "workflow.capability.read"),
                read("$BASE_PATH/workflows/definitions/{definitionId}", "getWorkflowDefinition", "workflow.definition.read"),
                WorkflowWebRoute("PUT", "$BASE_PATH/workflows/definitions/{definitionId}/draft", "putWorkflowDefinitionDraft", "workflow.definition.draft", true, true),
                write("$BASE_PATH/workflows/definitions/{definitionId}/publish", "publishWorkflowDefinition", "workflow.definition.publish"),
                write("$BASE_PATH/workflows/definitions/{definitionId}/retire", "retireWorkflowDefinition", "workflow.definition.retire"),
                write("$BASE_PATH/workflows/instances", "startWorkflowInstance", "workflow.instance.start"),
                read("$BASE_PATH/workflows/instances/{instanceId}", "getWorkflowInstance", "workflow.instance.read"),
                write("$BASE_PATH/workflows/instances/{instanceId}/suspend", "suspendWorkflowInstance", "workflow.instance.suspend"),
                write("$BASE_PATH/workflows/instances/{instanceId}/resume", "resumeWorkflowInstance", "workflow.instance.resume"),
                write("$BASE_PATH/workflows/instances/{instanceId}/cancel", "cancelWorkflowInstance", "workflow.instance.cancel"),
                write("$BASE_PATH/workflows/instances/{instanceId}/terminate", "terminateWorkflowInstance", "workflow.instance.terminate"),
                read("$BASE_PATH/workflows/tasks", "listWorkflowTasks", "workflow.task.read"),
                read("$BASE_PATH/workflows/tasks/{taskId}", "getWorkflowTask", "workflow.task.read"),
                write("$BASE_PATH/workflows/tasks/{taskId}/claim", "claimWorkflowTask", "workflow.task.claim"),
                write("$BASE_PATH/workflows/tasks/{taskId}/decisions", "decideWorkflowTask", "workflow.task.decision"),
                write("$BASE_PATH/workflows/tasks/{taskId}/delegate", "delegateWorkflowTask", "workflow.task.delegate"),
                write("$BASE_PATH/workflows/tasks/{taskId}/add-sign", "addWorkflowTaskSigner", "workflow.task.add-sign"),
                write("$BASE_PATH/workflows/tasks/{taskId}/return", "returnWorkflowTask", "workflow.task.return"),
                read("$BASE_PATH/workflows/tasks/{taskId}/form", "getWorkflowTaskForm", "workflow.form.read"),
                write("$BASE_PATH/workflows/tasks/{taskId}/form-submissions", "submitWorkflowTaskForm", "workflow.form.submit"),
                read("$BASE_PATH/workflows/instances/{instanceId}/comments", "listWorkflowComments", "workflow.comment.read"),
                write("$BASE_PATH/workflows/instances/{instanceId}/comments", "createWorkflowComment", "workflow.comment.create"),
                read("$BASE_PATH/workflows/instances/{instanceId}/history", "listWorkflowHistory", "workflow.history.read"),
                read("$BASE_PATH/workflows/incidents", "listWorkflowIncidents", "workflow.incident.read"),
                read("$BASE_PATH/workflows/incidents/{incidentId}", "getWorkflowIncident", "workflow.incident.read"),
                write("$BASE_PATH/workflows/incidents/{incidentId}/retry", "retryWorkflowIncident", "workflow.incident.retry"),
                write("$BASE_PATH/workflows/incidents/{incidentId}/skip", "skipWorkflowIncident", "workflow.incident.skip"),
                write("$BASE_PATH/workflows/incidents/{incidentId}/repair", "repairWorkflowIncident", "workflow.incident.repair"),
                write("$BASE_PATH/workflows/migrations/dry-run", "dryRunWorkflowMigration", "workflow.migration.dry-run"),
                write("$BASE_PATH/workflows/migrations", "executeWorkflowMigration", "workflow.migration.execute"),
                read("$BASE_PATH/workflows/migrations/{migrationId}", "getWorkflowMigration", "workflow.migration.read"),
                read("$BASE_PATH/workflows/doctor", "getWorkflowDoctor", "workflow.doctor.read"),
            ),
            "Workflow routes",
            64,
        ).also { routes ->
            require(routes.map { it.operationId }.toSet().size == routes.size) {
                "Workflow route operation identifiers must be unique."
            }
            require(routes.map { it.method + " " + it.pathTemplate }.toSet().size == routes.size) {
                "Workflow route method/path pairs must be unique."
            }
        }
    }
}
