package com.fileweft.domain.document

import com.fileweft.core.id.Identifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DocumentTest {
    @Test
    fun `version is required before a draft can be submitted`() {
        val document = draft()

        val failure = assertThrows<DocumentVersionRequiredException> {
            document.transition(LifecycleCommand.SUBMIT)
        }

        assertEquals("A document version is required before submission.", failure.message)
        assertEquals(LifecycleState.DRAFT, document.lifecycleState)
    }

    @Test
    fun `document reports an explicit conflict when its current state is not editable`() {
        val document = draft()
        document.addVersion(version(document))
        document.transition(LifecycleCommand.SUBMIT)

        val renameFailure = assertThrows<DocumentNotEditableException> {
            document.rename("Renamed contract")
        }
        val versionFailure = assertThrows<DocumentNotEditableException> {
            document.addVersion(version(document, id = "version-2", versionNumber = "2.0"))
        }

        assertEquals(LifecycleState.PENDING_REVIEW, renameFailure.currentState)
        assertEquals(LifecycleState.PENDING_REVIEW, versionFailure.currentState)
        assertEquals("Contract", document.title)
        assertEquals(listOf("1.0"), document.versions.map { it.versionNumber })
    }

    @Test
    fun `document reports a business version number collision as a conflict`() {
        val document = draft()
        document.addVersion(version(document))

        val failure = assertThrows<DocumentVersionAlreadyExistsException> {
            document.addVersion(version(document, id = "version-2", versionNumber = "1.0"))
        }

        assertEquals("1.0", failure.versionNumber)
        assertEquals(listOf("1.0"), document.versions.map { it.versionNumber })
        assertEquals(Identifier("version-1"), document.currentVersionId)
    }

    @Test
    fun `generated version id collision remains an internal invariant failure`() {
        val document = draft()
        document.addVersion(version(document))

        val failure = assertThrows<IllegalStateException> {
            document.addVersion(version(document, id = "version-1", versionNumber = "2.0"))
        }

        assertFalse(failure is DocumentConflictException)
        assertEquals(listOf("1.0"), document.versions.map { it.versionNumber })
    }

    @Test
    fun `blank title remains an invalid argument while a draft is editable`() {
        val document = draft()

        assertThrows<IllegalArgumentException> {
            document.rename(" ")
        }
        assertEquals("Contract", document.title)
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
        assertEquals(1, document.deliveryGeneration)
    }

    @Test
    fun `offline document returns to a draft without reusing its publication generation`() {
        val document = draft()
        document.addVersion(version(document))
        document.transition(LifecycleCommand.SUBMIT)
        document.transition(LifecycleCommand.APPROVE)
        document.transition(LifecycleCommand.PUBLISH_SUCCEEDED)
        document.transition(LifecycleCommand.OFFLINE)

        document.transition(LifecycleCommand.RESTORE_DRAFT)

        assertEquals(LifecycleState.DRAFT, document.lifecycleState)
        assertEquals(1, document.deliveryGeneration)
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

    private fun version(
        document: Document,
        id: String = "version-1",
        versionNumber: String = "1.0",
    ): DocumentVersion = DocumentVersion(
        id = Identifier(id),
        tenantId = document.tenantId,
        documentId = document.id,
        versionNumber = versionNumber,
        fileObjectId = Identifier("file-1"),
    )
}
