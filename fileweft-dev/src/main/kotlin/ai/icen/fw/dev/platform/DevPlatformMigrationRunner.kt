package ai.icen.fw.dev.platform

import org.flywaydb.core.Flyway
import javax.sql.DataSource

class DevPlatformMigrationRunner(
    private val dataSource: DataSource,
) {
    fun migrate(): Int = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/dev-platform")
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .load()
        .migrate()
        .migrationsExecuted

    private companion object {
        const val SCHEMA = "fileweft_dev_platform"
    }
}
