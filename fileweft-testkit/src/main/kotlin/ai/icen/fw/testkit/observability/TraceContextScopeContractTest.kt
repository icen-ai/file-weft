package ai.icen.fw.testkit.observability

import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.observability.TraceContextScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Reusable nested bind, restore, and clear contract for worker trace carriers. */
abstract class TraceContextScopeContractTest : TraceContextProviderContractTest() {
    protected abstract val traceContextScope: TraceContextScope

    final override val traceContextProvider: TraceContextScope
        get() = traceContextScope

    @Test
    fun `binds nested contexts and supports explicit restoration and clearing`() {
        val outer = TraceContext(Identifier("trace-outer"), Identifier("trace-parent"))
        val inner = TraceContext(Identifier("trace-inner"), outer.traceId)

        try {
            traceContextScope.bindTraceContext(outer)
            assertContext(outer, traceContextScope.currentTraceContext())

            traceContextScope.bindTraceContext(inner)
            assertContext(inner, traceContextScope.currentTraceContext())

            traceContextScope.bindTraceContext(outer)
            assertContext(outer, traceContextScope.currentTraceContext())

            traceContextScope.bindTraceContext(null)
            assertNull(traceContextScope.currentTraceContext(), "Binding null must clear the trace carrier.")
        } finally {
            traceContextScope.bindTraceContext(null)
        }
    }

    private fun assertContext(expected: TraceContext, actual: TraceContext?) {
        requireNotNull(actual) { "A bound trace context must be readable." }
        assertEquals(expected.traceId, actual.traceId)
        assertEquals(expected.parentTraceId, actual.parentTraceId)
    }
}
