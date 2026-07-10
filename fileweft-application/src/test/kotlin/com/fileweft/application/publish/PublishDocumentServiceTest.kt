package com.fileweft.application.publish

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.delivery.DocumentDeliveryPlanner
import com.fileweft.application.delivery.DocumentDeliveryTarget
import com.fileweft.application.delivery.DocumentDeliveryTargetRepository
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
import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.ConnectorRemoveRequest
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncResult
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.delivery.DeliveryConnectorResolver
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.delivery.DocumentDeliveryProfile
import com.fileweft.spi.delivery.DocumentDeliveryProfileProvider
import com.fileweft.spi.delivery.DocumentDeliveryTargetDefinition
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class PublishDocumentServiceTest {
    @Test
    fun `authorizes then persists publishing state and one per target outbox event in one transaction`() {
        val document = pendingReviewDocument()
        val documentRepository = InMemoryDocumentRepository(document)
        val outbox = RecordingOutbox()
        val audits = RecordingAudits()
        val service = PublishDocumentService(
            tenantProvider = object : TenantProvider {
                override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
            },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-1"), "发布管理员")

                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
            },
            documentRepository = documentRepository,
            deliveryPlanner = deliveryPlanner(outbox, listOf("delivery-1", "event-1")),
            transaction = DirectTransaction,
            auditTrail = AuditTrail(
                audits,
                object : IdentifierGenerator { override fun nextId(): Identifier = Identifier("audit-1") },
                Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
            ),
        )

        service.publish(document.id)

        assertEquals(LifecycleState.PUBLISHING, document.lifecycleState)
        assertEquals(document.id, documentRepository.savedDocument?.id)
        assertEquals("document.delivery.target.requested", outbox.events.single().type)
        assertEquals(document.id.value, outbox.events.single().payload["documentId"])
        assertEquals("delivery-1", outbox.events.single().payload["deliveryId"])
        assertEquals(100, outbox.events.single().timestamp)
        assertEquals("document:publish:request", audits.records.single().action)
        assertEquals("发布管理员", audits.records.single().operatorName)
    }

    private fun pendingReviewDocument(): Document {
        val document = Document(
            id = Identifier("document-1"),
            tenantId = Identifier("tenant-1"),
            assetId = Identifier("asset-1"),
            documentNumber = "DOC-001",
            title = "Contract",
        )
        document.addVersion(
            DocumentVersion(
                id = Identifier("version-1"),
                tenantId = document.tenantId,
                documentId = document.id,
                versionNumber = "1.0",
                fileObjectId = Identifier("file-1"),
            ),
        )
        document.transition(LifecycleCommand.SUBMIT)
        return document
    }

    private class InMemoryDocumentRepository(document: Document) : DocumentRepository {
        private var document: Document? = document
        var savedDocument: Document? = null

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            document?.takeIf { it.tenantId == tenantId && it.id == documentId }

        override fun save(document: Document) {
            this.document = document
            savedDocument = document
        }
    }

    private class RecordingOutbox : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()

        override fun append(event: OutboxEvent) {
            events.add(event)
        }
    }

    private class RecordingAudits : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) { records += record }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private fun deliveryPlanner(outbox: RecordingOutbox, ids: List<String>): DocumentDeliveryPlanner {
        val identifiers = ArrayDeque(ids)
        return DocumentDeliveryPlanner(
            profiles = object : DocumentDeliveryProfileProvider {
                override fun listProfiles(tenantId: Identifier) = listOf(
                    DocumentDeliveryProfile(
                        "default", "Default", listOf(
                            DocumentDeliveryTargetDefinition("main", "Main", "main", DeliveryRequirement.REQUIRED),
                        ),
                    ),
                )
            },
            connectors = object : DeliveryConnectorResolver {
                override fun findConnector(connectorId: String): FileConnector = object : FileConnector {
                    override fun sync(request: ConnectorSyncRequest) = ConnectorSyncResult(com.fileweft.spi.connector.ConnectorSyncStatus.SUCCESS)
                    override fun remove(request: ConnectorRemoveRequest) = ConnectorSyncResult(com.fileweft.spi.connector.ConnectorSyncStatus.SUCCESS)
                    override fun health() = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
                }
            },
            deliveries = object : DocumentDeliveryTargetRepository {
                override fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? = null
                override fun findByDocument(tenantId: Identifier, documentId: Identifier): List<DocumentDeliveryTarget> = emptyList()
                override fun save(target: DocumentDeliveryTarget) = Unit
            },
            outbox = outbox,
            identifiers = object : IdentifierGenerator { override fun nextId(): Identifier = Identifier(identifiers.removeFirst()) },
            clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
        )
    }
}
