package ai.icen.fw.observability

import ai.icen.fw.core.id.Identifier
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class ExecutorServiceSystemDoctorProbeExecutionPortTest {
    @Test
    fun `executor enforces hard timeout and returns no exception payload`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val port = ExecutorServiceSystemDoctorProbeExecutionPort(executor)
            val request = probeRequest()
            val execution = port.execute(
                SystemDoctorProbe {
                    Thread.sleep(10_000L)
                    error("secret should never become evidence")
                },
                request,
                20L,
            )

            assertEquals(SystemDoctorProbeExecutionState.TIMED_OUT, execution.state)
            assertEquals(null, execution.result)
            assertEquals("SystemDoctorProbeExecution(state=TIMED_OUT)", execution.toString())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probeRequest(): SystemDoctorProbeRequest {
        val request = SystemDoctorRequest(
            Identifier("request-a"),
            SystemDoctorScope.TENANT,
            Identifier("tenant-a"),
            Identifier("principal-a"),
            "human",
            "revision-a",
            1L,
            1_000L,
        )
        return SystemDoctorProbeRequest.from(
            request,
            SystemDoctorProbeRequirement(
                SystemDoctorCapability.DATABASE,
                "database-probe",
                true,
                "v1",
                DIGEST,
                100L,
                1_000L,
            ),
            2L,
            100L,
        )
    }

    private companion object {
        const val DIGEST: String = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
