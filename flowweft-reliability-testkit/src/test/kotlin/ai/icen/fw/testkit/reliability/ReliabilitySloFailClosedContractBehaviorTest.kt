package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.runtime.ReliabilitySloSchedule
import ai.icen.fw.reliability.runtime.ReliabilitySloScheduleRepository

class ReliabilitySloFailClosedContractBehaviorTest : ReliabilitySloFailClosedContractTest() {
    override fun newRepository(initial: ReliabilitySloSchedule): ReliabilitySloScheduleRepository =
        InMemoryReliabilitySloRepository.containing(initial)
}
