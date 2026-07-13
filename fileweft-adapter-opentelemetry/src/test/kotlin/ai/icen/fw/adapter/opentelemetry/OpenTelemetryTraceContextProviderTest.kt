package ai.icen.fw.adapter.opentelemetry

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OpenTelemetryTraceContextProviderTest {
    private val provider = OpenTelemetryTraceContextProvider()

    @Test
    fun `returns null when no valid span is active`() {
        assertNull(provider.currentTraceContext(), "Provider must return null outside an active OTel span.")
    }

    @Test
    fun `maps the active OTel trace id to FileWeft trace context`() {
        val tracer = createTracer()
        val span = tracer.spanBuilder("fileweft-test").startSpan()
        span.makeCurrent().use {
            val context = provider.currentTraceContext()

            assertNotNull(context, "Provider must expose an active OTel span context.")
            assertEquals(span.spanContext.traceId, context?.traceId?.value)
        }
        span.end()
    }

    private fun createTracer(): Tracer {
        val sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().build())
            .build()
        return sdk.getTracer("ai.icen.fw.test")
    }
}
