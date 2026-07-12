package ai.icen.fw.application.doctor

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import java.time.Clock

/** Performs the user-facing authorization check before a system Doctor task is queued. */
class ScheduleDocumentDoctorService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documents: DocumentRepository,
    private val tasks: TaskRepository,
    private val identifiers: IdentifierGenerator,
    private val transaction: ApplicationTransaction,
    private val clock: Clock,
    private val auditTrail: AuditTrail? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun schedule(documentId: Identifier): BackgroundTask {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        authorization.requireDocumentAction(tenant.tenantId, documentId, DOCTOR_ACTION)
        return transaction.execute {
            val document = documents.findById(tenant.tenantId, documentId) ?: throw DocumentNotFoundException(documentId)
            val taskId = identifiers.nextId()
            val task = BackgroundTask(
                id = taskId,
                tenantId = tenant.tenantId,
                type = DocumentDoctorTaskHandler.TASK_TYPE,
                idempotencyKey = "${DocumentDoctorTaskHandler.TASK_TYPE}:${taskId.value}",
                businessId = document.id,
                payload = mapOf("requestedBy" to (operator?.id?.value ?: "SYSTEM")),
                nextAttemptTime = clock.millis(),
            )
            tasks.enqueue(task)
            auditTrail?.record(
                tenantId = tenant.tenantId,
                resourceType = "DOCUMENT",
                resourceId = document.id,
                action = SCHEDULE_AUDIT_ACTION,
                operatorId = operator?.id,
                operatorName = operator?.displayName,
                details = mapOf("taskId" to task.id.value),
            )
            task
        }
    }

    companion object {
        const val DOCTOR_ACTION = "document:doctor"
        const val SCHEDULE_AUDIT_ACTION = "document:doctor:schedule"
    }
}
