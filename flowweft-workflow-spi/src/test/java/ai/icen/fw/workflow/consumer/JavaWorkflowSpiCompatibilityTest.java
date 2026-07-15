package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.spi.WorkflowBusinessCalendar;
import ai.icen.fw.workflow.spi.WorkflowConformanceCoverage;
import ai.icen.fw.workflow.spi.WorkflowConformanceEntry;
import ai.icen.fw.workflow.spi.WorkflowConformanceFeatureRef;
import ai.icen.fw.workflow.spi.WorkflowConformanceStatus;
import ai.icen.fw.workflow.spi.WorkflowDefinitionCodec;
import ai.icen.fw.workflow.spi.WorkflowDefinitionCodecDescriptor;
import ai.icen.fw.workflow.spi.WorkflowDefinitionConformanceReport;
import ai.icen.fw.workflow.spi.WorkflowDefinitionDecodeRequest;
import ai.icen.fw.workflow.spi.WorkflowDefinitionDecodeResult;
import ai.icen.fw.workflow.spi.WorkflowDefinitionEncodeRequest;
import ai.icen.fw.workflow.spi.WorkflowDefinitionEncodeResult;
import ai.icen.fw.workflow.spi.WorkflowDefinitionFormatRef;
import ai.icen.fw.workflow.spi.WorkflowDefinitionMediaType;
import ai.icen.fw.workflow.spi.WorkflowPayloadValidationReceipt;
import ai.icen.fw.workflow.spi.WorkflowProviderCallContext;
import ai.icen.fw.workflow.spi.WorkflowProviderFailure;
import ai.icen.fw.workflow.spi.WorkflowProviderOutcome;
import ai.icen.fw.workflow.spi.WorkflowSchemaRef;
import ai.icen.fw.workflow.spi.WorkflowStructuredPayload;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowSpiCompatibilityTest {
    @Test
    void factoriesAndCompletionStageContractsAreUsableFromPureJava() {
        WorkflowProviderCallContext context = WorkflowProviderCallContext.of(
            "request-1",
            "tenant-a",
            "provider-a",
            "r1",
            "java-consumer",
            1_000L,
            1_200L,
            4_096,
            4_096,
            16
        );
        WorkflowSchemaRef schema = WorkflowSchemaRef.of(
            "schema-provider",
            "payload",
            "1",
            repeat('a', 64)
        );
        WorkflowStructuredPayload rawPayload = WorkflowStructuredPayload.of(
            schema,
            "{}".getBytes(StandardCharsets.UTF_8)
        );
        WorkflowPayloadValidationReceipt validationReceipt = WorkflowPayloadValidationReceipt.of(
            "schema-validator",
            "r1",
            schema,
            rawPayload.getCanonicalPayloadDigest(),
            0,
            repeat('b', 64)
        );
        WorkflowStructuredPayload payload = WorkflowStructuredPayload.validated(rawPayload, validationReceipt);
        WorkflowConformanceFeatureRef feature = WorkflowConformanceFeatureRef.of("node-1", "human-task");
        WorkflowConformanceEntry entry = WorkflowConformanceEntry.of(
            "node-1",
            "human-task",
            WorkflowConformanceStatus.SUPPORTED,
            "exact"
        );
        WorkflowConformanceCoverage coverage = WorkflowConformanceCoverage.complete(
            1,
            Collections.singletonList(feature)
        );
        WorkflowDefinitionFormatRef format = WorkflowDefinitionFormatRef.of(
            "flowweft-neutral",
            "1",
            WorkflowDefinitionMediaType.FLOWWEFT_JSON,
            repeat('c', 64)
        );
        WorkflowDefinitionConformanceReport legacyReport = WorkflowDefinitionConformanceReport.of(
            format,
            repeat('d', 64),
            repeat('e', 64),
            Collections.singletonList(entry)
        );
        WorkflowDefinitionConformanceReport completeReport = WorkflowDefinitionConformanceReport.complete(
            format,
            repeat('d', 64),
            repeat('e', 64),
            Collections.singletonList(entry),
            coverage
        );
        WorkflowBusinessCalendar calendar = request -> CompletableFuture.completedFuture(null);
        WorkflowDefinitionCodec codec = new WorkflowDefinitionCodec() {
            @Override
            public WorkflowDefinitionCodecDescriptor descriptor() {
                return null;
            }

            @Override
            public CompletionStage<WorkflowDefinitionDecodeResult> decode(WorkflowDefinitionDecodeRequest request) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<WorkflowDefinitionEncodeResult> encode(WorkflowDefinitionEncodeRequest request) {
                return CompletableFuture.completedFuture(null);
            }
        };

        assertEquals("tenant-a", context.getTenantId());
        assertEquals(2, payload.getSize());
        assertFalse(rawPayload.getValidated());
        assertTrue(payload.getValidated());
        assertEquals(0, payload.getFieldCount());
        assertFalse(legacyReport.getExecutable());
        assertTrue(completeReport.getExecutable());
        assertEquals(coverage.getManifestDigest(), completeReport.getCoverage().getManifestDigest());
        assertNotNull(calendar);
        assertNotNull(codec);
        assertEquals("retry-later", WorkflowProviderFailure.of("retry-later", true).getCode());
        assertEquals("future-outcome", WorkflowProviderOutcome.of("future-outcome").getCode());
    }

    private static String repeat(char character, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(character);
        }
        return result.toString();
    }
}
