package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.delivery.DocumentDeliveryStatusView
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryService
import ai.icen.fw.application.delivery.DocumentSyncStatusView
import ai.icen.fw.web.api.v1.document.DocumentDeliverySyncStatusDto
import ai.icen.fw.web.api.v1.document.DocumentSyncStatusDto

/** Transport-neutral mapping for the formal, redacted synchronization status route. */
class DocumentSyncStatusApiFacade(
    private val synchronization: DocumentSyncStatusQueryService,
) {
    fun status(documentId: String): DocumentSyncStatusDto =
        synchronization.status(DocumentApiInputs.documentId(documentId)).toDto()

    private fun DocumentSyncStatusView.toDto(): DocumentSyncStatusDto = DocumentSyncStatusDto(
        documentId = documentId.value,
        deliveryTargets = deliveryTargets.map { target -> target.toDto() },
    )

    private fun DocumentDeliveryStatusView.toDto(): DocumentDeliverySyncStatusDto =
        DocumentDeliverySyncStatusDto(
            deliveryId = deliveryId.value,
            targetId = targetId,
            displayName = displayName,
            requirement = requirement.name,
            deliveryStatus = deliveryStatus.name,
            deliveryRetryCount = deliveryRetryCount,
            removalStatus = removalStatus.name,
            removalRetryCount = removalRetryCount,
            deliveryRetryable = deliveryRetryable,
            removalRetryable = removalRetryable,
            updatedTime = updatedTime,
            lastErrorCategory = lastErrorCategory?.name,
        )
}
