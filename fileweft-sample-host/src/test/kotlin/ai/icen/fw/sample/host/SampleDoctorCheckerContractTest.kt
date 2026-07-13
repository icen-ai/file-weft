package ai.icen.fw.sample.host

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.testkit.doctor.DoctorCheckerContractTest

class SampleDoctorCheckerContractTest : DoctorCheckerContractTest() {

    override val doctorChecker = SampleDoctorChecker()

    override fun checkContext(): DoctorCheckContext {
        return DoctorCheckContext(
            tenantId = Identifier("sample-tenant"),
        )
    }
}
