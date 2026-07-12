package com.fileweft.web.runtime.v1.doctor

import com.fileweft.application.doctor.DocumentDoctorQueryService
import com.fileweft.application.doctor.DocumentDoctorTaskQueryService
import com.fileweft.application.doctor.DocumentDoctorTaskReceipt
import com.fileweft.application.doctor.DocumentDoctorTaskView
import com.fileweft.application.doctor.IdempotentScheduleDocumentCatalogDoctorService
import com.fileweft.application.doctor.IdempotentScheduleDocumentDoctorService
import com.fileweft.application.doctor.SystemDoctorService
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorReport
import com.fileweft.core.result.DoctorStatus
import com.fileweft.web.api.v1.doctor.DoctorCheckDto
import com.fileweft.web.api.v1.doctor.DoctorReportDto
import com.fileweft.web.api.v1.doctor.DoctorTaskDetailDto
import com.fileweft.web.api.v1.doctor.DoctorTaskDto
import com.fileweft.web.api.v1.doctor.DoctorTaskReceiptDto
import com.fileweft.web.api.v1.doctor.SystemDoctorReportDto
import com.fileweft.web.runtime.v1.IdempotencyKeyParser
import com.fileweft.web.runtime.v1.V1FeatureUnavailableException
import com.fileweft.web.runtime.v1.document.DocumentApiInputs

/**
 * Transport-neutral formal Doctor boundary.
 *
 * Only already-authorized application services are accepted. Raw checker
 * output is never copied into public DTOs: even extension-provided names,
 * reasons, repair text, and evidence can contain credentials or topology.
 */
class DoctorApiFacade private constructor(
    private val documentDoctors: DocumentDoctors?,
    private val taskQueries: TaskQueries?,
    private val schedulers: ScheduleCommands?,
    private val systemDoctors: SystemDoctors?,
) {
    constructor(
        catalogAccessCount: Int,
        documentDoctors: List<DocumentDoctorQueryService>,
        taskQueries: List<DocumentDoctorTaskQueryService>,
        flatSchedulers: List<IdempotentScheduleDocumentDoctorService>,
        catalogSchedulers: List<IdempotentScheduleDocumentCatalogDoctorService>,
        systemDoctors: List<SystemDoctorService>,
    ) : this(
        documentDoctors = resolveSingle(documentDoctors, "document Doctor")?.let { service ->
            ApplicationDocumentDoctors(service)
        },
        taskQueries = resolveSingle(taskQueries, "document Doctor task query")?.let { service ->
            ApplicationTaskQueries(service)
        },
        schedulers = resolveScheduler(catalogAccessCount, flatSchedulers, catalogSchedulers),
        systemDoctors = resolveSingle(systemDoctors, "system Doctor")?.let { service ->
            ApplicationSystemDoctors(service)
        },
    )

    fun inspectDocument(documentId: String): DoctorReportDto {
        val identifier = DocumentApiInputs.documentId(documentId)
        return documentDoctors().inspect(identifier).toDocumentDto(identifier)
    }

    fun scheduleDocument(documentId: String, idempotencyKey: String): DoctorTaskReceiptDto {
        val identifier = DocumentApiInputs.documentId(documentId)
        val key = IdempotencyKeyParser.parse(listOf(idempotencyKey))
        return schedulers().schedule(identifier, key).toDto(identifier)
    }

    fun task(documentId: String, taskId: String): DoctorTaskDetailDto {
        val requestedDocumentId = DocumentApiInputs.documentId(documentId)
        val requestedTaskId = DocumentApiInputs.taskId(taskId)
        return taskQueries().find(requestedDocumentId, requestedTaskId).toDto(requestedDocumentId, requestedTaskId)
    }

    fun inspectSystem(): SystemDoctorReportDto = systemDoctors().inspect().toSystemDto()

    private fun documentDoctors(): DocumentDoctors = documentDoctors ?: throw V1FeatureUnavailableException()

    private fun taskQueries(): TaskQueries = taskQueries ?: throw V1FeatureUnavailableException()

    private fun schedulers(): ScheduleCommands = schedulers ?: throw V1FeatureUnavailableException()

    private fun systemDoctors(): SystemDoctors = systemDoctors ?: throw V1FeatureUnavailableException()

    private fun DoctorReport.toDocumentDto(expectedDocumentId: Identifier): DoctorReportDto {
        check(documentId == expectedDocumentId) {
            "Document Doctor returned a report outside the requested document."
        }
        return DoctorReportDto(
            documentId = expectedDocumentId.value,
            status = status.name,
            checks = checks.toPublicChecks(),
            inspectedTime = inspectedAt,
        )
    }

    private fun DoctorReport.toSystemDto(): SystemDoctorReportDto {
        check(documentId == null) { "System Doctor returned a document-scoped report." }
        return SystemDoctorReportDto(
            status = status.name,
            checks = checks.toPublicChecks(),
            inspectedTime = inspectedAt,
        )
    }

    private fun DocumentDoctorTaskReceipt.toDto(expectedDocumentId: Identifier): DoctorTaskReceiptDto {
        check(documentId == expectedDocumentId) {
            "Document Doctor scheduler returned a receipt outside the requested document."
        }
        return DoctorTaskReceiptDto(taskId.value, documentId.value, status.name)
    }

    private fun DocumentDoctorTaskView.toDto(
        expectedDocumentId: Identifier,
        expectedTaskId: Identifier,
    ): DoctorTaskDetailDto {
        check(documentId == expectedDocumentId && taskId == expectedTaskId) {
            "Document Doctor task query returned a result outside the requested resource."
        }
        val taskDto = DoctorTaskDto(
            id = taskId.value,
            documentId = documentId.value,
            status = status.name,
            createdTime = createdTime,
            updatedTime = updatedTime,
        )
        return DoctorTaskDetailDto(taskDto, report?.toDocumentDto(expectedDocumentId))
    }

    private interface DocumentDoctors {
        fun inspect(documentId: Identifier): DoctorReport
    }

    private interface TaskQueries {
        fun find(documentId: Identifier, taskId: Identifier): DocumentDoctorTaskView
    }

    private interface ScheduleCommands {
        fun schedule(documentId: Identifier, idempotencyKey: String): DocumentDoctorTaskReceipt
    }

    private interface SystemDoctors {
        fun inspect(): DoctorReport
    }

    private class ApplicationDocumentDoctors(
        private val service: DocumentDoctorQueryService,
    ) : DocumentDoctors {
        override fun inspect(documentId: Identifier): DoctorReport = service.inspect(documentId)
    }

    private class ApplicationTaskQueries(
        private val service: DocumentDoctorTaskQueryService,
    ) : TaskQueries {
        override fun find(documentId: Identifier, taskId: Identifier): DocumentDoctorTaskView =
            service.find(documentId, taskId)
    }

    private class FlatScheduleCommands(
        private val service: IdempotentScheduleDocumentDoctorService,
    ) : ScheduleCommands {
        override fun schedule(documentId: Identifier, idempotencyKey: String): DocumentDoctorTaskReceipt =
            service.schedule(documentId, idempotencyKey)
    }

    private class CatalogScheduleCommands(
        private val service: IdempotentScheduleDocumentCatalogDoctorService,
    ) : ScheduleCommands {
        override fun schedule(documentId: Identifier, idempotencyKey: String): DocumentDoctorTaskReceipt =
            service.schedule(documentId, idempotencyKey)
    }

    private class ApplicationSystemDoctors(
        private val service: SystemDoctorService,
    ) : SystemDoctors {
        override fun inspect(): DoctorReport = service.inspect()
    }

    companion object {
        /** Kotlin test seam that cannot become a Java host integration surface. */
        @JvmSynthetic
        internal fun forTesting(
            documentDoctor: ((Identifier) -> DoctorReport)? = null,
            taskQuery: ((Identifier, Identifier) -> DocumentDoctorTaskView)? = null,
            scheduler: ((Identifier, String) -> DocumentDoctorTaskReceipt)? = null,
            systemDoctor: (() -> DoctorReport)? = null,
        ): DoctorApiFacade = DoctorApiFacade(
            documentDoctors = documentDoctor?.let { action -> LambdaDocumentDoctors(action) },
            taskQueries = taskQuery?.let { action -> LambdaTaskQueries(action) },
            schedulers = scheduler?.let { action -> LambdaScheduleCommands(action) },
            systemDoctors = systemDoctor?.let { action -> LambdaSystemDoctors(action) },
        )

        private fun <T> resolveSingle(values: List<T>, label: String): T? {
            require(values.size <= 1) { "Formal Doctor API has multiple $label candidates." }
            return values.singleOrNull()
        }

        private fun resolveScheduler(
            catalogAccessCount: Int,
            flat: List<IdempotentScheduleDocumentDoctorService>,
            catalog: List<IdempotentScheduleDocumentCatalogDoctorService>,
        ): ScheduleCommands? {
            require(catalogAccessCount in 0..1) {
                "Formal Doctor API requires at most one catalog access boundary."
            }
            require(flat.size <= 1) { "Formal Doctor API has multiple flat scheduler candidates." }
            require(catalog.size <= 1) { "Formal Doctor API has multiple catalog scheduler candidates." }
            return when {
                catalogAccessCount == 0 && flat.size == 1 && catalog.isEmpty() -> FlatScheduleCommands(flat.single())
                catalogAccessCount == 1 && flat.isEmpty() && catalog.size == 1 -> CatalogScheduleCommands(catalog.single())
                else -> null
            }
        }

        private class LambdaDocumentDoctors(
            private val action: (Identifier) -> DoctorReport,
        ) : DocumentDoctors {
            override fun inspect(documentId: Identifier): DoctorReport = action(documentId)
        }

        private class LambdaTaskQueries(
            private val action: (Identifier, Identifier) -> DocumentDoctorTaskView,
        ) : TaskQueries {
            override fun find(documentId: Identifier, taskId: Identifier): DocumentDoctorTaskView =
                action(documentId, taskId)
        }

        private class LambdaScheduleCommands(
            private val action: (Identifier, String) -> DocumentDoctorTaskReceipt,
        ) : ScheduleCommands {
            override fun schedule(documentId: Identifier, idempotencyKey: String): DocumentDoctorTaskReceipt =
                action(documentId, idempotencyKey)
        }

        private class LambdaSystemDoctors(
            private val action: () -> DoctorReport,
        ) : SystemDoctors {
            override fun inspect(): DoctorReport = action()
        }
    }
}

private fun List<DoctorCheckResult>.toPublicChecks(): List<DoctorCheckDto> {
    val checksByName = associateBy { check -> check.checkerName }
    val safe = ArrayList<DoctorCheckDto>(PUBLIC_COMPONENTS.size + 1)
    PUBLIC_COMPONENTS.forEach { (internalName, component) ->
        checksByName[internalName]?.let { check -> safe += component.toDto(check.status) }
    }
    val extensionChecks = filter { check -> check.checkerName !in PUBLIC_COMPONENTS }
    if (extensionChecks.isNotEmpty()) {
        safe += EXTENSIONS.toDto(aggregate(extensionChecks.map { check -> check.status }))
    }
    return safe
}

private fun PublicDoctorComponent.toDto(status: DoctorStatus): DoctorCheckDto = DoctorCheckDto(
    checkerName = id,
    status = status.name,
    reason = when (status) {
        DoctorStatus.HEALTHY -> "$label check passed."
        DoctorStatus.WARNING -> "$label check requires attention."
        DoctorStatus.ERROR -> "$label check failed."
        DoctorStatus.SKIPPED -> "$label check was skipped."
    },
    repairSuggestion = when (status) {
        DoctorStatus.WARNING,
        DoctorStatus.ERROR,
        -> "Review authorized operational logs, repair the affected component, and run Doctor again."
        DoctorStatus.HEALTHY,
        DoctorStatus.SKIPPED,
        -> null
    },
)

private fun aggregate(statuses: List<DoctorStatus>): DoctorStatus = when {
    statuses.any { status -> status == DoctorStatus.ERROR } -> DoctorStatus.ERROR
    statuses.any { status -> status == DoctorStatus.WARNING } -> DoctorStatus.WARNING
    statuses.any { status -> status == DoctorStatus.HEALTHY } -> DoctorStatus.HEALTHY
    else -> DoctorStatus.SKIPPED
}

private class PublicDoctorComponent(val id: String, val label: String)

private val PUBLIC_COMPONENTS: Map<String, PublicDoctorComponent> = linkedMapOf(
    "permission" to PublicDoctorComponent("permission", "Permission"),
    "lifecycle" to PublicDoctorComponent("lifecycle", "Lifecycle"),
    "workflow" to PublicDoctorComponent("workflow", "Workflow"),
    "storage" to PublicDoctorComponent("storage", "Storage"),
    "catalog" to PublicDoctorComponent("catalog", "Catalog"),
    "delivery-profile" to PublicDoctorComponent("delivery-profile", "Delivery profile"),
    "connector" to PublicDoctorComponent("connector", "Connector"),
    "agent" to PublicDoctorComponent("agent", "Agent"),
    "doctor-task" to PublicDoctorComponent("task", "Doctor task"),
)

private val EXTENSIONS = PublicDoctorComponent("extensions", "Extension")
