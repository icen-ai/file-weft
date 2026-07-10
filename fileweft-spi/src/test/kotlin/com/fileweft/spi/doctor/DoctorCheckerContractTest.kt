package com.fileweft.spi.doctor

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DoctorCheckerContractTest {
    @Test
    fun `exposes a Java friendly request response checker contract`() {
        val checker = object : DoctorChecker {
            override fun name(): String = "sample"

            override fun check(context: DoctorCheckContext): DoctorCheckResult =
                DoctorCheckResult(name(), DoctorStatus.HEALTHY, "Sample checker is healthy.")
        }

        val result = checker.check(DoctorCheckContext(Identifier("tenant-1"), Identifier("document-1")))

        assertEquals("sample", checker.name())
        assertEquals(DoctorStatus.HEALTHY, result.status)
    }
}
