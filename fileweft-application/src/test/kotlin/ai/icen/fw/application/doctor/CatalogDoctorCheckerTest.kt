package ai.icen.fw.application.doctor

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.spi.catalog.DocumentCatalogBinding
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CatalogDoctorCheckerTest {
    @Test
    fun `skips when document is unavailable or has no folder binding`() {
        val unavailable = checker(document = null, asset = null, folders = emptyList()).check(context())
        assertEquals(DoctorStatus.SKIPPED, unavailable.status)

        val unbound = checker(
            document = document(),
            asset = asset(emptyMap()),
            folders = emptyList(),
        ).check(context())
        assertEquals(DoctorStatus.SKIPPED, unbound.status)
        assertEquals("asset-1", unbound.evidence["assetId"])
    }

    @Test
    fun `reports a healthy binding only when the folder resolves inside the tenant catalog`() {
        val result = checker(
            document = document(),
            asset = asset(mapOf(DocumentCatalogBinding.METADATA_KEY to "contracts")),
            folders = listOf(DocumentCatalogFolder("contracts", null, "Contracts")),
        ).check(context())

        assertEquals(DoctorStatus.HEALTHY, result.status)
        assertEquals("contracts", result.evidence["folderId"])
        assertEquals("asset-1", result.evidence["assetId"])
    }

    @Test
    fun `reports repairable errors for missing asset or missing host folder`() {
        val missingAsset = checker(document(), null, emptyList()).check(context())
        assertEquals(DoctorStatus.ERROR, missingAsset.status)
        assertEquals("asset-1", missingAsset.evidence["assetId"])

        val missingFolder = checker(
            document(),
            asset(mapOf(DocumentCatalogBinding.METADATA_KEY to "retired")),
            emptyList(),
        ).check(context())
        assertEquals(DoctorStatus.ERROR, missingFolder.status)
        assertEquals("retired", missingFolder.evidence["folderId"])
        assertEquals(
            "Restore the host folder or move the document to an available folder through DocumentCatalogBindingService.",
            missingFolder.repairSuggestion,
        )
    }

    @Test
    fun `contains host catalog failures with actionable evidence`() {
        val result = CatalogDoctorChecker(
            documentRepository(document()),
            fileAssets(asset(mapOf(DocumentCatalogBinding.METADATA_KEY to "contracts"))),
            object : DocumentCatalogProvider {
                override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
                    throw IllegalStateException("catalog unavailable")
            },
            DirectTransaction,
        ).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals(IllegalStateException::class.java.name, result.evidence["exceptionType"])
    }

    @Test
    fun `resolves the host folder after the local persistence transaction has completed`() {
        var transactionActive = false
        val transaction = object : ApplicationTransaction {
            override fun <T> execute(action: () -> T): T {
                transactionActive = true
                return try {
                    action()
                } finally {
                    transactionActive = false
                }
            }
        }
        val checker = CatalogDoctorChecker(
            documentRepository(document()),
            fileAssets(asset(mapOf(DocumentCatalogBinding.METADATA_KEY to "contracts"))),
            object : DocumentCatalogProvider {
                override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> {
                    assertFalse(transactionActive)
                    return listOf(DocumentCatalogFolder("contracts", null, "Contracts"))
                }
            },
            transaction,
        )

        assertEquals(DoctorStatus.HEALTHY, checker.check(context()).status)
    }

    private fun checker(
        document: Document?,
        asset: FileAsset?,
        folders: List<DocumentCatalogFolder>,
    ): CatalogDoctorChecker = CatalogDoctorChecker(
        documentRepository(document),
        fileAssets(asset),
        object : DocumentCatalogProvider {
            override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = folders
        },
        DirectTransaction,
    )

    private fun context() = DoctorCheckContext(Identifier("tenant-1"), Identifier("document-1"))

    private fun document() = Document(
        Identifier("document-1"), Identifier("tenant-1"), Identifier("asset-1"), "DOC-001", "Contract",
    )

    private fun asset(metadata: Map<String, String>) = FileAsset(
        Identifier("asset-1"), Identifier("tenant-1"), Identifier("file-1"), "DOCUMENT", metadata,
    )

    private fun documentRepository(document: Document?) = InMemoryDocumentRepository(document)

    private fun fileAssets(asset: FileAsset?) = object : FileAssetRepository {
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
            asset?.takeIf { it.tenantId == tenantId && it.id == fileAssetId }

        override fun save(fileAsset: FileAsset) = Unit
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
