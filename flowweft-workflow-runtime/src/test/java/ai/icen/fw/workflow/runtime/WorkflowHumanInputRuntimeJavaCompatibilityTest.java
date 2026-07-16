package ai.icen.fw.workflow.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkflowHumanInputRuntimeJavaCompatibilityTest {
    @Test
    void durableHumanInputContractsAreUsableFromPureJava() {
        WorkflowHumanInputProviderProfile profile = WorkflowHumanInputProviderProfile.of(
            "provider-a",
            "r1",
            1_000L,
            4_096,
            4_096,
            16
        );
        WorkflowHumanInputReservationRequest request = WorkflowHumanInputReservationRequest.of(
            "tenant-a",
            "idempotency-1",
            WorkflowHumanInputOperation.FORM_VALIDATE,
            repeat('a', 64),
            1_000L,
            2_000L
        );
        WorkflowHumanInputIdempotencyPort port = new WorkflowHumanInputIdempotencyPort() {
            @Override
            public WorkflowHumanInputReservationResult reserve(WorkflowHumanInputReservationRequest value) {
                return WorkflowHumanInputReservationResult.reserved(
                    WorkflowHumanInputReservation.of(
                        value.getTenantId(),
                        value.getIdempotencyKey(),
                        value.getOperation(),
                        value.getRequestDigest(),
                        "lease-1",
                        1L,
                        value.getLeaseUntilEpochMilli()
                    )
                );
            }

            @Override
            public WorkflowHumanInputIdempotencyWriteResult complete(
                WorkflowHumanInputReservation reservation,
                WorkflowHumanInputIdempotencyRecord record
            ) {
                return WorkflowHumanInputIdempotencyWriteResult.stored(record);
            }
        };

        WorkflowHumanInputReservationResult result = port.reserve(request);

        assertEquals("provider-a", profile.getProviderId());
        assertEquals(WorkflowHumanInputReservationCode.RESERVED, result.getCode());
        assertNotNull(result.getReservation());
    }

    private static String repeat(char character, int count) {
        StringBuilder value = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            value.append(character);
        }
        return value.toString();
    }
}
