package ai.icen.fw.sample.host

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker

/**
 * Sample host Doctor checker that always reports a healthy state.
 */
class SampleDoctorChecker : DoctorChecker {

    override fun name(): String = "sample-host-doctor"

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        return DoctorCheckResult(
            checkerName = name(),
            status = DoctorStatus.HEALTHY,
            reason = "Sample host checker is operational.",
        )
    }
}
