package ai.icen.fw.adapter.opentelemetry

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.observability.FileWeftGauge
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.LogContext
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Collector-style acceptance test for the OpenTelemetry adapter.
 *
 * Uses in-memory OTLP pipeline equivalents to assert that:
 * - trace, metric and log signals share the same trace id;
 * - metrics are redacted (no tenant/document/resource identifiers);
 * - metric tag cardinality is bounded to an explicit allow-list;
 * - gauge observations only use the low-cardinality state dimension.
 */
class OpenTelemetryCollectorCorrelationTest {

    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var metricReader: InMemoryMetricReader
    private lateinit var logExporter: InMemoryLogRecordExporter
    private lateinit var sdk: OpenTelemetrySdk
    private lateinit var traceContextProvider: OpenTelemetryTraceContextProvider
    private lateinit var metrics: OpenTelemetryFileWeftMetrics
    private lateinit var gauges: OpenTelemetryFileWeftGauges
    private lateinit var logger: OpenTelemetryFileWeftLogger

    @BeforeEach
    fun setUp() {
        spanExporter = InMemorySpanExporter.create()
        metricReader = InMemoryMetricReader.create()
        logExporter = InMemoryLogRecordExporter.create()

        sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build(),
            )
            .setMeterProvider(
                SdkMeterProvider.builder()
                    .registerMetricReader(metricReader)
                    .build(),
            )
            .setLoggerProvider(
                SdkLoggerProvider.builder()
                    .addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter))
                    .build(),
            )
            .build()

        val meter = sdk.sdkMeterProvider.get("ai.icen.fw.test")
        val otelLogger = sdk.sdkLoggerProvider.get("ai.icen.fw.test")

        traceContextProvider = OpenTelemetryTraceContextProvider()
        metrics = OpenTelemetryFileWeftMetrics(meter)
        gauges = OpenTelemetryFileWeftGauges(meter)
        logger = OpenTelemetryFileWeftLogger(otelLogger)
    }

    @AfterEach
    fun tearDown() {
        sdk.shutdown()
    }

    @Test
    fun `trace metric and log are correlated and metrics are redacted`() {
        val tracer = sdk.getTracer("ai.icen.fw.test")
        val span = tracer.spanBuilder("fileweft-operation").startSpan()

        val traceContext: ai.icen.fw.core.context.TraceContext?
        span.makeCurrent().use {
            traceContext = traceContextProvider.currentTraceContext()
            assertNotNull(traceContext, "Trace context provider must read the active OTel span")

            metrics.increment(
                FileWeftMetric.UPLOAD_COUNT,
                mapOf(
                    "taskType" to "document-upload",
                    "connector" to "local",
                    "outcome" to "success",
                    // These must be redacted from metric labels.
                    "tenantId" to "tenant-42",
                    "documentId" to "doc-7",
                    "userId" to "user-99",
                ),
            )

            gauges.set(
                FileWeftGauge.OUTBOX_BACKLOG,
                7.0,
                mapOf(
                    "state" to "ready",
                    // These must be redacted from gauge labels.
                    "tenantId" to "tenant-42",
                    "documentId" to "doc-7",
                ),
            )

            logger.info(
                "upload completed",
                LogContext(
                    tenantId = Identifier("tenant-42"),
                    documentId = Identifier("doc-7"),
                    traceId = Identifier(traceContext!!.traceId.value),
                ),
            )
        }
        span.end()

        assertEquals(1, spanExporter.finishedSpanItems.size, "Exactly one span must be exported")
        val exportedTraceId = spanExporter.finishedSpanItems[0].traceId

        val allMetrics = metricReader.collectAllMetrics()
        val uploadMetric = allMetrics.find { it.name == "fileweft.upload_count" }
        assertNotNull(uploadMetric, "Counter metric must be exported")

        val counterData = uploadMetric!!.longSumData
        assertEquals(1, counterData.points.size, "Counter must have a single point")
        val counterPoint = counterData.points.first()
        assertEquals(1L, counterPoint.value, "Counter must record a single increment")

        val counterAttrs = counterPoint.attributes.asMap()
        assertTrue(counterAttrs.containsKey(AttributeKey.stringKey("taskType")), "Allowed tag taskType must be present")
        assertTrue(counterAttrs.containsKey(AttributeKey.stringKey("connector")), "Allowed tag connector must be present")
        assertTrue(counterAttrs.containsKey(AttributeKey.stringKey("outcome")), "Allowed tag outcome must be present")
        assertFalse(counterAttrs.containsKey(AttributeKey.stringKey("tenantId")), "Tenant id must be redacted from metrics")
        assertFalse(counterAttrs.containsKey(AttributeKey.stringKey("documentId")), "Document id must be redacted from metrics")
        assertFalse(counterAttrs.containsKey(AttributeKey.stringKey("userId")), "Arbitrary tags must be redacted from metrics")

        val backlogMetric = allMetrics.find { it.name == "fileweft.outbox_backlog" }
        assertNotNull(backlogMetric, "Gauge metric must be exported")
        val gaugeData = backlogMetric!!.doubleGaugeData
        assertEquals(1, gaugeData.points.size, "Gauge must have a single point")
        val gaugePoint = gaugeData.points.first()
        assertEquals(7.0, gaugePoint.value, 0.001, "Gauge must record the supplied value")
        val gaugeAttrs = gaugePoint.attributes.asMap()
        assertEquals("ready", gaugeAttrs[AttributeKey.stringKey("state")], "Gauge state tag must be preserved")
        assertFalse(gaugeAttrs.containsKey(AttributeKey.stringKey("tenantId")), "Tenant id must be redacted from gauges")
        assertFalse(gaugeAttrs.containsKey(AttributeKey.stringKey("documentId")), "Document id must be redacted from gauges")

        assertEquals(1, logExporter.finishedLogRecordItems.size, "Exactly one log record must be exported")
        val logRecord = logExporter.finishedLogRecordItems[0]
        assertEquals("upload completed", logRecord.body.asString(), "Log body must match the message")
        assertEquals(exportedTraceId, logRecord.spanContext.traceId, "Log record must carry the active trace id")

        val logAttrs = logRecord.attributes.asMap()
        assertEquals("tenant-42", logAttrs[AttributeKey.stringKey("fileweft.tenant_id")], "Tenant id must be in log attributes")
        assertEquals("doc-7", logAttrs[AttributeKey.stringKey("fileweft.document_id")], "Document id must be in log attributes")
        assertEquals(
            exportedTraceId,
            logAttrs[AttributeKey.stringKey("fileweft.trace_id")],
            "Trace id must be in log attributes",
        )
    }
}
