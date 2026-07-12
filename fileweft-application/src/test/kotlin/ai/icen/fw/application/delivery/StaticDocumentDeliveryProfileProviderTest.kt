package ai.icen.fw.application.delivery

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.delivery.DeliveryRequirement
import ai.icen.fw.spi.delivery.DocumentDeliveryProfile
import ai.icen.fw.spi.delivery.DocumentDeliveryTargetDefinition
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class StaticDocumentDeliveryProfileProviderTest {

    @Test
    fun `uses the sole connector for the legacy default name without requiring extra profile configuration`() {
        val connector = NoOpConnector
        val resolver = MapDeliveryConnectorResolver(mapOf("customerConnector" to connector), legacyConnectorId = "default")
        val profiles = StaticDocumentDeliveryProfileProvider(
            listOf(profile("default")),
            defaultProfileId = "default",
        )

        assertSame(connector, resolver.findConnector("default"))
        assertEquals("default", profiles.defaultProfile(Identifier("tenant-1"))?.id)
    }

    @Test
    fun `does not guess a connector when multiple candidates exist`() {
        val first = NoOpConnector
        val second = object : FileConnector by NoOpConnector {}
        val resolver = MapDeliveryConnectorResolver(
            mapOf("first" to first, "second" to second),
            legacyConnectorId = "default",
        )

        assertNull(resolver.findConnector("default"))
        assertSame(first, resolver.findConnector("first"))
        assertSame(second, resolver.findConnector("second"))
    }

    @Test
    fun `uses the first profile when no explicit default is configured`() {
        val profiles = StaticDocumentDeliveryProfileProvider(listOf(profile("first"), profile("second")))

        assertEquals("first", profiles.defaultProfile(Identifier("tenant-1"))?.id)
    }

    private fun profile(id: String) = DocumentDeliveryProfile(
        id = id,
        displayName = id,
        targets = listOf(DocumentDeliveryTargetDefinition("target-$id", id, "default", DeliveryRequirement.REQUIRED)),
    )

    private object NoOpConnector : FileConnector {
        override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult = ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

        override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult = ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

        override fun health(): ConnectorHealth = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
    }
}
