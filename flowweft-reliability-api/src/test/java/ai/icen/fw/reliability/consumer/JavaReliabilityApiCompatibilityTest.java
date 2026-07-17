package ai.icen.fw.reliability.consumer;

import ai.icen.fw.reliability.api.ReliabilityAction;
import ai.icen.fw.reliability.api.ReliabilityAuthorizationSnapshot;
import ai.icen.fw.reliability.api.ReliabilityBackupArtifact;
import ai.icen.fw.reliability.api.ReliabilityBackupManifest;
import ai.icen.fw.reliability.api.ReliabilityBackupManifestContent;
import ai.icen.fw.reliability.api.ReliabilityCallContext;
import ai.icen.fw.reliability.api.ReliabilityComponentKind;
import ai.icen.fw.reliability.api.ReliabilityComponentScope;
import ai.icen.fw.reliability.api.ReliabilityConsistentCut;
import ai.icen.fw.reliability.api.ReliabilityEncryptionDescriptor;
import ai.icen.fw.reliability.api.ReliabilityEnvironmentKind;
import ai.icen.fw.reliability.api.ReliabilityEnvironmentRef;
import ai.icen.fw.reliability.api.ReliabilityErrorBudgetEvaluation;
import ai.icen.fw.reliability.api.ReliabilityImmutableManifestSeal;
import ai.icen.fw.reliability.api.ReliabilityKeyReference;
import ai.icen.fw.reliability.api.ReliabilityMetricCode;
import ai.icen.fw.reliability.api.ReliabilityMetricComponentClass;
import ai.icen.fw.reliability.api.ReliabilityMetricEvidence;
import ai.icen.fw.reliability.api.ReliabilityMetricOutcome;
import ai.icen.fw.reliability.api.ReliabilityPrincipalRef;
import ai.icen.fw.reliability.api.ReliabilityProviderSpi;
import ai.icen.fw.reliability.api.ReliabilityPurpose;
import ai.icen.fw.reliability.api.ReliabilityRecoveryObjective;
import ai.icen.fw.reliability.api.ReliabilityRecoveryObjectiveSet;
import ai.icen.fw.reliability.api.ReliabilityResourceRef;
import ai.icen.fw.reliability.api.ReliabilitySliKind;
import ai.icen.fw.reliability.api.ReliabilitySliObservation;
import ai.icen.fw.reliability.api.ReliabilitySloEvaluationRequest;
import ai.icen.fw.reliability.api.ReliabilitySloEvaluator;
import ai.icen.fw.reliability.api.ReliabilitySloObjective;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaReliabilityApiCompatibilityTest {
    @Test
    void javaConsumerCanConstructAndEvaluatePublicContracts() throws ReflectiveOperationException {
        ReliabilityResourceRef resource = ReliabilityResourceRef.of("service", "api", "1", digest('a'));
        ReliabilityPrincipalRef principal = ReliabilityPrincipalRef.of("user", "operator");
        ReliabilityAuthorizationSnapshot authorization = ReliabilityAuthorizationSnapshot.of(
            "auth-1",
            "tenant-a",
            principal,
            ReliabilityPurpose.EVALUATE_SLO,
            ReliabilityAction.EVALUATE_SLO,
            resource,
            "host-authz",
            "1",
            "1",
            digest('b'),
            9_900L,
            11_100L
        );
        ReliabilityCallContext context = ReliabilityCallContext.of(
            "request-1",
            "tenant-a",
            principal,
            ReliabilityPurpose.EVALUATE_SLO,
            ReliabilityAction.EVALUATE_SLO,
            resource,
            authorization,
            digest('c'),
            10_000L,
            11_000L
        );
        ReliabilitySloObjective objective = ReliabilitySloObjective.of(
            "availability",
            "1",
            digest('d'),
            resource,
            ReliabilitySliKind.AVAILABILITY,
            990_000L,
            1_000L,
            100L,
            2_000L,
            0L,
            20_000L
        );
        ReliabilitySliObservation observation = ReliabilitySliObservation.of(
            objective.getObjectiveDigest(), 8_000L, 9_000L, 995L, 1_000L, 9_100L
        );
        ReliabilitySloEvaluationRequest request = ReliabilitySloEvaluationRequest.of(
            context, objective, observation, 8_000L, 9_000L, 10_000L
        );

        ReliabilityErrorBudgetEvaluation result = ReliabilitySloEvaluator.STANDARD.evaluate(request);

        assertEquals(Long.valueOf(500_000L), result.getBudgetConsumedPpm());
        assertEquals(Long.valueOf(500_000L), result.getRemainingBudgetPpm());
        assertNotNull(ReliabilityProviderSpi.class.getMethod("restore",
            Class.forName("ai.icen.fw.reliability.api.ReliabilityRestoreRequest")));

        ReliabilityMetricEvidence metric = ReliabilityMetricEvidence.of(
            ReliabilityMetricCode.SLO_EVALUATION_RESULT,
            ReliabilityMetricOutcome.SLO_SATISFIED,
            null,
            ReliabilityMetricComponentClass.OTHER,
            10_000L
        );
        assertEquals(ReliabilityMetricOutcome.SLO_SATISFIED, metric.getOutcome());
    }

    @Test
    void javaRecoveryManifestEnforcesConsistentCutAndRpoBounds() {
        ReliabilityBackupManifest manifest = recoveryFixture().manifest;

        // single database workflow-only topology is a valid consistent-cut manifest
        List<ReliabilityBackupArtifact> artifacts = manifest.getContent().getArtifacts();
        assertEquals(1, artifacts.size());
        assertEquals(
            ReliabilityComponentKind.DATABASE,
            artifacts.get(0).getScope().getKind()
        );
        assertTrue(manifest.getManifestDigest().matches("[0-9a-f]{64}"));

        // manifest rejects a component recovery point outside its RPO
        RecoveryFixture fixture = recoveryFixture();
        long cutAt = fixture.cut.getCutAtEpochMilli();
        ReliabilityBackupArtifact oldArtifact = backupArtifact(
            fixture.scope,
            fixture.cut.getCutDigest(),
            cutAt - 1_001L,
            cutAt + 100L
        );
        assertThrows(IllegalArgumentException.class, () ->
            ReliabilityBackupManifestContent.of(
                "manifest-old",
                "1",
                fixture.objectives,
                fixture.cut,
                Collections.singletonList(oldArtifact),
                cutAt + 200L
            )
        );
    }

    private static final class RecoveryFixture {
        final ReliabilityEnvironmentRef source;
        final ReliabilityComponentScope scope;
        final ReliabilityRecoveryObjectiveSet objectives;
        final ReliabilityConsistentCut cut;
        final ReliabilityBackupManifest manifest;

        RecoveryFixture(
            ReliabilityEnvironmentRef source,
            ReliabilityComponentScope scope,
            ReliabilityRecoveryObjectiveSet objectives,
            ReliabilityConsistentCut cut,
            ReliabilityBackupManifest manifest
        ) {
            this.source = source;
            this.scope = scope;
            this.objectives = objectives;
            this.cut = cut;
            this.manifest = manifest;
        }
    }

    private static RecoveryFixture recoveryFixture() {
        ReliabilityEnvironmentRef source = sourceEnvironment();
        ReliabilityComponentScope scope = ReliabilityComponentScope.of(
            ReliabilityComponentKind.DATABASE, "workflow-db", "1", digest('3')
        );
        ReliabilityRecoveryObjective objective = ReliabilityRecoveryObjective.of(
            scope, 1_000L, 10_000L
        );
        ReliabilityRecoveryObjectiveSet objectives = ReliabilityRecoveryObjectiveSet.of(
            "recovery-policy",
            "1",
            digest('4'),
            source,
            Collections.singletonList(objective),
            0L,
            1_000_000L
        );
        ReliabilityConsistentCut cut = ReliabilityConsistentCut.of(
            "cut-1", source, objectives.getTopologyDigest(), 100_000L
        );
        ReliabilityBackupArtifact artifact = backupArtifact(
            scope, cut.getCutDigest(), 99_500L, 100_100L
        );
        ReliabilityBackupManifestContent content = ReliabilityBackupManifestContent.of(
            "manifest-1", "1", objectives, cut, Collections.singletonList(artifact), 100_200L
        );
        ReliabilityKeyReference key = ReliabilityKeyReference.of(
            "kms", "key-1", "1", digest('5')
        );
        ReliabilityImmutableManifestSeal seal = ReliabilityImmutableManifestSeal.of(
            content, "1", "ed25519", key, digest('6'), 100_300L
        );
        return new RecoveryFixture(source, scope, objectives, cut, ReliabilityBackupManifest.of(content, seal));
    }

    private static ReliabilityEnvironmentRef sourceEnvironment() {
        ReliabilityResourceRef resource = ReliabilityResourceRef.of(
            ReliabilityEnvironmentRef.RESOURCE_TYPE, "production-a", "1", digest('0')
        );
        return ReliabilityEnvironmentRef.of(
            "tenant-a", "production-a", ReliabilityEnvironmentKind.PRODUCTION, resource, digest('a')
        );
    }

    private static ReliabilityBackupArtifact backupArtifact(
        ReliabilityComponentScope scope,
        String cutDigest,
        long recoveryPoint,
        long capturedAt
    ) {
        ReliabilityKeyReference key = ReliabilityKeyReference.of(
            "kms", "key-1", "1", digest('5')
        );
        ReliabilityEncryptionDescriptor encryption = ReliabilityEncryptionDescriptor.of(
            "aes-256-gcm", key, digest('8'), digest('9')
        );
        return ReliabilityBackupArtifact.immutableEncrypted(
            scope,
            "snapshot-1",
            "provider",
            "1",
            cutDigest,
            recoveryPoint,
            capturedAt,
            1_024L,
            digest('b'),
            encryption
        );
    }

    private static String digest(char value) {
        StringBuilder builder = new StringBuilder(64);
        for (int index = 0; index < 64; index++) builder.append(value);
        return builder.toString();
    }
}
