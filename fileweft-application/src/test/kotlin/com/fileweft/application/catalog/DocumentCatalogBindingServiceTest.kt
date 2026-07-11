package com.fileweft.application.catalog

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileAsset
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.catalog.DocumentCatalogAccessRequest
import com.fileweft.spi.catalog.DocumentCatalogFolder
import com.fileweft.spi.catalog.DocumentCatalogProvider
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class DocumentCatalogBindingServiceTest {
    @Test
    fun `moves only the opaque folder metadata and records an audit trail`() {
        val tenant = Identifier("tenant-a")
        val document = Document(
            id = Identifier("document-1"), tenantId = tenant, assetId = Identifier("asset-1"),
            documentNumber = "DOC-001", title = "Contract",
        )
        val assets = MemoryAssets(
            FileAsset(document.assetId, tenant, Identifier("file-1"), "DOCUMENT", mapOf("catalog.folder-id" to "inbox", "source" to "host")),
        )
        val authorization = RecordingAuthorization()
        val audits = MemoryAudits()
        val access = DocumentCatalogAccessService(
            tenantProvider = tenantProvider(tenant),
            userRealmProvider = userRealm(),
            authorizationProvider = authorization,
            catalog = object : DocumentCatalogProvider {
                override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = emptyList()
                override fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder> = listOf(
                    DocumentCatalogFolder("inbox", null, "Inbox"),
                    DocumentCatalogFolder("contracts", null, "Contracts"),
                )
            },
        )
        val service = DocumentCatalogBindingService(
            tenantProvider = tenantProvider(tenant),
            userRealmProvider = userRealm(),
            catalogAccess = access,
            documents = object : DocumentRepository {
                override fun findById(tenantId: Identifier, documentId: Identifier) = document.takeIf { it.tenantId == tenantId && it.id == documentId }
                override fun save(document: Document) = Unit
            },
            assets = assets,
            transaction = DirectTransaction,
            auditTrail = AuditTrail(audits, SequenceIds(), Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)),
        )

        service.move(document.id, "contracts")
        service.move(document.id, "contracts")

        assertEquals("contracts", assets.asset.metadata["catalog.folder-id"])
        assertEquals("host", assets.asset.metadata["source"])
        assertEquals("document:edit", authorization.lastRequest?.action?.name)
        assertEquals(1, audits.records.size)
        assertEquals("document:catalog:move", audits.records.single().action)
        assertEquals("inbox", audits.records.single().details["previousFolderId"])
        assertEquals("contracts", audits.records.single().details["folderId"])
        assertEquals("Editor A", audits.records.single().operatorName)
    }

    private fun tenantProvider(tenant: Identifier) = object : TenantProvider {
        override fun currentTenant() = TenantContext(tenant)
    }

    private fun userRealm() = object : UserRealmProvider {
        override fun currentUser() = UserIdentity(Identifier("editor-a"), "Editor A")
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private class RecordingAuthorization : AuthorizationProvider {
        var lastRequest: AuthorizationRequest? = null
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            lastRequest = request
            return AuthorizationDecision(true)
        }
    }

    private class MemoryAssets(initial: FileAsset) : FileAssetRepository {
        var asset: FileAsset = initial
        override fun findById(tenantId: Identifier, fileAssetId: Identifier) = asset.takeIf { it.tenantId == tenantId && it.id == fileAssetId }
        override fun save(fileAsset: FileAsset) { asset = fileAsset }
    }

    private class MemoryAudits : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) { records += record }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int) =
            records.filter { it.tenantId == tenantId && it.resourceType == resourceType && it.resourceId == resourceId }.take(limit)
    }

    private class SequenceIds : IdentifierGenerator {
        private var counter = 0
        override fun nextId() = Identifier("audit-${++counter}")
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
