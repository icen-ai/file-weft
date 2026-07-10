package com.fileweft.domain.document

import com.fileweft.core.id.Identifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class DocumentTest {
    @Test
    fun `version is required before a draft can be submitted`() {
        val document = draft()

        assertThrows<IllegalArgumentException> {
            document.transition(LifecycleCommand.SUBMIT)
        }
        assertEquals(LifecycleState.DRAFT, document.lifecycleState)
    }

    @Test
    fun `document follows the publish lifecycle after approval`() {
        val document = draft()
        document.addVersion(version(document))

        document.transition(LifecycleCommand.SUBMIT)
        document.transition(LifecycleCommand.APPROVE)
        document.transition(LifecycleCommand.PUBLISH_SUCCEEDED)
        document.transition(LifecycleCommand.OFFLINE)

        assertEquals(LifecycleState.OFFLINE, document.lifecycleState)
    }

    @Test
    fun `document rejects transitions that violate its state machine`() {
        val document = draft()

        assertThrows<InvalidLifecycleTransitionException> {
            document.transition(LifecycleCommand.OFFLINE)
        }
        assertEquals(LifecycleState.DRAFT, document.lifecycleState)
    }

    @Test
    fun `version must belong to the same document and tenant`() {
        val document = draft()
        val invalidVersion = DocumentVersion(
            id = Identifier("version-1"),
            tenantId = Identifier("tenant-2"),
            documentId = document.id,
            versionNumber = "1.0",
            fileObjectId = Identifier("file-1"),
        )

        assertThrows<IllegalArgumentException> {
            document.addVersion(invalidVersion)
        }
    }

    private fun draft(): Document = Document(
        id = Identifier("document-1"),
        tenantId = Identifier("tenant-1"),
        assetId = Identifier("asset-1"),
        documentNumber = "DOC-001",
        title = "Contract",
    )

    private fun version(document: Document): DocumentVersion = DocumentVersion(
        id = Identifier("version-1"),
        tenantId = document.tenantId,
        documentId = document.id,
        versionNumber = "1.0",
        fileObjectId = Identifier("file-1"),
    )
}
