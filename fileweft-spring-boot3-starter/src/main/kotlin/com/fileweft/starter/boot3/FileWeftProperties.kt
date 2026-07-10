package com.fileweft.starter.boot3

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "fileweft")
class FileWeftProperties {
    var defaultTenantId: String = "default"

    var storage: StorageProperties = StorageProperties()

    class StorageProperties {
        var localRoot: String = "./fileweft-data"
    }
}
