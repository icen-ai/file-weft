package com.fileweft.application.upload

import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.file.FileAsset
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.domain.file.FileObject
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageUploadRequest
import com.fileweft.spi.tenant.TenantProvider
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
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun upload(command: UploadFileCommand, content: InputStream): UploadFileResult {
        val tenant = tenantProvider.currentTenant()
        val fileObjectId = identifierGenerator.nextId()
        val fileAssetId = identifierGenerator.nextId()
        authorization.requireAction(tenant.tenantId, fileObjectId, "FILE_OBJECT", "file:upload")

        val storedObject = storageAdapter.upload(
            StorageUploadRequest(
                tenantId = tenant.tenantId,
                objectName = command.fileName,
                contentLength = command.contentLength,
                contentType = command.contentType,
                contentHash = command.contentHash,
                metadata = command.metadata,
            ),
            content,
        )
        return try {
            transaction.execute {
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
        } catch (failure: Throwable) {
            try {
                storageAdapter.delete(storedObject.location)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }
}
