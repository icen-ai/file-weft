package com.fileweft.application.doctor

import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorStatus
import com.fileweft.spi.doctor.DoctorChecker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransactionalDoctorCheckerTest {
    @Test
    fun `runs its delegate inside a short application transaction`() {
        val transaction = RecordingTransaction()
        val checker = TransactionalDoctorChecker(
            object : DoctorChecker {
                override fun name(): String = "repository"
                override fun check(context: DoctorCheckContext): DoctorCheckResult = DoctorCheckResult("repository", DoctorStatus.HEALTHY, "ok")
            },
            transaction,
        )

        val result = checker.check(DoctorCheckContext(Identifier("tenant"), Identifier("document")))

        assertEquals(1, transaction.calls)
        assertEquals(DoctorStatus.HEALTHY, result.status)
    }

    private class RecordingTransaction : ApplicationTransaction {
        var calls = 0
        override fun <T> execute(action: () -> T): T {
            calls++
            return action()
        }
    }
}
