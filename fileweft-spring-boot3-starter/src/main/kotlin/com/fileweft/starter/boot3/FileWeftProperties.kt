package com.fileweft.starter.boot3

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "fileweft")
class FileWeftProperties {
    var defaultTenantId: String = "default"

    var storage: StorageProperties = StorageProperties()

    var sync: SyncProperties = SyncProperties()

    var task: TaskProperties = TaskProperties()

    var worker: WorkerProperties = WorkerProperties()

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

    /** Explicitly opt-in polling configuration for a separately deployed worker role. */
    class WorkerProperties {
        var enabled: Boolean = false
        var fixedDelayMillis: Long = 1_000
        var outboxBatchSize: Int = 50
        var taskBatchSize: Int = 50
        var processOutbox: Boolean = true
        var processTasks: Boolean = true
    }
}
