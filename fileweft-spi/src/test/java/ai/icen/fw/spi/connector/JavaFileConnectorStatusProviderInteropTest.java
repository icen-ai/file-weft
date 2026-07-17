package ai.icen.fw.spi.connector;

import ai.icen.fw.core.id.Identifier;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaFileConnectorStatusProviderInteropTest {
    @Test
    void javaCanImplementAndInvokeTheAdditiveStatusCapability() {
        FileConnectorStatusProvider provider = request -> new ConnectorStatusResult(
            ConnectorStatusQueryStatus.SUCCESS,
            ConnectorExternalState.AVAILABLE,
            "Projection is available."
        );
        ConnectorStatusRequest request = new ConnectorStatusRequest(
            new Identifier("tenant-a"),
            new Identifier("document-a"),
            "projection:v1:1",
            new ConnectorInvocation("status-1", Duration.ofSeconds(2), 1)
        );

        assertEquals(ConnectorExternalState.AVAILABLE, provider.status(request).getState());
        assertEquals(512, ConnectorStatusResult.MAX_DIAGNOSTIC_UTF16_LENGTH);
    }
}
