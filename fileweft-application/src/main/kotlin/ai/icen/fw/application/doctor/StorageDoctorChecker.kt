package ai.icen.fw.application.doctor

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.doctor.DoctorChecker
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageObjectLocation

/** Confirms that the active document version can still be found in storage. */
class StorageDoctorChecker(
    private val documentRepository: DocumentRepository,
    private val fileObjectRepository: FileObjectRepository,
    private val storageAdapter: StorageAdapter,
    private val transaction: ApplicationTransaction,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        val documentId = context.documentId ?: return DoctorCheckResult(
            NAME,
            DoctorStatus.SKIPPED,
            "Storage diagnosis needs a document identifier.",
            repairSuggestion = "Run this checker as part of document diagnosis.",
        )
        val resolved = transaction.execute {
            val document = documentRepository.findById(context.tenantId, documentId) ?: return@execute StorageInspection(
                result = DoctorCheckResult(
                    NAME,
                    DoctorStatus.SKIPPED,
                    "Storage diagnosis was skipped because the document is unavailable.",
                    repairSuggestion = "Resolve the lifecycle diagnosis before checking document storage.",
                ),
            )
            val version = document.currentVersionId?.let { currentVersionId ->
                document.versions.firstOrNull { it.id == currentVersionId }
            } ?: return@execute StorageInspection(
                result = DoctorCheckResult(
                    NAME,
                    DoctorStatus.SKIPPED,
                    "Storage diagnosis was skipped because the document has no active version.",
                    repairSuggestion = "Upload and attach a document version before checking storage.",
                ),
            )
            val fileObject = fileObjectRepository.findById(context.tenantId, version.fileObjectId) ?: return@execute StorageInspection(
                result = DoctorCheckResult(
                    NAME,
                    DoctorStatus.ERROR,
                    "The active document version references a missing file object.",
                    evidence = mapOf("fileObjectId" to version.fileObjectId.value),
                    repairSuggestion = "Restore the file object record or attach a new file version through the application workflow.",
                ),
            )
            StorageInspection(
                location = StorageObjectLocation(fileObject.storageType, fileObject.storagePath),
                fileObjectId = fileObject.id,
                storageType = fileObject.storageType,
            )
        }
        resolved.result?.let { return it }

        val location = checkNotNull(resolved.location)
        val available = try {
            storageAdapter.exists(location)
        } catch (failure: Throwable) {
            return DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Storage adapter could not inspect the active file object.",
                evidence = mapOf("exceptionType" to failure.javaClass.name, "storageType" to checkNotNull(resolved.storageType)),
                repairSuggestion = "Check storage adapter configuration, credentials, and connectivity before retrying.",
            )
        }
        return if (available) {
            DoctorCheckResult(
                NAME,
                DoctorStatus.HEALTHY,
                "Active file object is available in storage.",
                evidence = mapOf("fileObjectId" to checkNotNull(resolved.fileObjectId).value, "storageType" to checkNotNull(resolved.storageType)),
            )
        } else {
            DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Active file object is missing from storage.",
                evidence = mapOf("fileObjectId" to checkNotNull(resolved.fileObjectId).value, "storageType" to checkNotNull(resolved.storageType)),
                repairSuggestion = "Restore the storage object from backup or upload a new document version.",
            )
        }
    }

    companion object {
        const val NAME = "storage"
    }

    private data class StorageInspection(
        val result: DoctorCheckResult? = null,
        val location: StorageObjectLocation? = null,
        val fileObjectId: Identifier? = null,
        val storageType: String? = null,
    )
}
