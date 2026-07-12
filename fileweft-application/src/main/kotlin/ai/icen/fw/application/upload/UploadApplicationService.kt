package ai.icen.fw.application.upload

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
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
        val tenant = tenantProvider.currentTenant()
        val fileObjectId = identifierGenerator.nextId()
        val fileAssetId = identifierGenerator.nextId()
        authorization.requireAction(tenant.tenantId, fileObjectId, "FILE_OBJECT", "file:upload")

        var storedObject: StoredObject? = null
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
            val result = transaction.execute {
                val fileObject = FileObject(
                    id = fileObjectId,
                    tenantId = tenant.tenantId,
                    fileName = command.fileName,
                    contentLength = storedObject.contentLength,
                    storageType = storedObject.location.storageType,
                    storagePath = storedObject.location.path,
                    contentType = storedObject.contentType,
                    contentHash = storedObject.contentHash,
                )
                val fileAsset = FileAsset(
                    id = fileAssetId,
                    tenantId = tenant.tenantId,
                    fileObjectId = fileObject.id,
                    assetType = command.assetType,
                    metadata = command.metadata,
                )
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
                UploadFileResult(fileObject, fileAsset)
            }
            recordMetric(FileWeftMetric.UPLOAD_COUNT, tenant.tenantId.value)
            return result
        } catch (failure: Throwable) {
            storedObject?.let { stored ->
                try {
                    storageAdapter.delete(stored.location)
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            recordMetric(FileWeftMetric.UPLOAD_FAILURE, tenant.tenantId.value)
            throw failure
        }
    }

    private fun recordMetric(metric: FileWeftMetric, tenantId: String) {
        try {
            metrics?.increment(metric, mapOf("tenantId" to tenantId))
        } catch (_: Exception) {
            // Metrics are intentionally non-blocking for business execution.
        }
    }
}
