package ai.icen.fw.domain.retention;

import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRetentionDeletionInteropTest {
    @Test
    void evaluatesProviderNeutralDeletionContractsFromJava() {
        Identifier tenantId = new Identifier("tenant-java");
        Identifier documentId = new Identifier("document-java");
        Identifier principalId = new Identifier("principal-java");
        RetentionPolicySnapshot policy = new RetentionPolicySnapshot(
            tenantId,
            "DOCUMENT",
            documentId,
            "records-policy",
            "policy-r1",
            RetentionPolicyMode.RETAIN_UNTIL,
            0L,
            900L,
            2_000L,
            1_000L
        );
        LegalHoldSetSnapshot holds = new LegalHoldSetSnapshot(
            tenantId,
            "DOCUMENT",
            documentId,
            "holds-r1",
            900L,
            2_000L,
            true,
            Collections.emptyList()
        );
        DeletionAuthorizationSnapshot authorization = new DeletionAuthorizationSnapshot(
            tenantId,
            "DOCUMENT",
            documentId,
            principalId,
            "auth-r1",
            900L,
            2_000L,
            true,
            true
        );
        SecureDeletionRequest request = new SecureDeletionRequest(
            new Identifier("decision-java"),
            new Identifier("plan-java"),
            new Identifier("tombstone-java"),
            tenantId,
            "DOCUMENT",
            documentId,
            11L,
            principalId,
            policy,
            holds,
            authorization
        );

        SecureDeletionDecision decision = new RetentionDeletionDecisionEngine(
            Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC)
        ).evaluate(request);

        assertTrue(decision.isAllowed());
        SecureDeletionPlan plan = decision.getPlan();
        assertNotNull(plan);
        assertEquals(SecureDeletionStage.PERSIST_TOMBSTONE, plan.getSteps().get(0).getStage());
        assertEquals(SecureDeletionStage.APPEND_COMPLETION_AUDIT, plan.getSteps().get(6).getStage());
        assertEquals(11L, plan.getTombstone().getResourceRevision());
        assertEquals("policy-r1", decision.getAuditEvidence().getPolicyRevision());
    }
}
