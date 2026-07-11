package com.fileweft.web.runtime.v1.document

import com.fileweft.application.document.DocumentDownloadService
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.file.FileObject
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.storage.MultipartPart
import com.fileweft.spi.storage.MultipartUpload
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageDownload
import com.fileweft.spi.storage.StorageObjectLocation
import com.fileweft.spi.storage.StorageUploadRequest
import com.fileweft.spi.storage.StoredObject
import com.fileweft.spi.tenant.TenantProvider
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
                DocumentDownloadService(
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
                ),
            )
        }
    }
}
