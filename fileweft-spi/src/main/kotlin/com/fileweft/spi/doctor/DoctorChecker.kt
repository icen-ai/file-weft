package com.fileweft.spi.doctor

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.result.DoctorCheckResult

/**
 * Extension contract for a bounded, side-effect-free production diagnostic.
 * Implementations must return actionable results instead of throwing for an
 * expected unhealthy dependency state.
 */
interface DoctorChecker {
    fun name(): String

    fun check(context: DoctorCheckContext): DoctorCheckResult
}
