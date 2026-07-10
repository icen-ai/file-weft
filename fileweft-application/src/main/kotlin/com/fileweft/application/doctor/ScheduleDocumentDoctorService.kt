package com.fileweft.application.doctor

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.task.BackgroundTask
import com.fileweft.application.task.TaskRepository
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
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
