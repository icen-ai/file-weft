package ai.icen.fw.adapter.dify

import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorStatusRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.connector.FileConnectorStatusProvider
import ai.icen.fw.testkit.connector.FileConnectorContractTest
import ai.icen.fw.testkit.connector.FileConnectorStatusProviderContractTest

class DifyFileConnectorContractTest : FileConnectorContractTest() {
    private val store = InMemoryDifyProjectionStore()
    private val remote = TestDifyRemoteApi()
    override val fileConnector: FileConnector = DifyKnowledgeBaseConnector(
        testProfile(),
        store,
        TestDifySourceDownloader(),
        remote,
    )

    override fun syncRequest(): ConnectorSyncRequest = ai.icen.fw.adapter.dify.syncRequest()

    override fun removalRequest(): ConnectorRemoveRequest {
        val synchronized = fileConnector.sync(ai.icen.fw.adapter.dify.syncRequest())
        return ai.icen.fw.adapter.dify.removalRequest(requireNotNull(synchronized.externalId))
    }
}

class DifyStatusProviderContractTest : FileConnectorStatusProviderContractTest() {
    private val connector = DifyKnowledgeBaseConnector(
        testProfile(),
        InMemoryDifyProjectionStore(),
        TestDifySourceDownloader(),
        TestDifyRemoteApi(),
    )
    override val statusProvider: FileConnectorStatusProvider = connector

    override fun statusRequest(): ConnectorStatusRequest {
        val synchronized = connector.sync(ai.icen.fw.adapter.dify.syncRequest())
        return ai.icen.fw.adapter.dify.statusRequest(requireNotNull(synchronized.externalId))
    }
}
