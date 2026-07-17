package ai.icen.fw.testkit.catalog

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.catalog.DocumentCatalogAccessRequest
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DocumentCatalogProviderContractBehaviorTest {
    @Test
    fun `rejects malformed visible forests before an adapter can pass its contract`() {
        val invalidForests = listOf(
            listOf(
                DocumentCatalogFolder("duplicate", null, "First"),
                DocumentCatalogFolder("duplicate", null, "Second"),
            ),
            listOf(DocumentCatalogFolder("child", "hidden-parent", "Child")),
            listOf(
                DocumentCatalogFolder("cycle-a", "cycle-b", "Cycle A"),
                DocumentCatalogFolder("cycle-b", "cycle-a", "Cycle B"),
            ),
            listOf(DocumentCatalogFolder("safe", null, "Unsafe\u200bname")),
            (0..10_000).map { index -> DocumentCatalogFolder("folder-$index", null, "Folder $index") },
        )

        invalidForests.forEach { folders ->
            assertThrows(AssertionError::class.java) {
                Contract(Provider(folders)).`user-aware listFolders returns valid folders`()
            }
        }
    }

    private class Contract(
        override val catalogProvider: DocumentCatalogProvider,
    ) : DocumentCatalogProviderContractTest() {
        override fun tenantId(): Identifier = Identifier("tenant-a")

        override fun userId(): Identifier = Identifier("user-a")
    }

    private class Provider(
        private val folders: List<DocumentCatalogFolder>,
    ) : DocumentCatalogProvider {
        override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = folders

        override fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder> = folders
    }
}
