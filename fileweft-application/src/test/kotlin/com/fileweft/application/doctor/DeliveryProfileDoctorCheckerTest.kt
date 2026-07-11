package com.fileweft.application.doctor

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorStatus
import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.delivery.DeliveryConnectorResolver
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.delivery.DocumentDeliveryProfile
import com.fileweft.spi.delivery.DocumentDeliveryProfileProvider
import com.fileweft.spi.delivery.DocumentDeliveryTargetDefinition
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DeliveryProfileDoctorCheckerTest {
    @Test
    fun `reports healthy when every tenant target resolves to a connector`() {
        val result = DeliveryProfileDoctorChecker(
            profiles(profile("regulated", target("archive", "archive-connector"), target("search", "search-connector", DeliveryRequirement.OPTIONAL))),
            resolver("archive-connector", "search-connector"),
        ).check(context())

        assertEquals(DoctorStatus.HEALTHY, result.status)
        assertEquals("RESOLVED", result.evidence["profile.0.target.0.status"])
        assertEquals("RESOLVED", result.evidence["profile.0.target.1.status"])
        assertEquals("0", result.evidence["unresolvedTargetCount"])
    }

    @Test
    fun `reports every unresolved target including optional targets`() {
        val result = DeliveryProfileDoctorChecker(
            profiles(profile("regulated", target("archive", "archive-connector"), target("search", "search-connector", DeliveryRequirement.OPTIONAL))),
            resolver("archive-connector"),
        ).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals("RESOLVED", result.evidence["profile.0.target.0.status"])
        assertEquals("UNRESOLVED", result.evidence["profile.0.target.1.status"])
        assertEquals("1", result.evidence["unresolvedTargetCount"])
    }

    @Test
    fun `reports an unavailable tenant profile set`() {
        val result = DeliveryProfileDoctorChecker(
            object : DocumentDeliveryProfileProvider {
                override fun listProfiles(tenantId: Identifier): List<DocumentDeliveryProfile> = emptyList()
            },
            resolver(),
        ).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals("No delivery profile is available for the current tenant.", result.reason)
    }

    @Test
    fun `contains connector resolver failures as diagnostic evidence`() {
        val result = DeliveryProfileDoctorChecker(
            profiles(profile("regulated", target("archive", "archive-connector"))),
            object : DeliveryConnectorResolver {
                override fun findConnector(connectorId: String): FileConnector? = throw IllegalStateException("registry unavailable")
            },
        ).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals("RESOLUTION_FAILED", result.evidence["profile.0.target.0.status"])
        assertEquals(IllegalStateException::class.java.name, result.evidence["profile.0.target.0.exceptionType"])
    }

    private fun context() = DoctorCheckContext(Identifier("tenant-1"), Identifier("document-1"))

    private fun profiles(vararg profiles: DocumentDeliveryProfile): DocumentDeliveryProfileProvider = object : DocumentDeliveryProfileProvider {
        override fun listProfiles(tenantId: Identifier): List<DocumentDeliveryProfile> = profiles.toList()
    }

    private fun resolver(vararg connectorIds: String): DeliveryConnectorResolver {
        val connector = FixedConnector(ConnectorHealth(ConnectorHealthStatus.HEALTHY))
        val connectors = connectorIds.associateWith { connector }
        return object : DeliveryConnectorResolver {
            override fun findConnector(connectorId: String): FileConnector? = connectors[connectorId]
        }
    }

    private fun profile(id: String, vararg targets: DocumentDeliveryTargetDefinition) = DocumentDeliveryProfile(
        id = id,
        displayName = id,
        targets = targets.toList(),
    )

    private fun target(
        id: String,
        connectorId: String,
        requirement: DeliveryRequirement = DeliveryRequirement.REQUIRED,
    ) = DocumentDeliveryTargetDefinition(id, id, connectorId, requirement)
}
