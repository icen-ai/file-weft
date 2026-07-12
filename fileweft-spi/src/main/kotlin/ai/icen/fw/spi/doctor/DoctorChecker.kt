package ai.icen.fw.spi.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult

/**
 * Extension contract for a bounded, side-effect-free production diagnostic.
 * Implementations must return actionable results instead of throwing for an
 * expected unhealthy dependency state.
 */
interface DoctorChecker {
    fun name(): String

    fun check(context: DoctorCheckContext): DoctorCheckResult
}
