package ai.icen.fw.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SystemDoctorContractsTest {
    @Test
    fun `topology refuses to hide an unclassified production capability`() {
        val incomplete = SystemDoctorCapability.values().dropLast(1).map { capability ->
            requirement(capability)
        }

        assertFailsWith<IllegalArgumentException> { SystemDoctorTopology(incomplete) }
    }

    @Test
    fun `healthy probe cannot smuggle warning evidence`() {
        assertFailsWith<IllegalArgumentException> {
            SystemDoctorProbeResult(
                DIGEST,
                SystemDoctorCapability.DATABASE,
                SystemDoctorProbeState.HEALTHY,
                "v1",
                DIGEST,
                1L,
                listOf(
                    SystemDoctorProbeSignal(
                        SystemDoctorSeverity.WARNING,
                        SystemDoctorCode.DATABASE_HISTORY_GAP,
                        1L,
                        SystemDoctorBucket.PARTIAL,
                        SystemDoctorRepairAction.REPAIR_HISTORY,
                    ),
                ),
            )
        }
    }

    @Test
    fun `unhealthy stable code cannot be relabeled healthy`() {
        assertFailsWith<IllegalArgumentException> {
            SystemDoctorProbeSignal(
                SystemDoctorSeverity.HEALTHY,
                SystemDoctorCode.DISK_LOW,
                1L,
                SystemDoctorBucket.LOW,
                SystemDoctorRepairAction.FREE_DISK_SPACE,
            )
        }
    }

    @Test
    fun `public values render only stable aggregate vocabulary`() {
        val finding = SystemDoctorFinding(
            SystemDoctorFindingArea.CAPABILITY,
            SystemDoctorCapability.DISK,
            SystemDoctorSeverity.WARNING,
            SystemDoctorCode.DISK_LOW,
            1L,
            SystemDoctorBucket.LOW,
            SystemDoctorRepairAction.FREE_DISK_SPACE,
            true,
        )

        assertEquals("flowweft.doctor.disk.low", finding.code.toString())
        assertFalse(finding.toString().contains("/"))
        assertFalse(finding.toString().contains("=" + DIGEST))
    }

    private fun requirement(capability: SystemDoctorCapability): SystemDoctorProbeRequirement =
        SystemDoctorProbeRequirement(capability, capability.name.lowercase(), true, "v1", DIGEST, 100L, 1_000L)

    private companion object {
        const val DIGEST: String = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
