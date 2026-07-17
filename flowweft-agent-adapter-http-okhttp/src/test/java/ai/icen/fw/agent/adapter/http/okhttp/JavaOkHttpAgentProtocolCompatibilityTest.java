package ai.icen.fw.agent.adapter.http.okhttp;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaOkHttpAgentProtocolCompatibilityTest {
    @Test
    void transportAndSecretMaterialAreJava8Friendly() {
        AgentProtocolHttpCredentialMaterial material = AgentProtocolHttpCredentialMaterial.oauthBearer(
            "access-token-secret".toCharArray()
        );
        AgentProtocolHttpCredentialProvider credentials = request -> CompletableFuture.completedFuture(material);
        AgentProtocolHttpEvidenceRecorder evidence = value -> CompletableFuture.completedFuture(null);
        OkHttpAgentProtocolHttpTransport transport = new OkHttpAgentProtocolHttpTransport(credentials, evidence);

        assertFalse(material.toString().contains("access-token-secret"));
        material.close();
        assertTrue(material.isDestroyed());
        transport.close();
    }
}
