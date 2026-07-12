package ai.icen.fw.application.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.domain.document.Document
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LifecycleDoctorCheckerTest {
    @Test
    fun `reports an error when document does not exist`() {
        val result = LifecycleDoctorChecker(InMemoryDocumentRepository()).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
    }

    @Test
    fun `warns when a draft document has no active version`() {
        val draft = Document(Identifier("document-1"), Identifier("tenant-1"), Identifier("asset-1"), "DOC-001", "Contract")
        val result = LifecycleDoctorChecker(InMemoryDocumentRepository(draft)).check(context())

        assertEquals(DoctorStatus.WARNING, result.status)
    }

    @Test
    fun `reports healthy for a coherent active version`() {
        val result = LifecycleDoctorChecker(InMemoryDocumentRepository(documentWithActiveVersion())).check(context())

        assertEquals(DoctorStatus.HEALTHY, result.status)
        assertEquals("version-1", result.evidence["currentVersionId"])
    }

    private fun context() = DoctorCheckContext(Identifier("tenant-1"), Identifier("document-1"))
}
