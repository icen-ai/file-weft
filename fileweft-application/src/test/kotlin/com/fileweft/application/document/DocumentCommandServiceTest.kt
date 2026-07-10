package com.fileweft.application.document

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.document.LifecycleState
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class DocumentCommandServiceTest {
    @Test
    fun `records a named opaque user when revising a rejected document`() {
        val document = rejectedDocument()
        val audits = RecordingAudits()
        val service = DocumentCommandService(
            tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(Identifier("tenant-1")) },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser() = UserIdentity(Identifier("10001"), "外部编辑者")
                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest) = AuthorizationDecision(true)
            },
            documentRepository = InMemoryDocuments(document),
            transaction = DirectTransaction,
            auditTrail = AuditTrail(
                audits,
                object : IdentifierGenerator { override fun nextId(): Identifier = Identifier("audit-1") },
                Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC),
            ),
        )

        val revised = service.revise(document.id)

        assertEquals(LifecycleState.DRAFT, revised.lifecycleState)
        assertEquals("document:revise", audits.records.single().action)
        assertEquals("10001", audits.records.single().operatorId?.value)
        assertEquals("外部编辑者", audits.records.single().operatorName)
    }

    private fun rejectedDocument() = Document(
        Identifier("document-1"), Identifier("tenant-1"), Identifier("asset-1"), "DOC-001", "Contract",
        versions = listOf(DocumentVersion(Identifier("version-1"), Identifier("tenant-1"), Identifier("document-1"), "1.0", Identifier("file-1"))),
        currentVersionId = Identifier("version-1"),
    ).also {
        it.transition(LifecycleCommand.SUBMIT)
        it.transition(LifecycleCommand.REJECT)
    }

    private class InMemoryDocuments(private var document: Document) : DocumentRepository {
        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            document.takeIf { it.tenantId == tenantId && it.id == documentId }

        override fun save(document: Document) { this.document = document }
    }

    private class RecordingAudits : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) { records += record }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
