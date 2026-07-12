package ai.icen.fw.application.delivery

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogMutationGuard
import ai.icen.fw.application.catalog.DocumentLifecycleMutationGuard
import ai.icen.fw.application.document.DocumentMutationComponents
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.idempotency.IdempotencyReplayMapper
import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.IdempotencyStoreException
import ai.icen.fw.application.idempotency.IdempotentCommand
import ai.icen.fw.application.idempotency.IdempotentCommandResult
import ai.icen.fw.application.idempotency.RequestFingerprint
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.lifecycle.DocumentLifecycleMutationContext
import ai.icen.fw.application.lifecycle.DocumentLifecycleMutationTransaction
import ai.icen.fw.application.outbox.OutboxEventMutationRepository
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.outbox.OutboxEventStatus
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.DocumentConflictException
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import java.time.Clock

enum class DocumentDeliveryRecoveryOperation {
    DELIVERY,
    REMOVAL,
}

/** Stable receipt returned for both a fresh recovery command and its replay. */
class DocumentDeliveryRecoveryReceipt(
    val documentId: Identifier,
    val deliveryId: Identifier,
    val operation: DocumentDeliveryRecoveryOperation,
)

/** Flat, tenant-wide recovery boundary for hosts without a document catalog. */
class IdempotentDocumentDeliveryRecoveryService @JvmOverloads constructor(
    tenants: TenantProvider,
    users: UserRealmProvider,
    authorization: AuthorizationProvider,
    documents: DocumentRepository,
    deliveries: DocumentDeliveryTargetMutationRepository,
    outboxMutations: OutboxEventMutationRepository,
    outbox: OutboxEventRepository,
    identifiers: IdentifierGenerator,
    clock: Clock,
    idempotency: RequestIdempotencyService,
    auditTrail: AuditTrail? = null,
) {
    private val delegate = IdempotentDocumentDeliveryRecoveryDelegate(
        tenants, users, authorization, documents, deliveries, outboxMutations, outbox,
        identifiers, clock, idempotency, auditTrail, null,
    )

    fun retryDelivery(
        documentId: Identifier,
        deliveryId: Identifier,
        idempotencyKey: String,
    ): DocumentDeliveryRecoveryReceipt = delegate.retry(
        documentId, deliveryId, idempotencyKey, DocumentDeliveryRecoveryOperation.DELIVERY,
    )

    fun retryRemoval(
        documentId: Identifier,
        deliveryId: Identifier,
        idempotencyKey: String,
    ): DocumentDeliveryRecoveryReceipt = delegate.retry(
        documentId, deliveryId, idempotencyKey, DocumentDeliveryRecoveryOperation.REMOVAL,
    )
}

/** Catalog-aware recovery boundary that repeats folder ACL checks on every replay. */
class IdempotentDocumentCatalogDeliveryRecoveryService @JvmOverloads constructor(
    tenants: TenantProvider,
    users: UserRealmProvider,
    authorization: AuthorizationProvider,
    documents: DocumentRepository,
    assets: FileAssetRepository,
    deliveries: DocumentDeliveryTargetMutationRepository,
    outboxMutations: OutboxEventMutationRepository,
    outbox: OutboxEventRepository,
    identifiers: IdentifierGenerator,
    transaction: ApplicationTransaction,
    clock: Clock,
    idempotency: RequestIdempotencyService,
    auditTrail: AuditTrail? = null,
    catalogAccess: DocumentCatalogAccessService,
) {
    private val delegate = IdempotentDocumentDeliveryRecoveryDelegate(
        tenants = tenants,
        users = users,
        authorizationProvider = authorization,
        documents = documents,
        deliveries = deliveries,
        outboxMutations = outboxMutations,
        outbox = outbox,
        identifiers = identifiers,
        clock = clock,
        idempotency = idempotency,
        auditTrail = auditTrail,
        guard = DocumentCatalogMutationGuard(
            catalogAccess,
            DocumentMutationComponents(documents, assets, transaction),
        ),
    )

    fun retryDelivery(
        documentId: Identifier,
        deliveryId: Identifier,
        idempotencyKey: String,
    ): DocumentDeliveryRecoveryReceipt = delegate.retry(
        documentId, deliveryId, idempotencyKey, DocumentDeliveryRecoveryOperation.DELIVERY,
    )

    fun retryRemoval(
        documentId: Identifier,
        deliveryId: Identifier,
        idempotencyKey: String,
    ): DocumentDeliveryRecoveryReceipt = delegate.retry(
        documentId, deliveryId, idempotencyKey, DocumentDeliveryRecoveryOperation.REMOVAL,
    )
}

internal class IdempotentDocumentDeliveryRecoveryDelegate(
    private val tenants: TenantProvider,
    users: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documents: DocumentRepository,
    private val deliveries: DocumentDeliveryTargetMutationRepository,
    private val outboxMutations: OutboxEventMutationRepository,
    private val outbox: OutboxEventRepository,
    private val identifiers: IdentifierGenerator,
    private val clock: Clock,
    private val idempotency: RequestIdempotencyService,
    private val auditTrail: AuditTrail?,
    private val guard: DocumentLifecycleMutationGuard?,
) {
    private val authorization = ApplicationAuthorization(users, authorizationProvider)

    fun retry(
        documentId: Identifier,
        deliveryId: Identifier,
        idempotencyKey: String,
        operation: DocumentDeliveryRecoveryOperation,
    ): DocumentDeliveryRecoveryReceipt {
        val tenant = tenants.currentTenant()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, RETRY_PERMISSION)
        val context = DocumentLifecycleMutationContext.prepare(
            tenant.tenantId,
            operator,
            documentId,
            RETRY_PERMISSION,
            guard,
        )
        val request = RequestIdempotency.create(
            tenantId = tenant.tenantId,
            operatorId = operator.id,
            idempotencyKey = idempotencyKey,
            action = operation.action,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = documentId,
            requestFingerprint = operation.fingerprint,
            subresourceId = deliveryId,
        )
        idempotency.findCompleted(request)?.let { stored ->
            return replay(documentId, deliveryId, operation, stored)
        }
        val validated = context.revalidate()
        return idempotency.execute(
            request,
            IdempotencyReplayMapper { stored -> replay(documentId, deliveryId, operation, stored) },
            IdempotentCommand {
                DocumentLifecycleMutationTransaction.execute {
                    val document = documents.findForMutation(tenant.tenantId, documentId)
                        ?: throw DocumentNotFoundException(documentId)
                    validated.verifyLocked(document, RETRY_PERMISSION)
                    val target = deliveries.findForMutation(tenant.tenantId, deliveryId)
                        ?: throw NoSuchElementException("Delivery target was not found in the current document.")
                    if (target.documentId != documentId) {
                        throw NoSuchElementException("Delivery target was not found in the current document.")
                    }
                    if (target.deliveryGeneration != document.deliveryGeneration) {
                        throw DocumentDeliveryRecoveryConflictException()
                    }
                    val currentFence = target.currentDispatchFence
                        ?: throw IdempotencyStoreException("Delivery target is missing its current dispatch fence.")
                    if (currentFence.operation != operation.dispatchOperation) {
                        throw DocumentDeliveryRecoveryConflictException()
                    }
                    val currentEvent = outboxMutations.findForMutation(tenant.tenantId, currentFence.eventId)
                        ?: throw IdempotencyStoreException("Delivery target references a missing current Outbox event.")
                    if (
                        currentEvent.id != currentFence.eventId ||
                        currentEvent.tenantId != tenant.tenantId ||
                        currentEvent.eventType != operation.eventType
                    ) {
                        throw IdempotencyStoreException(
                            "Delivery target current Outbox event identity or type is inconsistent.",
                        )
                    }
                    if (currentEvent.status != OutboxEventStatus.FAILED) {
                        throw DocumentDeliveryRecoveryConflictException()
                    }

                    val newEventId = identifiers.nextId()
                    when (operation) {
                        DocumentDeliveryRecoveryOperation.DELIVERY ->
                            target.recoverDeliveryAfterOutboxFailure(newEventId)
                        DocumentDeliveryRecoveryOperation.REMOVAL ->
                            target.recoverRemovalAfterOutboxFailure(newEventId)
                    }
                    deliveries.save(target)
                    outbox.append(
                        OutboxEvent(
                            id = newEventId,
                            tenantId = tenant.tenantId,
                            type = operation.eventType,
                            payload = mapOf(
                                DocumentDeliveryPlanner.DOCUMENT_ID_PAYLOAD_KEY to documentId.value,
                                DocumentDeliveryPlanner.DELIVERY_ID_PAYLOAD_KEY to deliveryId.value,
                            ),
                            timestamp = nonNegativeNow(),
                        ),
                    )
                    auditTrail?.record(
                        tenantId = tenant.tenantId,
                        resourceType = DOCUMENT_RESOURCE_TYPE,
                        resourceId = documentId,
                        action = operation.action,
                        operatorId = operator.id,
                        operatorName = operator.displayName,
                        details = mapOf(
                            "deliveryId" to deliveryId.value,
                            "targetId" to target.targetId,
                            "operation" to operation.name,
                        ),
                    )
                    val receipt = DocumentDeliveryRecoveryReceipt(documentId, deliveryId, operation)
                    IdempotentCommandResult(
                        receipt,
                        IdempotencyResult(
                            DOCUMENT_RESOURCE_TYPE,
                            documentId,
                            DELIVERY_RESOURCE_TYPE,
                            deliveryId,
                        ),
                    )
                }
            },
        ).value
    }

    private fun replay(
        documentId: Identifier,
        deliveryId: Identifier,
        operation: DocumentDeliveryRecoveryOperation,
        stored: IdempotencyResult,
    ): DocumentDeliveryRecoveryReceipt {
        if (
            stored.resourceType != DOCUMENT_RESOURCE_TYPE ||
            stored.resourceId != documentId ||
            stored.relatedResourceType != DELIVERY_RESOURCE_TYPE ||
            stored.relatedResourceId != deliveryId
        ) {
            throw IdempotencyStoreException("Stored delivery recovery receipt does not match the requested target.")
        }
        return DocumentDeliveryRecoveryReceipt(documentId, deliveryId, operation)
    }

    private fun nonNegativeNow(): Long = clock.millis().also { now ->
        if (now < 0) throw IdempotencyStoreException("System clock returned an invalid delivery recovery timestamp.")
    }

    private val DocumentDeliveryRecoveryOperation.action: String
        get() = when (this) {
            DocumentDeliveryRecoveryOperation.DELIVERY -> DELIVERY_RETRY_ACTION
            DocumentDeliveryRecoveryOperation.REMOVAL -> REMOVAL_RETRY_ACTION
        }

    private val DocumentDeliveryRecoveryOperation.eventType: String
        get() = when (this) {
            DocumentDeliveryRecoveryOperation.DELIVERY -> DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE
            DocumentDeliveryRecoveryOperation.REMOVAL -> DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE
        }

    private val DocumentDeliveryRecoveryOperation.dispatchOperation: DeliveryDispatchOperation
        get() = when (this) {
            DocumentDeliveryRecoveryOperation.DELIVERY -> DeliveryDispatchOperation.DELIVERY
            DocumentDeliveryRecoveryOperation.REMOVAL -> DeliveryDispatchOperation.REMOVAL
        }

    private val DocumentDeliveryRecoveryOperation.fingerprint: String
        get() = when (this) {
            DocumentDeliveryRecoveryOperation.DELIVERY -> DELIVERY_RETRY_FINGERPRINT
            DocumentDeliveryRecoveryOperation.REMOVAL -> REMOVAL_RETRY_FINGERPRINT
        }

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val DELIVERY_RESOURCE_TYPE = "DELIVERY"
        const val RETRY_PERMISSION = "document:delivery:retry"
        const val DELIVERY_RETRY_ACTION = "document:delivery:retry"
        const val REMOVAL_RETRY_ACTION = "document:delivery:removal:retry"
        val DELIVERY_RETRY_FINGERPRINT = RequestFingerprint.sha256("fileweft:delivery:retry:v1")
        val REMOVAL_RETRY_FINGERPRINT = RequestFingerprint.sha256("fileweft:delivery:removal:retry:v1")
    }
}

class DocumentDeliveryRecoveryConflictException @JvmOverloads constructor(
    message: String = "Delivery target is not ready for the requested recovery operation.",
    cause: Throwable? = null,
) : DocumentConflictException(message, cause)
