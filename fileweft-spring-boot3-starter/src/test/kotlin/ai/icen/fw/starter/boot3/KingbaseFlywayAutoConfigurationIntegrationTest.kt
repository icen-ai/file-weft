package ai.icen.fw.starter.boot3

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class KingbaseFlywayAutoConfigurationIntegrationTest {
    @Test
    fun `Boot 3 Flyway auto configuration migrates an independent host location on Kingbase`() {
        check(System.getenv("FILEWEFT_RUN_KINGBASE_TESTS") == "true") {
            "Kingbase integration tests must run only through the fail-closed Gradle task"
        }
        assertEquals("17", System.getProperty("java.specification.version"))

        val settings = KingbaseSettings.fromEnvironment()
        Class.forName(settings.driver)
        settings.rawConnection().use { connection -> resetSchema(connection) }
        val mainDataSource = DriverManagerDataSource(settings)

        try {
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration::class.java))
                .withUserConfiguration(FileWeftKingbaseFlywayAutoConfiguration::class.java)
                .withBean("dataSource", DataSource::class.java, { mainDataSource })
                .withPropertyValues(
                    "spring.flyway.enabled=true",
                    "spring.flyway.locations=classpath:db/kingbase-host-boot3",
                    "spring.flyway.default-schema=$HOST_SCHEMA",
                    "spring.flyway.schemas=$HOST_SCHEMA",
                    "spring.flyway.table=$HISTORY_TABLE",
                    "spring.flyway.clean-disabled=true",
                )
                .run { context ->
                    assertNull(context.startupFailure)
                    assertSame(mainDataSource, context.getBean("dataSource", DataSource::class.java))

                    val flyway = context.getBean(Flyway::class.java)
                    val flywayDataSource = flyway.configuration.dataSource
                    assertNotSame(mainDataSource, flywayDataSource)
                    flywayDataSource.connection.use { connection ->
                        assertEquals("PostgreSQL", connection.metaData.databaseProductName)
                        assertEquals(settings.url, connection.metaData.url)
                    }
                    mainDataSource.connection.use { connection ->
                        assertEquals("KingbaseES", connection.metaData.databaseProductName)
                        assertEquals("boot3-flyway11", singleString(connection, "SELECT runtime_name FROM $HOST_SCHEMA.$PROBE_TABLE"))
                        assertEquals(
                            1,
                            singleInt(
                                connection,
                                "SELECT COUNT(*) FROM $HOST_SCHEMA.$HISTORY_TABLE WHERE version = '1' AND success = TRUE",
                            ),
                        )
                    }
                }
        } finally {
            settings.rawConnection().use { connection -> dropSchema(connection) }
        }
    }

    private fun resetSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("DROP SCHEMA IF EXISTS $HOST_SCHEMA CASCADE")
            statement.execute("CREATE SCHEMA $HOST_SCHEMA")
        }
    }

    private fun dropSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("DROP SCHEMA IF EXISTS $HOST_SCHEMA CASCADE")
        }
    }

    private fun singleString(connection: Connection, sql: String): String =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next()) { "Expected one row from: $sql" }
                result.getString(1)
            }
        }

    private fun singleInt(connection: Connection, sql: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next()) { "Expected one row from: $sql" }
                result.getInt(1)
            }
        }

    private data class KingbaseSettings(
        val driver: String,
        val url: String,
        val user: String,
        val password: String,
    ) {
        fun rawConnection(): Connection = DriverManager.getConnection(url, user, password)

        companion object {
            fun fromEnvironment(): KingbaseSettings = KingbaseSettings(
                driver = System.getenv("FILEWEFT_KINGBASE_DRIVER") ?: "com.kingbase8.Driver",
                url = System.getenv("FILEWEFT_KINGBASE_URL") ?: "jdbc:kingbase8://127.0.0.1:54321/test",
                user = System.getenv("FILEWEFT_KINGBASE_USER") ?: "system",
                password = System.getenv("FILEWEFT_KINGBASE_PASSWORD") ?: "kingbase",
            )
        }
    }

    private class DriverManagerDataSource(
        private val settings: KingbaseSettings,
    ) : DataSource {
        override fun getConnection(): Connection = settings.rawConnection()
        override fun getConnection(username: String, password: String): Connection =
            DriverManager.getConnection(settings.url, username, password)
        override fun getLogWriter(): PrintWriter? = DriverManager.getLogWriter()
        override fun setLogWriter(out: PrintWriter?) = DriverManager.setLogWriter(out)
        override fun setLoginTimeout(seconds: Int) = DriverManager.setLoginTimeout(seconds)
        override fun getLoginTimeout(): Int = DriverManager.getLoginTimeout()
        override fun getParentLogger(): Logger = Logger.getLogger("ai.icen.fw.starter.boot3.kingbase")
        override fun <T : Any> unwrap(iface: Class<T>): T {
            if (iface.isInstance(this)) return iface.cast(this)
            throw java.sql.SQLException("Not a wrapper for ${iface.name}")
        }
        override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
    }

    private companion object {
        const val HOST_SCHEMA = "fw_boot3_host_probe"
        const val HISTORY_TABLE = "fw_boot3_host_schema_history"
        const val PROBE_TABLE = "fw_boot3_host_migration_probe"
    }
}
