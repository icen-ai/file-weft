package ai.icen.fw.application.doctor

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker
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
