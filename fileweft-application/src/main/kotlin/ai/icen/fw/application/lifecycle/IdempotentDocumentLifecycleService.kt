package ai.icen.fw.application.lifecycle

import ai.icen.fw.application.archive.ArchiveDocumentService
import ai.icen.fw.application.catalog.DocumentCatalogLifecycleService
import ai.icen.fw.application.catalog.DocumentLifecycleMutationGuard
import ai.icen.fw.application.document.DocumentCommandService
import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.IdempotencyStoreException
import ai.icen.fw.application.idempotency.IdempotentCommand
import ai.icen.fw.application.idempotency.IdempotentCommandResult
import ai.icen.fw.application.idempotency.IdempotencyReplayMapper
import ai.icen.fw.application.idempotency.RequestFingerprint
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.offline.OfflineDocumentService
import ai.icen.fw.application.offline.RestoreOfflineDocumentService
import ai.icen.fw.application.publish.PublishDocumentService
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document

/**
 * Stable receipt for a formal lifecycle command or its durable replay.
 *
 * A fresh review submission carries the first pending task of its workflow in
 * [taskId]; routes with several parallel tasks still return that single first
 * pending task. A durable submit replay stores only the document and workflow
 * identifiers, so its [taskId] is null. Decision receipts always carry the
 * decided task, fresh and replayed alike.
 */
class DocumentLifecycleReceipt @JvmOverloads constructor(
    val documentId: Identifier,
    val workflowId: Identifier? = null,
    val taskId: Identifier? = null,
) {
    init {
        require(taskId == null || workflowId != null) {
            "A lifecycle task receipt requires its workflow identifier."
        }
    }
}

/**
 * Tenant-wide lifecycle boundary for hosts that do not install a catalog.
 * Every method re-runs current authentication/authorization before probing a
 * durable replay and returns only stable identifiers, never a mutable domain
 * aggregate.
 */
class IdempotentDocumentLifecycleService(
    commands: DocumentCommandService,
    publish: PublishDocumentService,
    offline: OfflineDocumentService,
    restore: RestoreOfflineDocumentService,
    archive: ArchiveDocumentService,
    idempotency: RequestIdempotencyService,
) {
    private val delegate = IdempotentDocumentLifecycleDelegate(
        commands,
        publish,
        offline,
        restore,
        archive,
        idempotency,
        null,
    )

    fun revise(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt =
        delegate.revise(documentId, idempotencyKey)

    fun publish(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt =
        delegate.publish(documentId, null, idempotencyKey)

    fun publish(
        documentId: Identifier,
        deliveryProfileId: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.publish(documentId, deliveryProfileId, idempotencyKey)

    fun offline(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt =
        delegate.offline(documentId, idempotencyKey)

    fun restore(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt =
        delegate.restore(documentId, idempotencyKey)

    fun archive(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt =
        delegate.archive(documentId, idempotencyKey)
}

/** Catalog-aware counterpart that preserves the host folder ACL on every replay. */
class IdempotentDocumentCatalogLifecycleService(
    catalogLifecycle: DocumentCatalogLifecycleService,
    idempotency: RequestIdempotencyService,
) {
    private val delegate = catalogLifecycle.createIdempotentDelegate(idempotency)

    fun revise(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt =
        delegate.revise(documentId, idempotencyKey)

    fun publish(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt =
        delegate.publish(documentId, null, idempotencyKey)

    fun publish(
        documentId: Identifier,
        deliveryProfileId: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt = delegate.publish(documentId, deliveryProfileId, idempotencyKey)

    fun offline(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt =
        delegate.offline(documentId, idempotencyKey)

    fun restore(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt =
        delegate.restore(documentId, idempotencyKey)

    fun archive(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt =
        delegate.archive(documentId, idempotencyKey)
}

internal class IdempotentDocumentLifecycleDelegate(
    private val commands: DocumentCommandService,
    private val publish: PublishDocumentService,
    private val offline: OfflineDocumentService,
    private val restore: RestoreOfflineDocumentService,
    private val archive: ArchiveDocumentService,
    private val idempotency: RequestIdempotencyService,
    private val guard: DocumentLifecycleMutationGuard?,
) {
    @JvmSynthetic
    fun revise(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt {
        val context = commands.prepareRevise(documentId, guard)
        return executeSimple(context, idempotencyKey, REVISE_FINGERPRINT) { validated ->
            commands.reviseInCurrentTransaction(validated)
        }
    }

    @JvmSynthetic
    fun publish(
        documentId: Identifier,
        deliveryProfileId: String?,
        idempotencyKey: String,
    ): DocumentLifecycleReceipt {
        val normalizedProfileId = normalizeDeliveryProfileId(deliveryProfileId)
        val context = publish.preparePublish(documentId, guard)
        val request = request(context, idempotencyKey, publishFingerprint(normalizedProfileId))
        idempotency.findCompleted(request)?.let { return replayDocument(context.documentId, it) }
        publish.preflightPublish(context)
        val preparation = publish.prepareDelivery(context, normalizedProfileId)
        val validated = context.revalidate()
        return idempotency.execute(
            request,
            replayMapper(context.documentId),
            IdempotentCommand {
                DocumentLifecycleMutationTransaction.execute {
                    freshDocument(context, publish.publishInCurrentTransaction(validated, preparation))
                }
            },
        ).value
    }

    @JvmSynthetic
    fun offline(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt {
        val context = offline.prepareOffline(documentId, guard)
        return executeSimple(context, idempotencyKey, OFFLINE_FINGERPRINT) { validated ->
            offline.offlineInCurrentTransaction(validated)
        }
    }

    @JvmSynthetic
    fun restore(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt {
        val context = restore.prepareRestore(documentId, guard)
        return executeSimple(context, idempotencyKey, RESTORE_FINGERPRINT) { validated ->
            restore.restoreInCurrentTransaction(validated)
        }
    }

    @JvmSynthetic
    fun archive(documentId: Identifier, idempotencyKey: String): DocumentLifecycleReceipt {
        val context = archive.prepareArchive(documentId, guard)
        return executeSimple(context, idempotencyKey, ARCHIVE_FINGERPRINT) { validated ->
            archive.archiveInCurrentTransaction(validated)
        }
    }

    private fun executeSimple(
        context: DocumentLifecycleMutationContext,
        idempotencyKey: String,
        fingerprint: String,
        mutation: (ValidatedDocumentLifecycleMutation) -> Document,
    ): DocumentLifecycleReceipt {
        val request = request(context, idempotencyKey, fingerprint)
        idempotency.findCompleted(request)?.let { return replayDocument(context.documentId, it) }
        val validated = context.revalidate()
        return idempotency.execute(
            request,
            replayMapper(context.documentId),
            IdempotentCommand {
                DocumentLifecycleMutationTransaction.execute {
                    freshDocument(context, mutation(validated))
                }
            },
        ).value
    }

    private fun request(
        context: DocumentLifecycleMutationContext,
        idempotencyKey: String,
        fingerprint: String,
    ): RequestIdempotency = RequestIdempotency.create(
        tenantId = context.tenantId,
        operatorId = context.operator.id,
        idempotencyKey = idempotencyKey,
        action = context.action,
        resourceType = DOCUMENT_RESOURCE_TYPE,
        resourceId = context.documentId,
        requestFingerprint = fingerprint,
    )

    private fun freshDocument(
        context: DocumentLifecycleMutationContext,
        document: Document,
    ): IdempotentCommandResult<DocumentLifecycleReceipt> {
        if (document.tenantId != context.tenantId || document.id != context.documentId) {
            throw IdempotencyStoreException("Lifecycle mutation returned an unexpected document receipt.")
        }
        val receipt = DocumentLifecycleReceipt(document.id)
        return IdempotentCommandResult(
            receipt,
            IdempotencyResult(DOCUMENT_RESOURCE_TYPE, document.id),
        )
    }

    private fun replayMapper(documentId: Identifier): IdempotencyReplayMapper<DocumentLifecycleReceipt> =
        IdempotencyReplayMapper { result -> replayDocument(documentId, result) }

    private fun replayDocument(
        documentId: Identifier,
        result: IdempotencyResult,
    ): DocumentLifecycleReceipt {
        if (
            result.resourceType != DOCUMENT_RESOURCE_TYPE ||
            result.resourceId != documentId ||
            result.relatedResourceType != null ||
            result.relatedResourceId != null
        ) {
            throw IdempotencyStoreException("Stored lifecycle receipt does not match the requested document.")
        }
        return DocumentLifecycleReceipt(documentId)
    }

    private fun normalizeDeliveryProfileId(deliveryProfileId: String?): String? {
        if (deliveryProfileId == null) return null
        require(deliveryProfileId.length <= MAX_DELIVERY_PROFILE_ID_LENGTH) {
            "Document delivery profile id must not exceed $MAX_DELIVERY_PROFILE_ID_LENGTH characters."
        }
        require(deliveryProfileId.none { character ->
            Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
        }) {
            "Document delivery profile id contains unsafe characters."
        }
        return deliveryProfileId.trim().takeIf { normalized -> normalized.isNotEmpty() }
    }

    private fun publishFingerprint(deliveryProfileId: String?): String =
        if (deliveryProfileId == null) {
            PUBLISH_FINGERPRINT
        } else {
            RequestFingerprint.sha256("fileweft:lifecycle:publish:v2", deliveryProfileId)
        }

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val MAX_DELIVERY_PROFILE_ID_LENGTH = 256
        val REVISE_FINGERPRINT = RequestFingerprint.sha256("fileweft:lifecycle:revise:v1")
        val PUBLISH_FINGERPRINT = RequestFingerprint.sha256("fileweft:lifecycle:publish:v1")
        val OFFLINE_FINGERPRINT = RequestFingerprint.sha256("fileweft:lifecycle:offline:v1")
        val RESTORE_FINGERPRINT = RequestFingerprint.sha256("fileweft:lifecycle:restore:v1")
        val ARCHIVE_FINGERPRINT = RequestFingerprint.sha256("fileweft:lifecycle:archive:v1")
    }
}
