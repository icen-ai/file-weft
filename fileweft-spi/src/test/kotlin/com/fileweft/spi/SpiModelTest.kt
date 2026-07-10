package com.fileweft.spi

import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationAction
import com.fileweft.spi.authorization.AuthorizationEnvironment
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.authorization.AuthorizationResource
import com.fileweft.spi.authorization.AuthorizationSubject
import com.fileweft.spi.connector.ConnectorInvocation
import com.fileweft.spi.storage.StorageObjectLocation
import com.fileweft.spi.storage.StorageUploadRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals

class SpiModelTest {
    @Test
    fun `storage request accepts a non-negative streamed content length`() {
        val request = StorageUploadRequest(
            tenantId = Identifier("tenant-1"),
            objectName = "contract.pdf",
            contentLength = 128,
        )
        val location = StorageObjectLocation("local", "tenant-1/contract.pdf")

        assertEquals(128, request.contentLength)
        assertEquals("tenant-1/contract.pdf", location.path)
    }

    @Test
    fun `connector invocation requires idempotency and a positive timeout`() {
        assertThrows<IllegalArgumentException> {
            ConnectorInvocation("", Duration.ofSeconds(5))
        }
        assertThrows<IllegalArgumentException> {
            ConnectorInvocation("sync-1", Duration.ZERO)
        }
    }

    @Test
    fun `authorization request preserves all ABAC dimensions`() {
        val request = AuthorizationRequest(
            subject = AuthorizationSubject(Identifier("user-1"), "USER"),
            resource = AuthorizationResource(Identifier("doc-1"), "DOCUMENT", Identifier("tenant-1")),
            action = AuthorizationAction("document:publish"),
            environment = AuthorizationEnvironment(mapOf("source" to "api")),
        )

        assertEquals("user-1", request.subject.id.value)
        assertEquals("doc-1", request.resource.id.value)
        assertEquals("document:publish", request.action.name)
        assertEquals("api", request.environment.attributes["source"])
    }
}
