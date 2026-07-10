package com.fileweft.application.doctor

import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.spi.doctor.DoctorChecker

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
