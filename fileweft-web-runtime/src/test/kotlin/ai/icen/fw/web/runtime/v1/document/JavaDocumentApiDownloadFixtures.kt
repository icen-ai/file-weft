package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.document.DocumentDownloadService
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.time.Duration

class JavaDocumentApiDownloadFixtures private constructor() {
    companion object {
        @JvmStatic
        fun facade(): DocumentApiDownloadFacade {
            val tenantId = Identifier("tenant-java")
            val documentId = Identifier("document-java")
            val versionId = Identifier("version-java")
            val fileId = Identifier("file-java")
            val document = Document(
                id = documentId,
                tenantId = tenantId,
                assetId = Identifier("asset-java"),
                documentNumber = "DOC-JAVA",
                title = "Java download",
                versions = listOf(DocumentVersion(versionId, tenantId, documentId, "1.0", fileId)),
                currentVersionId = versionId,
            )
            val users = object : UserRealmProvider {
                override fun currentUser() = UserIdentity(Identifier("user-java"), "Java User")
                override fun findUser(userId: Identifier): UserIdentity? = null
            }
            return DocumentApiDownloadFacade(
                DocumentDownloadService.withDeletionVisibility(
                    tenantProvider = object : TenantProvider {
                        override fun currentTenant() = TenantContext(tenantId)
                    },
                    userRealmProvider = users,
                    authorizationProvider = object : AuthorizationProvider {
                        override fun authorize(request: AuthorizationRequest) = AuthorizationDecision(true)
                    },
                    documentRepository = object : DocumentRepository {
                        override fun findById(requestTenantId: Identifier, requestDocumentId: Identifier): Document? =
                            document.takeIf { it.tenantId == requestTenantId && it.id == requestDocumentId }

                        override fun save(document: Document) = Unit
                    },
                    fileObjectRepository = object : FileObjectRepository {
                        override fun findById(requestTenantId: Identifier, fileObjectId: Identifier): FileObject? =
                            FileObject(
                                id = fileObjectId,
                                tenantId = requestTenantId,
                                fileName = "清税证明.pdf",
                                contentLength = 4,
                                storageType = "memory",
                                storagePath = "tenant-java/file-java",
                                contentType = "application/pdf",
                            )

                        override fun save(fileObject: FileObject) = Unit
                    },
                    storageAdapter = object : StorageAdapter {
                        override fun download(location: StorageObjectLocation) = StorageDownload(
                            ByteArrayInputStream("java".toByteArray()),
                            4,
                            "application/pdf",
                        )

                        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject =
                            throw UnsupportedOperationException()
                        override fun delete(location: StorageObjectLocation) = Unit
                        override fun exists(location: StorageObjectLocation): Boolean = true
                        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI =
                            URI("http://localhost/file")
                        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload =
                            throw UnsupportedOperationException()
                        override fun uploadPart(
                            upload: MultipartUpload,
                            partNumber: Int,
                            content: InputStream,
                            contentLength: Long,
                        ): MultipartPart = throw UnsupportedOperationException()
                        override fun completeMultipartUpload(
                            upload: MultipartUpload,
                            parts: List<MultipartPart>,
                        ): StoredObject = throw UnsupportedOperationException()
                        override fun abortMultipartUpload(upload: MultipartUpload) = Unit
                    },
                    transaction = object : ApplicationTransaction {
                        override fun <T> execute(action: () -> T): T = action()
                    },
                    auditTrail = null,
                    deletionVisibility = visibleDeletionGuard(),
                ),
            )
        }
    }
}
