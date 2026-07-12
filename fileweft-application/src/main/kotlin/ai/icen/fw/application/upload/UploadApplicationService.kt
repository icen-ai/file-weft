package ai.icen.fw.application.upload

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionBoundary
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import java.io.InputStream
import java.time.Clock

class UploadApplicationService(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val storageAdapter: StorageAdapter,
    private val fileObjectRepository: FileObjectRepository,
    private val fileAssetRepository: FileAssetRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val transaction: ApplicationTransaction,
    private val clock: Clock,
    private val metrics: FileWeftMetrics? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun upload(command: UploadFileCommand, content: InputStream): UploadFileResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        val tenant = tenantProvider.currentTenant()
        val fileObjectId = identifierGenerator.nextId()
        val fileAssetId = identifierGenerator.nextId()
        authorization.requireAction(tenant.tenantId, fileObjectId, "FILE_OBJECT", "file:upload")

        var storedObject: StoredObject? = null
        var attemptedResult: UploadFileResult? = null
        try {
            val request = StorageUploadRequest(
                tenantId = tenant.tenantId,
                objectName = command.fileName,
                contentLength = command.contentLength,
                contentType = command.contentType,
                contentHash = command.contentHash,
                metadata = command.metadata,
            )
            val stored = storageAdapter.upload(request, content)
            storedObject = stored
            StoredObjectIntegrity.requireMatches(request, stored)
            val fileObject = FileObject(
                id = fileObjectId,
                tenantId = tenant.tenantId,
                fileName = command.fileName,
                contentLength = stored.contentLength,
                storageType = stored.location.storageType,
                storagePath = stored.location.path,
                contentType = stored.contentType,
                contentHash = stored.contentHash,
            )
            val fileAsset = FileAsset(
                id = fileAssetId,
                tenantId = tenant.tenantId,
                fileObjectId = fileObject.id,
                assetType = command.assetType,
                metadata = command.metadata,
            )
            val attempted = UploadFileResult(fileObject, fileAsset)
            attemptedResult = attempted
            val result = transaction.execute {
                fileObjectRepository.save(fileObject)
                fileAssetRepository.save(fileAsset)
                outboxEventRepository.append(
                    OutboxEvent(
                        id = identifierGenerator.nextId(),
                        tenantId = tenant.tenantId,
                        type = "file.uploaded",
                        payload = mapOf("fileObjectId" to fileObject.id.value, "fileAssetId" to fileAsset.id.value),
                        timestamp = clock.millis(),
                    ),
                )
                attempted
            }
            recordMetric(FileWeftMetric.UPLOAD_COUNT, tenant.tenantId.value)
            return result
        } catch (failure: Throwable) {
            try {
                storedObject?.let { stored ->
                    attemptedResult?.let { attempted ->
                        reconcileFailedPersistence(attempted, failure)?.let { recovered ->
                            recordMetric(FileWeftMetric.UPLOAD_COUNT, tenant.tenantId.value)
                            return recovered
                        }
                    }
                    compensate(stored, failure)
                }
            } catch (reconciledFailure: Throwable) {
                recordMetric(FileWeftMetric.UPLOAD_FAILURE, tenant.tenantId.value)
                throw reconciledFailure
            }
            recordMetric(FileWeftMetric.UPLOAD_FAILURE, tenant.tenantId.value)
            throw failure
        }
    }

    /**
     * Re-reads both durable storage references after a failed transaction.
     *
     * A commit acknowledgement can be lost after the database has committed. In
     * that case an exact aggregate is success, while any unknown, unreadable,
     * partial or conflicting state must retain the remote object for an operator
     * to reconcile. Compensation is allowed only for a known transaction failure
     * followed by an authoritative read proving that neither generated id exists.
     */
    private fun reconcileFailedPersistence(
        attempted: UploadFileResult,
        failure: Throwable,
    ): UploadFileResult? {
        val persisted = try {
            transaction.execute {
                UploadPersistenceSnapshot(
                    fileObject = fileObjectRepository.findById(
                        attempted.fileObject.tenantId,
                        attempted.fileObject.id,
                    ),
                    fileAsset = fileAssetRepository.findById(
                        attempted.fileAsset.tenantId,
                        attempted.fileAsset.id,
                    ),
                )
            }
        } catch (reconciliationFailure: Throwable) {
            throw outcomeUnknown(failure, reconciliationFailure)
        }

        val exactBinding =
            sameFileObject(persisted.fileObject, attempted.fileObject) &&
            sameFileAsset(persisted.fileAsset, attempted.fileAsset)
        if (failure is ApplicationTransactionOutcomeUnknownException) {
            if (exactBinding) {
                return UploadFileResult(checkNotNull(persisted.fileObject), checkNotNull(persisted.fileAsset))
            }
            if (persisted.fileObject == null && persisted.fileAsset == null) throw failure
            throw outcomeUnknown(failure, IllegalStateException(RECONCILIATION_MISMATCH_MESSAGE))
        }
        if (persisted.fileObject != null || persisted.fileAsset != null) {
            throw outcomeUnknown(failure, IllegalStateException(RECONCILIATION_MISMATCH_MESSAGE))
        }
        return null
    }

    private fun compensate(stored: StoredObject, failure: Throwable) {
        try {
            storageAdapter.delete(stored.location)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun outcomeUnknown(
        failure: Throwable,
        reconciliationFailure: Throwable,
    ): ApplicationTransactionOutcomeUnknownException {
        val unknown = failure as? ApplicationTransactionOutcomeUnknownException
            ?: ApplicationTransactionOutcomeUnknownException(failure)
        if (reconciliationFailure !== unknown && reconciliationFailure !== unknown.cause) {
            unknown.addSuppressed(reconciliationFailure)
        }
        return unknown
    }

    private fun sameFileObject(actual: FileObject?, expected: FileObject): Boolean =
        actual != null &&
            actual.id == expected.id &&
            actual.tenantId == expected.tenantId &&
            actual.fileName == expected.fileName &&
            actual.contentLength == expected.contentLength &&
            actual.storageType == expected.storageType &&
            actual.storagePath == expected.storagePath &&
            actual.contentType == expected.contentType &&
            actual.contentHash == expected.contentHash

    private fun sameFileAsset(actual: FileAsset?, expected: FileAsset): Boolean =
        actual != null &&
            actual.id == expected.id &&
            actual.tenantId == expected.tenantId &&
            actual.fileObjectId == expected.fileObjectId &&
            actual.assetType == expected.assetType &&
            actual.metadata == expected.metadata

    private fun recordMetric(metric: FileWeftMetric, tenantId: String) {
        try {
            metrics?.increment(metric, mapOf("tenantId" to tenantId))
        } catch (_: Exception) {
            // Metrics are intentionally non-blocking for business execution.
        }
    }

    private data class UploadPersistenceSnapshot(
        val fileObject: FileObject?,
        val fileAsset: FileAsset?,
    )

    private companion object {
        const val RECONCILIATION_MISMATCH_MESSAGE: String =
            "Persisted upload state is partial or inconsistent and requires reconciliation."
    }
}
