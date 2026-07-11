package com.fileweft.dev.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "fileweft.dev")
class FileWeftDevProperties {
    var storage: Storage = Storage()
    var platform: Platform = Platform()
    var outbox: Outbox = Outbox()
    var task: Task = Task()
    var upload: Upload = Upload()
    var users: MutableList<User> = mutableListOf()

    class Storage {
        var endpoint: String = "http://127.0.0.1:9000"
        var region: String = "us-east-1"
        var accessKey: String = "rustfsadmin"
        var secretKey: String = "ChangeMe123!"
        var bucket: String = "fileweft-dev"
    }

    class Platform {
        var baseUrl: String = "http://127.0.0.1:8081/"
        var connectTimeoutMillis: Int = 3_000
        var readTimeoutMillis: Int = 10_000
    }

    class Outbox {
        var fixedDelayMillis: Long = 1_000
        var batchSize: Int = 20
    }

    class Task {
        var fixedDelayMillis: Long = 1_000
        var batchSize: Int = 20
    }

    class Upload {
        var cleanupFixedDelayMillis: Long = 60_000
        var cleanupBatchSize: Int = 100
    }

    class User {
        var id: String = ""
        var username: String = ""
        var password: String = ""
        var displayName: String = ""
        var tenantId: String = ""
        var role: DevRole = DevRole.VIEWER
    }
}

enum class DevRole {
    ADMIN,
    EDITOR,
    REVIEWER,
    VIEWER,
}
