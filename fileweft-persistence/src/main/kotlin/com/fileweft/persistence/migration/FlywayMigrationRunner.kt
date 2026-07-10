package com.fileweft.persistence.migration

import org.flywaydb.core.Flyway
import javax.sql.DataSource

class FlywayMigrationRunner(
    private val dataSource: DataSource,
) {
    fun migrate(): Int = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()
        .migrationsExecuted
}
