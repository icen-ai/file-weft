package ai.icen.fw.application.doctor

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.spi.catalog.DocumentCatalogBinding
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.doctor.DoctorChecker

/**
 * Verifies that a document's optional host-owned folder binding still resolves
 * within the document tenant. This deliberately uses the tenant-only catalog
 * contract: asynchronous Doctor jobs run without impersonating a user and
 * must not evaluate or bypass a user's folder ACL.
 */
class CatalogDoctorChecker(
    private val documents: DocumentRepository,
    private val assets: FileAssetRepository,
    private val catalog: DocumentCatalogProvider,
    private val transaction: ApplicationTransaction,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        val documentId = context.documentId ?: return DoctorCheckResult(
            NAME,
            DoctorStatus.SKIPPED,
            "Catalog diagnosis needs a document identifier.",
            repairSuggestion = "Run this checker as part of document diagnosis.",
        )
        val inspection = transaction.execute {
            val document = documents.findById(context.tenantId, documentId) ?: return@execute CatalogBindingInspection(
                result = DoctorCheckResult(
                    NAME,
                    DoctorStatus.SKIPPED,
                    "Catalog diagnosis was skipped because the document is unavailable.",
                    repairSuggestion = "Resolve the lifecycle diagnosis before checking the host folder binding.",
                ),
            )
            val asset = assets.findById(context.tenantId, document.assetId) ?: return@execute CatalogBindingInspection(
                result = DoctorCheckResult(
                    NAME,
                    DoctorStatus.ERROR,
                    "Document references a missing file asset, so its host folder binding cannot be verified.",
                    evidence = mapOf("assetId" to document.assetId.value),
                    repairSuggestion = "Restore the file asset record through a controlled data migration before rebinding the document folder.",
                ),
            )
            val folderId = asset.metadata[DocumentCatalogBinding.METADATA_KEY] ?: return@execute CatalogBindingInspection(
                result = DoctorCheckResult(
                    NAME,
                    DoctorStatus.SKIPPED,
                    "Document has no host folder binding.",
                    evidence = mapOf("assetId" to asset.id.value),
                    repairSuggestion = "Bind the document to a host folder when it must appear in the business file tree.",
                ),
            )
            CatalogBindingInspection(asset.id, folderId)
        }
        inspection.result?.let { return it }
        val assetId = checkNotNull(inspection.assetId)
        val folderId = checkNotNull(inspection.folderId)
        val folder = try {
            catalog.findFolder(context.tenantId, folderId)
        } catch (failure: Throwable) {
            return DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Host catalog could not verify the bound folder.",
                evidence = mapOf("assetId" to assetId.value, "folderId" to folderId, "exceptionType" to failure.javaClass.name),
                repairSuggestion = "Check the host catalog provider, its credentials, and connectivity before retrying diagnosis.",
            )
        }
        return if (folder == null) {
            DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "The bound host folder no longer exists in this tenant catalog.",
                evidence = mapOf("assetId" to assetId.value, "folderId" to folderId),
                repairSuggestion = "Restore the host folder or move the document to an available folder through DocumentCatalogBindingService.",
            )
        } else {
            DoctorCheckResult(
                NAME,
                DoctorStatus.HEALTHY,
                "The bound host folder is available in the document tenant catalog.",
                evidence = mapOf("assetId" to assetId.value, "folderId" to folderId),
            )
        }
    }

    companion object {
        const val NAME = "catalog"
    }

    private data class CatalogBindingInspection(
        val assetId: Identifier? = null,
        val folderId: String? = null,
        val result: DoctorCheckResult? = null,
    )
}
