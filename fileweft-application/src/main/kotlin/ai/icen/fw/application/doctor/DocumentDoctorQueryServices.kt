package ai.icen.fw.application.doctor

import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.task.BackgroundTaskStatus
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorReport
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

/** Internal projection of one durable document Doctor task and its optional terminal report. */
class DocumentDoctorTaskView @JvmOverloads constructor(
    val tenantId: Identifier,
    val taskId: Identifier,
    val documentId: Identifier,
    val status: BackgroundTaskStatus,
    val createdTime: Long,
    val updatedTime: Long,
    val report: DoctorReport? = null,
) {
    init {
        require(createdTime >= 0) { "Doctor task creation time must not be negative." }
        require(updatedTime >= createdTime) { "Doctor task update time must not precede creation time." }
        report?.let { current ->
            require(current.tenantId == tenantId && current.documentId == documentId) {
                "Doctor task report must belong to the task tenant and document."
            }
            require(status == BackgroundTaskStatus.SUCCESS) {
                "Only a successfully acknowledged Doctor task may expose a report."
            }
        }
    }
}

/** Tenant/document/type scoped read port for asynchronous Doctor state. */
interface DocumentDoctorTaskQueryRepository {
    fun findTask(
        tenantId: Identifier,
        documentId: Identifier,
        taskId: Identifier,
        folderReadScope: DocumentFolderReadScope? = null,
    ): DocumentDoctorTaskView?
}

/**
 * Formal document diagnosis boundary. Authorization and catalog visibility are
 * checked both before and after potentially remote technical checkers run.
 */
class DocumentDoctorQueryService @JvmOverloads constructor(
    private val tenants: TenantProvider,
    users: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documents: DocumentQueryRepository,
    private val transaction: ApplicationTransaction,
    private val doctor: DoctorApplicationService,
    private val folderReadAccess: DocumentFolderReadAccess? = null,
) {
    private val authorization = ApplicationAuthorization(users, authorizationProvider)

    fun inspect(documentId: Identifier): DoctorReport {
        val tenant = tenants.currentTenant()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, DOCTOR_ACTION)
        authorization.requireDocumentActionAs(tenant.tenantId, documentId, READ_ACTION, operator)
        requireVisible(tenant.tenantId, documentId)

        val report = doctor.inspectAuthorizedDocument(tenant.tenantId, documentId)
        check(report.tenantId == tenant.tenantId && report.documentId == documentId) {
            "Doctor engine returned a report outside the authorized document scope."
        }

        authorization.requireDocumentActionAs(tenant.tenantId, documentId, DOCTOR_ACTION, operator)
        authorization.requireDocumentActionAs(tenant.tenantId, documentId, READ_ACTION, operator)
        requireVisible(tenant.tenantId, documentId)
        return report
    }

    private fun requireVisible(tenantId: Identifier, documentId: Identifier) {
        val scope = readableFolderScope()
        if (scope?.isEmpty == true) throw DocumentNotFoundException(documentId)
        val visible = transaction.execute { documents.findDetail(tenantId, documentId, scope) }
        if (visible == null || visible.document.id != documentId) throw DocumentNotFoundException(documentId)
    }

    private fun readableFolderScope(): DocumentFolderReadScope? =
        folderReadAccess?.readableFolderIds()?.let(::DocumentFolderReadScope)

    companion object {
        const val DOCTOR_ACTION = "document:doctor"
        const val READ_ACTION = "document:read"
    }
}

/** Secure polling boundary for one asynchronous document diagnosis. */
class DocumentDoctorTaskQueryService @JvmOverloads constructor(
    private val tenants: TenantProvider,
    users: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val queries: DocumentDoctorTaskQueryRepository,
    private val transaction: ApplicationTransaction,
    private val folderReadAccess: DocumentFolderReadAccess? = null,
) {
    private val authorization = ApplicationAuthorization(users, authorizationProvider)

    fun find(documentId: Identifier, taskId: Identifier): DocumentDoctorTaskView {
        val tenant = tenants.currentTenant()
        val operator = authorization.requireDocumentAction(
            tenant.tenantId,
            documentId,
            DocumentDoctorQueryService.DOCTOR_ACTION,
        )
        authorization.requireDocumentActionAs(
            tenant.tenantId,
            documentId,
            DocumentDoctorQueryService.READ_ACTION,
            operator,
        )
        val scope = folderReadAccess?.readableFolderIds()?.let(::DocumentFolderReadScope)
        authorization.requireDocumentActionAs(
            tenant.tenantId,
            documentId,
            DocumentDoctorQueryService.DOCTOR_ACTION,
            operator,
        )
        authorization.requireDocumentActionAs(
            tenant.tenantId,
            documentId,
            DocumentDoctorQueryService.READ_ACTION,
            operator,
        )
        if (scope?.isEmpty == true) throw DocumentNotFoundException(documentId)
        val result = transaction.execute {
            queries.findTask(tenant.tenantId, documentId, taskId, scope)
                ?: throw DocumentNotFoundException(documentId)
        }
        if (
            result.tenantId != tenant.tenantId ||
            result.documentId != documentId ||
            result.taskId != taskId
        ) {
            throw DocumentNotFoundException(documentId)
        }
        return result
    }
}

/** Tenant-scoped system Doctor protected by a distinct platform permission. */
class SystemDoctorService(
    private val tenants: TenantProvider,
    users: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val doctor: DoctorApplicationService,
) {
    private val authorization = ApplicationAuthorization(users, authorizationProvider)

    fun inspect(): DoctorReport {
        val tenant = tenants.currentTenant()
        val operator = authorization.requireAction(
            tenant.tenantId,
            SYSTEM_RESOURCE_ID,
            SYSTEM_RESOURCE_TYPE,
            SYSTEM_DOCTOR_ACTION,
        )
        val report = doctor.inspectAuthorizedSystem(tenant.tenantId)
        check(report.tenantId == tenant.tenantId && report.documentId == null) {
            "Doctor engine returned a report outside the authorized system scope."
        }
        authorization.requireActionAs(
            tenant.tenantId,
            SYSTEM_RESOURCE_ID,
            SYSTEM_RESOURCE_TYPE,
            SYSTEM_DOCTOR_ACTION,
            operator,
        )
        return report
    }

    companion object {
        const val SYSTEM_DOCTOR_ACTION = "system:doctor:read"
        const val SYSTEM_RESOURCE_TYPE = "FILEWEFT_SYSTEM"
        val SYSTEM_RESOURCE_ID: Identifier = Identifier("fileweft-system")
    }
}
