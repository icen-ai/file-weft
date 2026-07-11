package com.fileweft.starter.boot3

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "fileweft")
class FileWeftProperties {
    var defaultTenantId: String = "default"

    var storage: StorageProperties = StorageProperties()

    var upload: UploadProperties = UploadProperties()

    var sync: SyncProperties = SyncProperties()

    var workflow: WorkflowProperties = WorkflowProperties()

    var task: TaskProperties = TaskProperties()

    var outbox: OutboxProperties = OutboxProperties()

    var worker: WorkerProperties = WorkerProperties()

    class StorageProperties {
        var localRoot: String = "./fileweft-data"
    }

    class UploadProperties {
        /** TTL for an unfinished resumable multipart session before worker cleanup. */
        var resumableSessionTtlMillis: Long = 86_400_000
        var resumableCleanupBatchSize: Int = 100
    }

    class SyncProperties {
        var connectorName: String = "default"
        var defaultProfileId: String = "default"
        /** Hard upper bound for one downstream connector invocation. */
        var connectorTimeoutMillis: Long = 30_000
        /**
         * Lifetime of the signed/storage source URL delivered to a downstream.
         * It must be no shorter than [connectorTimeoutMillis] because a remote
         * platform can acknowledge the hand-off before it retrieves the file.
         */
        var sourceAccessUrlTtlMillis: Long = 900_000
        /** Consecutive retryable outcomes before the connector circuit opens. */
        var circuitBreakerFailureThreshold: Int = 3
        /** How long an open connector circuit rejects work before one recovery probe. */
        var circuitBreakerOpenDurationMillis: Long = 30_000
        /** Shared process-wide upper bound for concurrently executing connector calls. */
        var connectorMaxConcurrentInvocations: Int = 16
        /** Bounded queue used when all connector invocation threads are busy. */
        var connectorInvocationQueueCapacity: Int = 256
        var profiles: MutableList<DeliveryProfileProperties> = mutableListOf()
    }

    class WorkflowProperties {
        /** Route used when a caller does not explicitly select one. */
        var defaultReviewRouteId: String = "default"
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
        /** Recovery delay for RUNNING tasks created before persisted lease tokens were available. */
        var legacyRunningGraceMillis: Long = 300_000
        var workerId: String? = null
    }

    /** Durable Outbox ownership settings for independently deployed worker processes. */
    class OutboxProperties {
        /** Optional unique worker identity; blank values receive a generated process-local identity. */
        var workerId: String? = null
        /** Maximum ownership period for one claimed Outbox event. */
        var leaseDurationMillis: Long = 300_000
        /** Recovery delay before reclaiming RUNNING records created before persisted leases existed. */
        var legacyRunningGraceMillis: Long = 300_000
    }

    /** Explicitly opt-in polling configuration for a separately deployed worker role. */
    class WorkerProperties {
        var enabled: Boolean = false
        var fixedDelayMillis: Long = 1_000
        var outboxBatchSize: Int = 50
        var taskBatchSize: Int = 50
        var processOutbox: Boolean = true
        var processTasks: Boolean = true
        var processUploadCleanup: Boolean = true
    }
}
