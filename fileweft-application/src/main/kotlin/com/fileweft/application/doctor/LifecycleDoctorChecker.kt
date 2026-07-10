package com.fileweft.application.doctor

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorStatus
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.LifecycleState
import com.fileweft.spi.doctor.DoctorChecker

/** Verifies that the document aggregate and its active version are coherent. */
class LifecycleDoctorChecker(
    private val documentRepository: DocumentRepository,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        val documentId = context.documentId ?: return DoctorCheckResult(
            NAME,
            DoctorStatus.SKIPPED,
            "Lifecycle diagnosis needs a document identifier.",
            repairSuggestion = "Run this checker as part of document diagnosis.",
        )
        val document = documentRepository.findById(context.tenantId, documentId) ?: return DoctorCheckResult(
            NAME,
            DoctorStatus.ERROR,
            "Document was not found in the current tenant.",
            evidence = mapOf("documentId" to documentId.value),
            repairSuggestion = "Verify the document identifier and tenant context, then restore the document if it was removed unexpectedly.",
        )
        val evidence = linkedMapOf(
            "lifecycleState" to document.lifecycleState.name,
            "versionCount" to document.versions.size.toString(),
        )
        val currentVersionId = document.currentVersionId
        if (currentVersionId == null) {
            val status = if (document.lifecycleState == LifecycleState.DRAFT) DoctorStatus.WARNING else DoctorStatus.ERROR
            return DoctorCheckResult(
                NAME,
                status,
                "Document has no active version.",
                evidence,
                "Upload and attach a document version before progressing its lifecycle.",
            )
        }
        if (document.versions.none { it.id == currentVersionId }) {
            return DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Document active version does not belong to the document.",
                evidence + ("currentVersionId" to currentVersionId.value),
                "Repair the document version reference through a controlled data migration.",
            )
        }
        return DoctorCheckResult(
            NAME,
            DoctorStatus.HEALTHY,
            "Document lifecycle and active version are coherent.",
            evidence + ("currentVersionId" to currentVersionId.value),
        )
    }

    companion object {
        const val NAME = "lifecycle"
    }
}
