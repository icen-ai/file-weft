package com.fileweft.starter.boot2

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "fileweft")
class FileWeftProperties {
    var defaultTenantId: String = "default"

    var storage: StorageProperties = StorageProperties()

    var sync: SyncProperties = SyncProperties()

    var task: TaskProperties = TaskProperties()

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

    class TaskProperties {
        var maxAttempts: Int = 5
        var initialRetryDelayMillis: Long = 10_000
        var maxRetryDelayMillis: Long = 300_000
        var leaseDurationMillis: Long = 60_000
        var workerId: String? = null
    }
}
