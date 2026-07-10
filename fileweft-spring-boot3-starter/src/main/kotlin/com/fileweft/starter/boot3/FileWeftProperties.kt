package com.fileweft.starter.boot3

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
        var defaultProfileId: String = "default"
        var profiles: MutableList<DeliveryProfileProperties> = mutableListOf()
    }

    class DeliveryProfileProperties {
        var id: String = ""
        var displayName: String = ""
        var targets: MutableList<DeliveryTargetProperties> = mutableListOf()
    }

    class DeliveryTargetProperties {
        var id: String = ""
        var displayName: String = ""
        var connectorId: String = ""
        var required: Boolean = true
        var ownerRef: String? = null
    }
}
