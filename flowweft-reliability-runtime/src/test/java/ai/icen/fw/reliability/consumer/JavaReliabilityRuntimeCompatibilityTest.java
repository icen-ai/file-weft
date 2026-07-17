package ai.icen.fw.reliability.consumer;

import ai.icen.fw.reliability.api.ReliabilityAction;
import ai.icen.fw.reliability.api.ReliabilityPrincipalRef;
import ai.icen.fw.reliability.api.ReliabilityPurpose;
import ai.icen.fw.reliability.api.ReliabilityResourceRef;
import ai.icen.fw.reliability.runtime.ReliabilityRunRepository;
import ai.icen.fw.reliability.runtime.ReliabilityRuntimeMetric;
import ai.icen.fw.reliability.runtime.ReliabilityRuntimeMetricCode;
import ai.icen.fw.reliability.runtime.ReliabilityStoreCode;
import ai.icen.fw.reliability.runtime.ReliabilityTrustedInvocation;
import ai.icen.fw.reliability.runtime.ReliabilityWorkerCommand;
import ai.icen.fw.reliability.runtime.ReliabilityWorkerMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaReliabilityRuntimeCompatibilityTest {
    @Test
    void javaConsumerCanUseTrustedInvocationWorkerAndPersistenceContracts() throws ReflectiveOperationException {
        ReliabilityResourceRef resource = ReliabilityResourceRef.of("environment", "prod", "1", digest('a'));
        ReliabilityTrustedInvocation invocation = ReliabilityTrustedInvocation.of(
            "tenant-a",
            ReliabilityPrincipalRef.of("user", "operator"),
            ReliabilityPurpose.CREATE_BACKUP,
            ReliabilityAction.CREATE_BACKUP,
            resource,
            "raw-key-is-not-retained",
            100_000L,
            101_000L
        );
        ReliabilityWorkerCommand command = ReliabilityWorkerCommand.of(
            invocation, "run-1", "worker-1", ReliabilityWorkerMode.ADVANCE, 30_000L
        );

        assertEquals("run-1", command.getRunId());
        assertEquals(64, invocation.getIdempotencyDigest().length());
        assertNotNull(ReliabilityRunRepository.class.getMethod(
            "compareAndSet", String.class, String.class, long.class, long.class,
            Class.forName("ai.icen.fw.reliability.runtime.ReliabilityRun"),
            Class.forName("ai.icen.fw.reliability.runtime.ReliabilityOutboxRecord")
        ));
        assertEquals(
            ReliabilityRuntimeMetricCode.INTENT_CREATED,
            ReliabilityRuntimeMetric.of(ReliabilityRuntimeMetricCode.INTENT_CREATED, null).getCode()
        );
        assertEquals(ReliabilityStoreCode.STORED.name(), "STORED");
    }

    private static String digest(char value) {
        StringBuilder builder = new StringBuilder(64);
        for (int index = 0; index < 64; index++) builder.append(value);
        return builder.toString();
    }
}
