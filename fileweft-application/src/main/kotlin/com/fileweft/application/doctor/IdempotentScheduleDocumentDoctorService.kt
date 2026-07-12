package com.fileweft.application.doctor

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.catalog.DocumentCatalogAccessService
import com.fileweft.application.catalog.DocumentCatalogMutationGuard
import com.fileweft.application.catalog.DocumentLifecycleMutationGuard
import com.fileweft.application.document.DocumentMutationComponents
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.idempotency.IdempotencyReplayMapper
import com.fileweft.application.idempotency.IdempotencyResult
import com.fileweft.application.idempotency.IdempotencyStoreException
import com.fileweft.application.idempotency.IdempotentCommand
import com.fileweft.application.idempotency.IdempotentCommandResult
import com.fileweft.application.idempotency.RequestFingerprint
import com.fileweft.application.idempotency.RequestIdempotency
import com.fileweft.application.idempotency.RequestIdempotencyService
import com.fileweft.application.lifecycle.DocumentLifecycleMutationContext
import com.fileweft.application.lifecycle.DocumentLifecycleMutationTransaction
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.task.BackgroundTask
import com.fileweft.application.task.BackgroundTaskStatus
import com.fileweft.application.task.TaskRepository
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import java.time.Clock

/** Stable acknowledgement returned for a fresh Doctor request and every replay. */
class DocumentDoctorTaskReceipt @JvmOverloads constructor(
    val taskId: Identifier,
    val documentId: Identifier,
    val status: BackgroundTaskStatus = BackgroundTaskStatus.PENDING,
) {
    init {
        require(status == BackgroundTaskStatus.PENDING) {
            "A Doctor scheduling receipt must describe a pending task."
        }
    }
}

/** Tenant-wide boundary for hosts that do not expose a document catalog. */
class IdempotentScheduleDocumentDoctorService @JvmOverloads constructor(
    tenants: TenantProvider,
    users: UserRealmProvider,
    authorization: AuthorizationProvider,
    documents: DocumentRepository,
    tasks: TaskRepository,
    identifiers: IdentifierGenerator,
    clock: Clock,
    idempotency: RequestIdempotencyService,
    auditTrail: AuditTrail? = null,
) {
    private val delegate = IdempotentScheduleDocumentDoctorDelegate(
        tenants = tenants,
        users = users,
        authorizationProvider = authorization,
        documents = documents,
        tasks = tasks,
        identifiers = identifiers,
        clock = clock,
        idempotency = idempotency,
        auditTrail = auditTrail,
        guard = null,
    )

    fun schedule(documentId: Identifier, idempotencyKey: String): DocumentDoctorTaskReceipt =
        delegate.schedule(documentId, idempotencyKey)
}

/** Catalog-aware boundary that repeats folder ACL and binding checks for each request and replay. */
class IdempotentScheduleDocumentCatalogDoctorService @JvmOverloads constructor(
    tenants: TenantProvider,
    users: UserRealmProvider,
    authorization: AuthorizationProvider,
    documents: DocumentRepository,
    assets: FileAssetRepository,
    tasks: TaskRepository,
    identifiers: IdentifierGenerator,
    transaction: ApplicationTransaction,
    clock: Clock,
    idempotency: RequestIdempotencyService,
    auditTrail: AuditTrail? = null,
    catalogAccess: DocumentCatalogAccessService,
) {
    private val delegate = IdempotentScheduleDocumentDoctorDelegate(
        tenants = tenants,
        users = users,
        authorizationProvider = authorization,
        documents = documents,
        tasks = tasks,
        identifiers = identifiers,
        clock = clock,
        idempotency = idempotency,
        auditTrail = auditTrail,
        guard = DocumentCatalogMutationGuard(
            catalogAccess,
            DocumentMutationComponents(documents, assets, transaction),
        ),
    )

    fun schedule(documentId: Identifier, idempotencyKey: String): DocumentDoctorTaskReceipt =
        delegate.schedule(documentId, idempotencyKey)
}

internal class IdempotentScheduleDocumentDoctorDelegate(
    private val tenants: TenantProvider,
    users: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documents: DocumentRepository,
    private val tasks: TaskRepository,
    private val identifiers: IdentifierGenerator,
    private val clock: Clock,
    private val idempotency: RequestIdempotencyService,
    private val auditTrail: AuditTrail?,
    private val guard: DocumentLifecycleMutationGuard?,
) {
    private val authorization = ApplicationAuthorization(users, authorizationProvider)

    fun schedule(documentId: Identifier, idempotencyKey: String): DocumentDoctorTaskReceipt {
        val tenant = tenants.currentTenant()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, DOCTOR_ACTION)
        authorization.requireDocumentActionAs(tenant.tenantId, documentId, READ_ACTION, operator)
        val context = DocumentLifecycleMutationContext.prepare(
            tenantId = tenant.tenantId,
            operator = operator,
            documentId = documentId,
            action = DOCTOR_ACTION,
            guard = guard,
        )
        val request = RequestIdempotency.create(
            tenantId = tenant.tenantId,
            operatorId = operator.id,
            idempotencyKey = idempotencyKey,
            action = SCHEDULE_ACTION,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = documentId,
            requestFingerprint = SCHEDULE_FINGERPRINT,
        )
        reauthorize(tenant.tenantId, documentId, operator)
        idempotency.findCompleted(request)?.let { stored -> return replay(documentId, stored) }

        val validated = context.revalidate()
        reauthorize(tenant.tenantId, documentId, operator)
        return idempotency.execute(
            request = request,
            replayMapper = IdempotencyReplayMapper { stored -> replay(documentId, stored) },
            command = IdempotentCommand {
                DocumentLifecycleMutationTransaction.execute {
                    val document = documents.findForMutation(tenant.tenantId, documentId)
                        ?: throw DocumentNotFoundException(documentId)
                    if (document.tenantId != tenant.tenantId || document.id != documentId) {
                        throw DocumentNotFoundException(documentId)
                    }
                    validated.verifyLocked(document, DOCTOR_ACTION)

                    val taskId = identifiers.nextId()
                    val task = BackgroundTask(
                        id = taskId,
                        tenantId = tenant.tenantId,
                        type = DocumentDoctorTaskHandler.TASK_TYPE,
                        idempotencyKey = "${DocumentDoctorTaskHandler.TASK_TYPE}:${taskId.value}",
                        businessId = documentId,
                        payload = mapOf(REQUESTED_BY_PAYLOAD_KEY to operator.id.value),
                        nextAttemptTime = nonNegativeNow(),
                    )
                    tasks.enqueue(task)
                    requirePersistedTask(task)
                    auditTrail?.record(
                        tenantId = tenant.tenantId,
                        resourceType = DOCUMENT_RESOURCE_TYPE,
                        resourceId = documentId,
                        action = SCHEDULE_ACTION,
                        operatorId = operator.id,
                        operatorName = operator.displayName,
                        details = mapOf(TASK_ID_DETAIL_KEY to taskId.value),
                    )
                    val receipt = DocumentDoctorTaskReceipt(taskId, documentId)
                    IdempotentCommandResult(
                        receipt,
                        IdempotencyResult(
                            DOCUMENT_RESOURCE_TYPE,
                            documentId,
                            DOCTOR_TASK_RESOURCE_TYPE,
                            taskId,
                        ),
                    )
                }
            },
        ).value
    }

    private fun reauthorize(
        tenantId: Identifier,
        documentId: Identifier,
        operator: UserIdentity,
    ) {
        authorization.requireDocumentActionAs(tenantId, documentId, DOCTOR_ACTION, operator)
        authorization.requireDocumentActionAs(tenantId, documentId, READ_ACTION, operator)
    }

    private fun replay(documentId: Identifier, stored: IdempotencyResult): DocumentDoctorTaskReceipt {
        if (
            stored.resourceType != DOCUMENT_RESOURCE_TYPE ||
            stored.resourceId != documentId ||
            stored.relatedResourceType != DOCTOR_TASK_RESOURCE_TYPE ||
            stored.relatedResourceId == null
        ) {
            throw IdempotencyStoreException("Stored Doctor task receipt does not match the requested document.")
        }
        return DocumentDoctorTaskReceipt(stored.relatedResourceId, documentId)
    }

    /**
     * [TaskRepository.enqueue] is deliberately idempotent and may report a
     * duplicate as a no-op. A newly claimed HTTP idempotency request must not
     * be completed until the exact task is visible in the same local
     * transaction; otherwise an identifier/idempotency collision could create
     * a durable receipt that points at unrelated or missing work.
     */
    private fun requirePersistedTask(expected: BackgroundTask) {
        val persisted = tasks.findById(expected.tenantId, expected.id)
        if (
            persisted == null ||
            persisted.id != expected.id ||
            persisted.tenantId != expected.tenantId ||
            persisted.type != expected.type ||
            persisted.idempotencyKey != expected.idempotencyKey ||
            persisted.businessId != expected.businessId ||
            persisted.payload != expected.payload ||
            persisted.status != expected.status ||
            persisted.retryCount != expected.retryCount ||
            persisted.nextAttemptTime != expected.nextAttemptTime ||
            persisted.lastError != expected.lastError
        ) {
            throw IdempotencyStoreException(
                "Doctor task enqueue could not be verified in the current tenant transaction.",
            )
        }
    }

    private fun nonNegativeNow(): Long = clock.millis().also { now ->
        if (now < 0) throw IdempotencyStoreException("System clock returned an invalid Doctor task timestamp.")
    }

    private companion object {
        const val DOCTOR_ACTION = "document:doctor"
        const val READ_ACTION = "document:read"
        const val SCHEDULE_ACTION = ScheduleDocumentDoctorService.SCHEDULE_AUDIT_ACTION
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val DOCTOR_TASK_RESOURCE_TYPE = "DOCTOR_TASK"
        const val REQUESTED_BY_PAYLOAD_KEY = "requestedBy"
        const val TASK_ID_DETAIL_KEY = "taskId"
        val SCHEDULE_FINGERPRINT = RequestFingerprint.sha256("fileweft:document:doctor:schedule:v1")
    }
}
