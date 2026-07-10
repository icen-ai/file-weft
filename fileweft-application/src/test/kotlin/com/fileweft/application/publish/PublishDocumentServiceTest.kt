package com.fileweft.application.publish

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

class PublishDocumentServiceTest {
    @Test
    fun `authorizes then persists publishing state and outbox event in one transaction`() {
        val document = pendingReviewDocument()
        val documentRepository = InMemoryDocumentRepository(document)
        val outbox = RecordingOutbox()
        val service = PublishDocumentService(
            tenantProvider = object : TenantProvider {
                override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
            },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-1"))

                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
            },
            documentRepository = documentRepository,
            outboxEventRepository = outbox,
            identifierGenerator = object : IdentifierGenerator {
                override fun nextId(): Identifier = Identifier("event-1")
            },
            transaction = DirectTransaction,
            clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
        )

        service.publish(document.id)

        assertEquals(LifecycleState.PUBLISHING, document.lifecycleState)
        assertEquals(document.id, documentRepository.savedDocument?.id)
        assertEquals("document.publish.requested", outbox.events.single().type)
        assertEquals(document.id.value, outbox.events.single().payload["documentId"])
        assertEquals(100, outbox.events.single().timestamp)
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

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
