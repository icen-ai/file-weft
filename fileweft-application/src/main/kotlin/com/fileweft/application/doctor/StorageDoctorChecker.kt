package com.fileweft.application.doctor

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorStatus
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.spi.doctor.DoctorChecker
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageObjectLocation

/** Confirms that the active document version can still be found in storage. */
class StorageDoctorChecker(
    private val documentRepository: DocumentRepository,
    private val fileObjectRepository: FileObjectRepository,
    private val storageAdapter: StorageAdapter,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        val documentId = context.documentId ?: return DoctorCheckResult(
            NAME,
            DoctorStatus.SKIPPED,
            "Storage diagnosis needs a document identifier.",
            repairSuggestion = "Run this checker as part of document diagnosis.",
        )
        val document = documentRepository.findById(context.tenantId, documentId) ?: return DoctorCheckResult(
            NAME,
            DoctorStatus.SKIPPED,
            "Storage diagnosis was skipped because the document is unavailable.",
            repairSuggestion = "Resolve the lifecycle diagnosis before checking document storage.",
        )
        val version = document.currentVersionId?.let { currentVersionId ->
            document.versions.firstOrNull { it.id == currentVersionId }
        } ?: return DoctorCheckResult(
            NAME,
            DoctorStatus.SKIPPED,
            "Storage diagnosis was skipped because the document has no active version.",
            repairSuggestion = "Upload and attach a document version before checking storage.",
        )
        val fileObject = fileObjectRepository.findById(context.tenantId, version.fileObjectId) ?: return DoctorCheckResult(
            NAME,
            DoctorStatus.ERROR,
            "The active document version references a missing file object.",
            evidence = mapOf("fileObjectId" to version.fileObjectId.value),
            repairSuggestion = "Restore the file object record or attach a new file version through the application workflow.",
        )
        val location = StorageObjectLocation(fileObject.storageType, fileObject.storagePath)
        val available = try {
            storageAdapter.exists(location)
        } catch (failure: Throwable) {
            return DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Storage adapter could not inspect the active file object.",
                evidence = mapOf("exceptionType" to failure.javaClass.name, "storageType" to fileObject.storageType),
                repairSuggestion = "Check storage adapter configuration, credentials, and connectivity before retrying.",
            )
        }
        return if (available) {
            DoctorCheckResult(
                NAME,
                DoctorStatus.HEALTHY,
                "Active file object is available in storage.",
                evidence = mapOf("fileObjectId" to fileObject.id.value, "storageType" to fileObject.storageType),
            )
        } else {
            DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Active file object is missing from storage.",
                evidence = mapOf("fileObjectId" to fileObject.id.value, "storageType" to fileObject.storageType),
                repairSuggestion = "Restore the storage object from backup or upload a new document version.",
            )
        }
    }

    companion object {
        const val NAME = "storage"
    }
}
