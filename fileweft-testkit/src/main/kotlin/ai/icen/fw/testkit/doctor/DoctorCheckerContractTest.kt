package ai.icen.fw.testkit.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.doctor.DoctorChecker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

abstract class DoctorCheckerContractTest {
    protected abstract val doctorChecker: DoctorChecker

    protected abstract fun checkContext(): DoctorCheckContext

    @Test
    fun `reports a non-blank checker name`() {
        assertTrue(doctorChecker.name().isNotBlank(), "Doctor checker name must not be blank.")
    }

    @Test
    fun `check result names the checker and provides a reason`() {
        val result = doctorChecker.check(checkContext())

        assertEquals(doctorChecker.name(), result.checkerName, "Result checker name must match the checker.")
        assertTrue(result.reason.isNotBlank(), "Doctor check result must include a reason.")
    }
}
