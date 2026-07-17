package ai.icen.fw.adapter.dify;

import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDifyPublicApiInteropTest {
    @Test
    void constructsTheAdministratorProfileFromJava() {
        DifySourceTrustPolicy trust = new DifySourceTrustPolicy(
                Collections.singletonList(URI.create("https://files.example.test"))
        );
        DifyKnowledgeBaseProfile profile = new DifyKnowledgeBaseProfile(
                "dify-main",
                new Identifier("tenant-a"),
                URI.create("https://dify.example.test/v1"),
                "11111111-1111-1111-1111-111111111111",
                trust
        );

        assertEquals(DifyApiCompatibility.DIFY_1_14_X, profile.getCompatibility());
        assertEquals("1.14.x", profile.getCompatibility().getSupportedVersionRange());
        assertEquals("tenant-a", profile.getDedicatedTenantId().getValue());
        assertEquals(false, profile.getAllowPrivateApiAddresses());
        assertEquals("https://dify.example.test:443/v1", profile.getApiBaseUri().toString());
    }

    @Test
    void exposesOpaqueProjectionIdentityAndCredentialContractsToJava() {
        DifyProjectionKey key = new DifyProjectionKey(
                new Identifier("tenant-a"),
                new Identifier("document-a"),
                "dify-main",
                "11111111-1111-1111-1111-111111111111",
                new DifyKnowledgeBaseProfile(
                        "dify-main",
                        new Identifier("tenant-a"),
                        URI.create("https://dify.example.test/v1"),
                        "11111111-1111-1111-1111-111111111111",
                        new DifySourceTrustPolicy(Collections.singletonList(URI.create("https://files.example.test")))
                ).getTargetBindingDigest()
        );
        String projectionId = "44444444-4444-4444-4444-444444444444";
        String externalId = DifyProjectionExternalIds.create(projectionId, 1L);
        DifyProjectionSnapshot snapshot = new DifyProjectionSnapshot(
                key,
                projectionId,
                externalId,
                1L,
                1L,
                DifyProjectionOperation.CREATE,
                DifyProjectionIndexState.CLAIMED,
                null,
                null,
                "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        );

        assertEquals(externalId, snapshot.getExternalId());
        assertNotNull(snapshot.getKey());
        assertTrue(snapshot.getBindingDigest().startsWith("sha256:"));

        DifyProjectionReconciliationEvidence evidence = new DifyProjectionReconciliationEvidence(
                key,
                externalId,
                snapshot.getRevision(),
                snapshot.getBindingDigest(),
                DifyProjectionReconciliationResolution.SYNC_ACCEPTED,
                "22222222-2222-2222-2222-222222222222",
                "batch_1",
                "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                1L
        );
        DifyProjectionReconciliationAuthority authority = (candidate, current) ->
                candidate.getExpectedBindingDigest().equals(current.getBindingDigest());
        assertTrue(authority.authorize(evidence, snapshot));

        DifyApiKeyProvider provider = () -> "fresh-key".toCharArray();
        assertArrayEquals("fresh-key".toCharArray(), provider.loadApiKey());
    }
}
