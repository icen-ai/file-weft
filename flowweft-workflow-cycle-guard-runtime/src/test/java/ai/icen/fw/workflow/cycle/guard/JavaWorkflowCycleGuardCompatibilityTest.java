package ai.icen.fw.workflow.cycle.guard;

import ai.icen.fw.workflow.api.WorkflowDefinitionRef;
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionAuthorizationReceipt;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationDecision;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationPort;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationRequest;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeHumanDecisionReceiptRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaWorkflowCycleGuardCompatibilityTest {
    private static final String DIGEST =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void javaHostCanImplementDurablePortsAndConstructRuntime() {
        WorkflowCycleBudgetPolicyPort policy = request -> null;
        WorkflowRuntimeAuthorizationPort authorization = new WorkflowRuntimeAuthorizationPort() {
            @Override
            public WorkflowRuntimeAuthorizationDecision authorize(
                WorkflowRuntimeAuthorizationRequest request
            ) {
                return null;
            }

            @Override
            public WorkflowHumanDecisionAuthorizationReceipt issueHumanDecisionReceipt(
                WorkflowRuntimeHumanDecisionReceiptRequest request
            ) {
                throw new UnsupportedOperationException();
            }
        };
        WorkflowCycleGuardPersistencePort persistence = new WorkflowCycleGuardPersistencePort() {
            @Override
            public WorkflowCycleGuardStoreResult consume(WorkflowCycleGuardConsumeRequest request) {
                return WorkflowCycleGuardStoreResult.failure(
                    WorkflowCycleGuardStoreCode.OUTCOME_UNKNOWN
                );
            }

            @Override
            public WorkflowCycleGuardLookupResult findReceipt(
                WorkflowCycleGuardReceiptLookup request
            ) {
                return WorkflowCycleGuardLookupResult.absent(
                    WorkflowCycleGuardLookupCode.NOT_FOUND
                );
            }

            @Override
            public WorkflowCycleGuardLookupResult load(WorkflowCycleGuardScope scope) {
                return WorkflowCycleGuardLookupResult.absent(
                    WorkflowCycleGuardLookupCode.NOT_FOUND
                );
            }
        };
        WorkflowCycleGuardRuntime runtime = new WorkflowCycleGuardRuntime(
            policy,
            authorization,
            persistence
        );
        WorkflowCycleGuardScope scope = WorkflowCycleGuardScope.of(
            "tenant-1",
            "instance-1",
            "definition-1",
            WorkflowDefinitionRef.of("expense", "1", DIGEST),
            "manager-review",
            WorkflowCycleGuardOperation.ADD_SIGN,
            0L,
            null
        );

        assertNotNull(runtime);
        assertEquals("add-sign", scope.getOperation().getCode());
        assertEquals(64, scope.getScopeDigest().length());
    }
}
