package ai.icen.fw.application.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker

/** Explicitly reports a capability that is not installed in this deployment. */
class UnavailableDoctorChecker(
    private val checkerName: String,
    private val reason: String,
    private val repairSuggestion: String,
) : DoctorChecker {
    init {
        require(checkerName.isNotBlank()) { "Doctor checker name must not be blank." }
        require(reason.isNotBlank()) { "Doctor checker reason must not be blank." }
        require(repairSuggestion.isNotBlank()) { "Doctor repair suggestion must not be blank." }
    }

    override fun name(): String = checkerName

    override fun check(context: DoctorCheckContext): DoctorCheckResult =
        DoctorCheckResult(checkerName, DoctorStatus.SKIPPED, reason, repairSuggestion = repairSuggestion)
}
