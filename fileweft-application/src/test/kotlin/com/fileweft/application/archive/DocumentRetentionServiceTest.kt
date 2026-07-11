package com.fileweft.application.archive

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.delivery.DocumentDeliveryRemovalPlanner
import com.fileweft.application.delivery.DocumentDeliveryRemovalStatus
import com.fileweft.application.delivery.DocumentDeliveryStatus
import com.fileweft.application.delivery.DocumentDeliveryTarget
import com.fileweft.application.delivery.DocumentDeliveryTargetRepository
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.offline.OfflineDocumentService
import com.fileweft.application.offline.RestoreOfflineDocumentService
import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.document.LifecycleState
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class DocumentRetentionServiceTest {
    @Test
    fun `archives a published document with explicit archive authorization`() {
        val repository = InMemoryDocumentRepository(publishedDocument())
        val audits = RecordingAudits()
        var action: String? = null
        val service = ArchiveDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { request ->
                action = request.action.name
                AuthorizationDecision(true)
            }, repository, DirectTransaction, auditTrail(audits),
        )

        val archived = service.archive(Identifier("document-1"))

        assertEquals(LifecycleState.HISTORY, archived.lifecycleState)
        assertEquals("document:archive", action)
        assertEquals(archived, repository.saved)
        assertEquals("document:archive", audits.records.single().action)
        assertEquals("保留管理员", audits.records.single().operatorName)
    }

    @Test
    fun `takes a published document offline through the existing lifecycle service`() {
        val repository = InMemoryDocumentRepository(publishedDocument())
        val audits = RecordingAudits()
        val deliveries = RecordingDeliveries(deliveredTarget())
        val outbox = RecordingOutbox()
        val service = OfflineDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) }, repository, DirectTransaction, auditTrail(audits),
            DocumentDeliveryRemovalPlanner(deliveries, outbox, SequenceIds("removal-event"), Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)),
        )

        val offline = service.offline(Identifier("document-1"))

        assertEquals(LifecycleState.OFFLINE, offline.lifecycleState)
        assertEquals(offline, repository.saved)
        assertEquals("document:offline", audits.records.single().action)
        assertEquals("1", audits.records.single().details["downstreamRemovalCount"])
        assertEquals(DocumentDeliveryRemovalStatus.PENDING, deliveries.target?.removalStatus)
        assertEquals(DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE, outbox.events.single().type)
    }

    @Test
    fun `does not create retention state for a missing tenant scoped document`() {
        val service = ArchiveDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) }, InMemoryDocumentRepository(null), DirectTransaction,
        )

        assertThrows<DocumentNotFoundException> { service.archive(Identifier("missing")) }
    }

    @Test
    fun `restores an offline document only after its current delivery generation is withdrawn`() {
        val document = publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) }
        val repository = InMemoryDocumentRepository(document)
        val audits = RecordingAudits()
        val service = RestoreOfflineDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) }, repository,
            RecordingDeliveries(deliveredTarget(DocumentDeliveryRemovalStatus.SUCCEEDED)), DirectTransaction, auditTrail(audits),
        )

        val restored = service.restore(document.id)

        assertEquals(LifecycleState.DRAFT, restored.lifecycleState)
        assertEquals(1, restored.deliveryGeneration)
        assertEquals("document:restore", audits.records.single().action)
    }

    @Test
    fun `does not restore while a delivered target is still awaiting withdrawal`() {
        val document = publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) }
        val service = RestoreOfflineDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) }, InMemoryDocumentRepository(document),
            RecordingDeliveries(deliveredTarget(DocumentDeliveryRemovalStatus.PENDING)), DirectTransaction,
        )

        assertThrows<IllegalArgumentException> { service.restore(document.id) }
    }

    private fun publishedDocument(): Document = Document(
        id = Identifier("document-1"),
        tenantId = Identifier("tenant-1"),
        assetId = Identifier("asset-1"),
        documentNumber = "DOC-001",
        title = "Contract",
        versions = listOf(DocumentVersion(Identifier("version-1"), Identifier("tenant-1"), Identifier("document-1"), "1.0", Identifier("file-1"))),
        currentVersionId = Identifier("version-1"),
    ).also {
        it.transition(LifecycleCommand.SUBMIT)
        it.transition(LifecycleCommand.APPROVE)
        it.transition(LifecycleCommand.PUBLISH_SUCCEEDED)
    }

    private fun deliveredTarget(removalStatus: DocumentDeliveryRemovalStatus = DocumentDeliveryRemovalStatus.NOT_REQUESTED) = DocumentDeliveryTarget(
        id = Identifier("delivery-1"),
        tenantId = Identifier("tenant-1"),
        documentId = Identifier("document-1"),
        profileId = "regulated",
        targetId = "archive",
        displayName = "Archive",
        connectorId = "archive-connector",
        requirement = DeliveryRequirement.REQUIRED,
        status = DocumentDeliveryStatus.SUCCEEDED,
        externalId = "archive:tenant-1:document-1",
        deliveryGeneration = 1,
        removalStatus = removalStatus,
    )

    private fun tenantProvider(): TenantProvider = object : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
    }

    private fun userProvider(): UserRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-1"), "保留管理员")
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private fun authorizationProvider(authorizer: (AuthorizationRequest) -> AuthorizationDecision): AuthorizationProvider =
        object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = authorizer(request)
        }

    private class InMemoryDocumentRepository(
        private var document: Document?,
    ) : DocumentRepository {
        var saved: Document? = null

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            document?.takeIf { it.tenantId == tenantId && it.id == documentId }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? =
            findById(tenantId, documentId)

        override fun save(document: Document) {
            this.document = document
            saved = document
        }
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private fun auditTrail(repository: RecordingAudits) = AuditTrail(
        repository,
        object : com.fileweft.core.id.IdentifierGenerator { override fun nextId(): Identifier = Identifier("audit-1") },
        Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC),
    )

    private class RecordingAudits : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) { records += record }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
    }

    private class RecordingDeliveries(var target: DocumentDeliveryTarget?) : DocumentDeliveryTargetRepository {
        override fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? =
            target?.takeIf { it.tenantId == tenantId && it.id == deliveryId }

        override fun findByDocument(tenantId: Identifier, documentId: Identifier): List<DocumentDeliveryTarget> =
            target?.takeIf { it.tenantId == tenantId && it.documentId == documentId }?.let(::listOf) ?: emptyList()

        override fun save(target: DocumentDeliveryTarget) {
            this.target = target
        }
    }

    private class RecordingOutbox : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()
        override fun append(event: OutboxEvent) {
            events += event
        }
    }

    private class SequenceIds(vararg values: String) : IdentifierGenerator {
        private val ids = ArrayDeque(values.toList())
        override fun nextId(): Identifier = Identifier(ids.removeFirst())
    }
}
