package ai.icen.fw.application.upload

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PresignedUploadDoctorCheckerTest {
    @Test
    fun `reports only bounded aggregate evidence`() {
        val checker = checker(
            PresignedUploadDiagnosticsSnapshot(4, 1, 2, 1, 3, 45),
        )

        val result = checker.check(DoctorCheckContext(Identifier("tenant-secret")))

        assertEquals(DoctorStatus.WARNING, result.status)
        assertEquals("3", result.evidence["orphanRiskCount"])
        assertFalse(result.evidence.values.any { it.contains("tenant-secret") })
    }

    @Test
    fun `fails closed when migration history cannot be queried`() {
        val repository = object : PresignedUploadDiagnosticsRepository {
            override fun snapshot(tenantId: Identifier?, now: Long): PresignedUploadDiagnosticsSnapshot {
                throw IllegalStateException("table and jdbc details must stay private")
            }
        }
        val checker = PresignedUploadDoctorChecker(
            PresignedUploadDiagnosticsService(repository, DirectTransaction, CLOCK),
        )

        val result = checker.check(DoctorCheckContext(Identifier("tenant-1")))

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals(emptyMap(), result.evidence)
        assertFalse(result.reason.contains("jdbc", ignoreCase = true))
    }

    private fun checker(snapshot: PresignedUploadDiagnosticsSnapshot): PresignedUploadDoctorChecker {
        val repository = object : PresignedUploadDiagnosticsRepository {
            override fun snapshot(tenantId: Identifier?, now: Long): PresignedUploadDiagnosticsSnapshot = snapshot
        }
        return PresignedUploadDoctorChecker(
            PresignedUploadDiagnosticsService(repository, DirectTransaction, CLOCK),
        )
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC)
    }
}
