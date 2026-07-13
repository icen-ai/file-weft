package ai.icen.fw.sample.host

import ai.icen.fw.testkit.observability.TraceContextProviderContractTest

class SampleTraceContextProviderContractTest : TraceContextProviderContractTest() {

    override val traceContextProvider = SampleTraceContextProvider()
}
