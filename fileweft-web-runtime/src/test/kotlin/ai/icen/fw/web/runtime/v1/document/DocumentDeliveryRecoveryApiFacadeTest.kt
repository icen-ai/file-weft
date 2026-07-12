package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.delivery.DocumentDeliveryRecoveryOperation
import ai.icen.fw.application.delivery.DocumentDeliveryRecoveryReceipt
import ai.icen.fw.application.delivery.IdempotentDocumentCatalogDeliveryRecoveryService
import ai.icen.fw.application.delivery.IdempotentDocumentDeliveryRecoveryService
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.web.api.ApiErrorCodes
import ai.icen.fw.web.api.v1.document.DocumentDeliveryRecoveryResultDto
import ai.icen.fw.web.runtime.v1.ApiHttpStatus
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.V1FeatureUnavailableException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DocumentDeliveryRecoveryApiFacadeTest {
    @Test
    fun `maps only stable recovery receipt identifiers and operation`() {
        val facade = DocumentDeliveryRecoveryApiFacade(0, emptyList(), emptyList())
        val mapper = facade.javaClass.declaredMethods.single { method ->
            method.returnType == DocumentDeliveryRecoveryResultDto::class.java &&
                method.parameterTypes.contentEquals(arrayOf(DocumentDeliveryRecoveryReceipt::class.java))
        }.apply { isAccessible = true }

        val result = mapper.invoke(
            facade,
            DocumentDeliveryRecoveryReceipt(
                Identifier("document-1"),
                Identifier("delivery-1"),
                DocumentDeliveryRecoveryOperation.REMOVAL,
            ),
        ) as DocumentDeliveryRecoveryResultDto

        assertEquals("document-1", result.documentId)
        assertEquals("delivery-1", result.deliveryId)
        assertEquals("REMOVAL", result.operation)
    }

    @Test
    fun `selects exactly the flat or catalog command boundary matching catalog access`() {
        val flat = DocumentDeliveryRecoveryApiFacade(
            0,
            listOf(allocate(IdempotentDocumentDeliveryRecoveryService::class.java)),
            emptyList(),
        )
        val catalog = DocumentDeliveryRecoveryApiFacade(
            1,
            emptyList(),
            listOf(allocate(IdempotentDocumentCatalogDeliveryRecoveryService::class.java)),
        )

        assertEquals("FlatCommands", selected(flat)?.javaClass?.simpleName)
        assertEquals("CatalogCommands", selected(catalog)?.javaClass?.simpleName)
    }

    @Test
    fun `missing and mixed recovery capabilities fail closed as fixed 503`() {
        val flat = allocate(IdempotentDocumentDeliveryRecoveryService::class.java)
        val catalog = allocate(IdempotentDocumentCatalogDeliveryRecoveryService::class.java)
        val unsafeFacades = listOf(
            DocumentDeliveryRecoveryApiFacade(0, emptyList(), emptyList()),
            DocumentDeliveryRecoveryApiFacade(1, listOf(flat), emptyList()),
            DocumentDeliveryRecoveryApiFacade(0, emptyList(), listOf(catalog)),
            DocumentDeliveryRecoveryApiFacade(1, listOf(flat), listOf(catalog)),
        )

        unsafeFacades.forEach { facade ->
            assertNull(selected(facade))
            val failure = assertFailsWith<V1FeatureUnavailableException> {
                facade.retryDelivery("document-1", "delivery-1", "retry-key")
            }
            val mapped = V1ApiResponseFactory().failure(failure)
            assertEquals(ApiHttpStatus.SERVICE_UNAVAILABLE, mapped.status)
            assertEquals(ApiErrorCodes.FEATURE_UNAVAILABLE, mapped.response.error?.code)
        }
    }

    @Test
    fun `rejects ambiguous recovery candidates and catalog access boundaries at startup`() {
        val flat = allocate(IdempotentDocumentDeliveryRecoveryService::class.java)
        val catalog = allocate(IdempotentDocumentCatalogDeliveryRecoveryService::class.java)

        assertFailsWith<IllegalArgumentException> {
            DocumentDeliveryRecoveryApiFacade(2, emptyList(), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentDeliveryRecoveryApiFacade(0, listOf(flat, flat), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentDeliveryRecoveryApiFacade(1, emptyList(), listOf(catalog, catalog))
        }
    }

    @Test
    fun `validates identifiers and idempotency keys before entering a selected service`() {
        val selected = DocumentDeliveryRecoveryApiFacade(
            0,
            listOf(allocate(IdempotentDocumentDeliveryRecoveryService::class.java)),
            emptyList(),
        )

        assertFailsWith<IllegalArgumentException> {
            selected.retryDelivery(" ", "delivery-1", "retry-key")
        }
        assertFailsWith<IllegalArgumentException> {
            selected.retryRemoval("document-1", "delivery\u0000-1", "retry-key")
        }
        assertFailsWith<IllegalArgumentException> {
            selected.retryDelivery("document-1", "delivery-1", "private key")
        }
    }

    private fun selected(facade: DocumentDeliveryRecoveryApiFacade): Any? {
        val field = DocumentDeliveryRecoveryApiFacade::class.java.getDeclaredField("commands")
        field.isAccessible = true
        return field.get(facade)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val field = unsafeType.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        return unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }
}
