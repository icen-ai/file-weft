package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.delivery.DocumentDeliveryRecoveryReceipt
import ai.icen.fw.application.delivery.IdempotentDocumentCatalogDeliveryRecoveryService
import ai.icen.fw.application.delivery.IdempotentDocumentDeliveryRecoveryService
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.web.api.v1.document.DocumentDeliveryRecoveryResultDto
import ai.icen.fw.web.runtime.v1.IdempotencyKeyParser
import ai.icen.fw.web.runtime.v1.V1FeatureUnavailableException

/** Fail-closed flat/catalog selector for formal delivery recovery commands. */
class DocumentDeliveryRecoveryApiFacade(
    catalogAccessCount: Int,
    flatRecoveries: List<IdempotentDocumentDeliveryRecoveryService>,
    catalogRecoveries: List<IdempotentDocumentCatalogDeliveryRecoveryService>,
) {
    private val commands: RecoveryCommands? = resolve(catalogAccessCount, flatRecoveries, catalogRecoveries)

    fun retryDelivery(
        documentId: String,
        deliveryId: String,
        idempotencyKey: String,
    ): DocumentDeliveryRecoveryResultDto = commands().retryDelivery(
        DocumentApiInputs.documentId(documentId),
        deliveryId(deliveryId),
        IdempotencyKeyParser.parse(listOf(idempotencyKey)),
    ).toDto()

    fun retryRemoval(
        documentId: String,
        deliveryId: String,
        idempotencyKey: String,
    ): DocumentDeliveryRecoveryResultDto = commands().retryRemoval(
        DocumentApiInputs.documentId(documentId),
        deliveryId(deliveryId),
        IdempotencyKeyParser.parse(listOf(idempotencyKey)),
    ).toDto()

    private fun deliveryId(value: String): Identifier = DocumentApiInputs.deliveryId(value)

    private fun commands(): RecoveryCommands = commands ?: throw V1FeatureUnavailableException()

    private fun DocumentDeliveryRecoveryReceipt.toDto(): DocumentDeliveryRecoveryResultDto =
        DocumentDeliveryRecoveryResultDto(documentId.value, deliveryId.value, operation.name)

    private interface RecoveryCommands {
        fun retryDelivery(documentId: Identifier, deliveryId: Identifier, key: String): DocumentDeliveryRecoveryReceipt
        fun retryRemoval(documentId: Identifier, deliveryId: Identifier, key: String): DocumentDeliveryRecoveryReceipt
    }

    private class FlatCommands(
        private val service: IdempotentDocumentDeliveryRecoveryService,
    ) : RecoveryCommands {
        override fun retryDelivery(documentId: Identifier, deliveryId: Identifier, key: String) =
            service.retryDelivery(documentId, deliveryId, key)
        override fun retryRemoval(documentId: Identifier, deliveryId: Identifier, key: String) =
            service.retryRemoval(documentId, deliveryId, key)
    }

    private class CatalogCommands(
        private val service: IdempotentDocumentCatalogDeliveryRecoveryService,
    ) : RecoveryCommands {
        override fun retryDelivery(documentId: Identifier, deliveryId: Identifier, key: String) =
            service.retryDelivery(documentId, deliveryId, key)
        override fun retryRemoval(documentId: Identifier, deliveryId: Identifier, key: String) =
            service.retryRemoval(documentId, deliveryId, key)
    }

    private companion object {
        fun resolve(
            catalogAccessCount: Int,
            flat: List<IdempotentDocumentDeliveryRecoveryService>,
            catalog: List<IdempotentDocumentCatalogDeliveryRecoveryService>,
        ): RecoveryCommands? {
            require(catalogAccessCount in 0..1) { "Formal recovery API requires at most one catalog access boundary." }
            require(flat.size <= 1) { "Formal recovery API has multiple flat recovery candidates." }
            require(catalog.size <= 1) { "Formal recovery API has multiple catalog recovery candidates." }
            return when {
                catalogAccessCount == 0 && flat.size == 1 && catalog.isEmpty() -> FlatCommands(flat.single())
                catalogAccessCount == 1 && flat.isEmpty() && catalog.size == 1 -> CatalogCommands(catalog.single())
                else -> null
            }
        }
    }
}
