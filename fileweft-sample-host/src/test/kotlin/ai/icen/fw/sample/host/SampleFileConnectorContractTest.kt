package ai.icen.fw.sample.host

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.connector.ConnectorFileSource
import ai.icen.fw.spi.connector.ConnectorInvocation
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.testkit.connector.FileConnectorContractTest
import java.net.URI
import java.time.Duration

class SampleFileConnectorContractTest : FileConnectorContractTest() {

    override val fileConnector = SampleFileConnector()

    override fun syncRequest(): ConnectorSyncRequest {
        return ConnectorSyncRequest(
            tenantId = Identifier("sample-tenant"),
            businessId = Identifier("sample-business-${System.nanoTime()}"),
            source = ConnectorFileSource(
                downloadUri = URI("file://sample/contract.txt"),
                fileName = "contract.txt",
            ),
            invocation = ConnectorInvocation(
                idempotencyKey = "sample-idempotency-key",
                timeout = Duration.ofSeconds(5),
            ),
        )
    }

    override fun removalRequest(): ConnectorRemoveRequest {
        return ConnectorRemoveRequest(
            tenantId = Identifier("sample-tenant"),
            businessId = Identifier("sample-business-removal-${System.nanoTime()}"),
            externalId = "sample-external-id",
            invocation = ConnectorInvocation(
                idempotencyKey = "sample-removal-key",
                timeout = Duration.ofSeconds(5),
            ),
        )
    }
}
