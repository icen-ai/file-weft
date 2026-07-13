package ai.icen.fw.testkit.observability

import ai.icen.fw.spi.observability.TraceContextProvider
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

abstract class TraceContextProviderContractTest {
    protected abstract val traceContextProvider: TraceContextProvider

    @Test
    fun `current trace context is readable without throwing`() {
        val context = traceContextProvider.currentTraceContext()

        context?.let {
            assertNotNull(it.traceId, "A present trace context must carry a trace identifier.")
        }
    }
}
