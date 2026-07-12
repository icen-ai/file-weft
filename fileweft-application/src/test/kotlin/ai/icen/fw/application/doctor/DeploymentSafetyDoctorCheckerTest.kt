package ai.icen.fw.application.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeploymentSafetyDoctorCheckerTest {
    private val context = DoctorCheckContext(Identifier("sensitive-tenant"))

    @Test
    fun `reports healthy when production extension points replace both bootstrap adapters`() {
        val result = DeploymentSafetyDoctorChecker().check(context)

        assertEquals(DeploymentSafetyDoctorChecker.NAME, result.checkerName)
        assertEquals(DoctorStatus.HEALTHY, result.status)
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun `reports each active bootstrap mode without leaking tenant or storage values`() {
        val result = DeploymentSafetyDoctorChecker(
            fixedTenantProviderActive = true,
            localStorageAdapterActive = true,
        ).check(context)

        assertEquals(DoctorStatus.WARNING, result.status)
        assertEquals("fixed-tenant,local-filesystem", result.evidence["activeModes"])
        assertNotNull(result.repairSuggestion)
        assertTrue(result.repairSuggestion!!.contains("TenantProvider"))
        assertTrue(result.repairSuggestion!!.contains("StorageAdapter"))
        assertFalse(result.toText().contains("sensitive-tenant"))
    }

    @Test
    fun `reports only the bootstrap mode that is actually active`() {
        val fixedTenant = DeploymentSafetyDoctorChecker(fixedTenantProviderActive = true).check(context)
        val localStorage = DeploymentSafetyDoctorChecker(localStorageAdapterActive = true).check(context)

        assertEquals("fixed-tenant", fixedTenant.evidence["activeModes"])
        assertEquals("local-filesystem", localStorage.evidence["activeModes"])
    }

    private fun ai.icen.fw.core.result.DoctorCheckResult.toText(): String =
        listOf(reason, evidence.toString(), repairSuggestion.orEmpty()).joinToString(" ")
}
