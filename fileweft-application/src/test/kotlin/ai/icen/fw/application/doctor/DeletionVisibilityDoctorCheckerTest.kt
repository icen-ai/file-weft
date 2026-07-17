package ai.icen.fw.application.doctor

import ai.icen.fw.application.retention.DeletionVisibilityFence
import ai.icen.fw.application.retention.DeletionVisibilityQuery
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DeletionVisibilityDoctorCheckerTest {
    @Test
    fun `reports an active fence without exposing policy or authorization evidence`() {
        val checker = DeletionVisibilityDoctorChecker(
            object : DeletionVisibilityQuery {
                override fun findFence(
                    tenantId: Identifier,
                    resourceType: String,
                    resourceId: Identifier,
                ) = DeletionVisibilityFence(
                    Identifier("tombstone-a"), Identifier("plan-a"), tenantId,
                    resourceType, resourceId, 7, 100,
                )
            },
            DirectTransaction,
        )

        val result = checker.check(DoctorCheckContext(Identifier("tenant-a"), Identifier("document-a")))

        assertEquals(DoctorStatus.HEALTHY, result.status)
        assertEquals(mapOf("resourceRevision" to "7", "blockedAt" to "100"), result.evidence)
        assertFalse(result.evidence.keys.any { it.contains("policy", ignoreCase = true) })
        assertFalse(result.evidence.keys.any { it.contains("authorization", ignoreCase = true) })
    }

    @Test
    fun `reports unavailable projection as a sanitized error`() {
        val checker = DeletionVisibilityDoctorChecker(
            object : DeletionVisibilityQuery {
                override fun findFence(
                    tenantId: Identifier,
                    resourceType: String,
                    resourceId: Identifier,
                ): DeletionVisibilityFence? = throw IllegalStateException("jdbc:postgresql://secret")
            },
            DirectTransaction,
        )

        val result = checker.check(DoctorCheckContext(Identifier("tenant-a")))

        assertEquals(DoctorStatus.ERROR, result.status)
        assertFalse(result.reason.contains("jdbc:"))
        assertFalse(result.repairSuggestion.orEmpty().contains("secret"))
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
