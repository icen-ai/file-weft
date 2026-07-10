package com.fileweft.application.doctor

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorStatus
import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.FileConnector
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ConnectorDoctorCheckerTest {
    @Test
    fun `skips connector diagnosis when no connector is installed`() {
        val result = ConnectorDoctorChecker(emptyList()).check(context())

        assertEquals(DoctorStatus.SKIPPED, result.status)
    }

    @Test
    fun `surfaces degraded and unhealthy connector states`() {
        val degraded = ConnectorDoctorChecker(
            listOf(FixedConnector(ConnectorHealth(ConnectorHealthStatus.DEGRADED, "slow"))),
        ).check(context())
        val unhealthy = ConnectorDoctorChecker(
            listOf(FixedConnector(ConnectorHealth(ConnectorHealthStatus.UNHEALTHY, "offline"))),
        ).check(context())

        assertEquals(DoctorStatus.WARNING, degraded.status)
        assertEquals(DoctorStatus.ERROR, unhealthy.status)
    }

    @Test
    fun `contains connector health invocation failures`() {
        val connector = object : FileConnector by FixedConnector(ConnectorHealth(ConnectorHealthStatus.HEALTHY)) {
            override fun health(): ConnectorHealth = throw IllegalStateException("unreachable")
        }

        val result = ConnectorDoctorChecker(listOf(connector)).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals("CHECK_FAILED", result.evidence["connector.0.status"])
    }

    @Test
    fun `explicitly reports unavailable capabilities as skipped`() {
        val result = UnavailableDoctorChecker(
            "agent",
            "No agent runtime is installed.",
            "Install and configure an agent runtime before enabling agent diagnosis.",
        ).check(context())

        assertEquals(DoctorStatus.SKIPPED, result.status)
        assertEquals("No agent runtime is installed.", result.reason)
    }

    private fun context() = DoctorCheckContext(Identifier("tenant-1"), Identifier("document-1"))
}
