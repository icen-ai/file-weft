package com.fileweft.starter.boot2

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "fileweft")
class FileWeftProperties {
    var defaultTenantId: String = "default"

    var storage: StorageProperties = StorageProperties()

    var sync: SyncProperties = SyncProperties()

    class StorageProperties {
        var localRoot: String = "./fileweft-data"
    }

    class SyncProperties {
        var connectorName: String = "default"
    }
}
