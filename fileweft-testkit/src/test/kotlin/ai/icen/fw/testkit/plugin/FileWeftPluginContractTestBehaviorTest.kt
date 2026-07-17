package ai.icen.fw.testkit.plugin

import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.plugin.FileWeftPlugin

class FileWeftPluginContractTestBehaviorTest : FileWeftPluginContractTest() {
    override val fileWeftPlugin: FileWeftPlugin = object : FileWeftPlugin {
        override fun id(): String = "testkit-contract-plugin"

        override fun connectors(): Map<String, FileConnector> = mapOf(
            "testkit-connector" to object : FileConnector {
                override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult =
                    ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, "testkit-external-id")

                override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult =
                    ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)

                override fun health(): ConnectorHealth = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
            },
        )
    }
}
