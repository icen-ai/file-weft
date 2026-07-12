package ai.icen.fw.application.doctor

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.spi.doctor.DoctorChecker

/**
 * Gives repository-backed diagnostics a short read transaction without placing
 * connector or other external health checks inside a database transaction.
 */
class TransactionalDoctorChecker(
    private val delegate: DoctorChecker,
    private val transaction: ApplicationTransaction,
) : DoctorChecker {
    override fun name(): String = delegate.name()

    override fun check(context: DoctorCheckContext): DoctorCheckResult = transaction.execute {
        delegate.check(context)
    }
}
