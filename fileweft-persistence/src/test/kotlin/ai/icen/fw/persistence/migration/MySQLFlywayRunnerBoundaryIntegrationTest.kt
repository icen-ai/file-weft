package ai.icen.fw.persistence.migration

import com.mysql.cj.jdbc.MysqlDataSource
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MySQLFlywayRunnerBoundaryIntegrationTest {
    @Test
    fun `uses MySQL character limits instead of PostgreSQL byte limits`() {
        requireEnabled()
        // A multi-byte name longer than PostgreSQL's 63-byte limit proves the
        // MySQL path counts characters. Keep it below the host filesystem's
        // escaped-filename limit used by the official MySQL image.
        val acceptedNames = listOf("a".repeat(64), "文".repeat(32))
        try {
            acceptedNames.forEach { database ->
                recreateDatabase(database)
                val dataSource = dataSource(database)
                assertEquals(30, FlywayMigrationRunner(dataSource, database).migrate())
                FlywayMigrationRunner(dataSource, database).validate()
            }

            recreateDatabase(DEFAULT_BOUNDARY_DATABASE)
            listOf("a".repeat(65), "文".repeat(65)).forEach { rejected ->
                val failure = assertFailsWith<IllegalArgumentException> {
                    FlywayMigrationRunner(dataSource(DEFAULT_BOUNDARY_DATABASE), rejected).migrate()
                }
                assertTrue(failure.message.orEmpty().contains("65 characters"))
            }
        } finally {
            acceptedNames.plus(DEFAULT_BOUNDARY_DATABASE).forEach(::dropDatabase)
        }
    }

    @Test
    fun `two fresh MySQL runners publish one reviewed namespace history`() {
        requireEnabled()
        recreateDatabase(CONCURRENT_DATABASE)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val executions = (1..2).map {
                executor.submit<Int> {
                    start.await(5, TimeUnit.SECONDS)
                    FlywayMigrationRunner(dataSource(CONCURRENT_DATABASE)).migrate()
                }
            }
            start.countDown()
            val counts = executions.map { future -> future.get(30, TimeUnit.SECONDS) }

            assertTrue(counts.all { count -> count in 0..30 })
            assertEquals(30, counts.sum())
            FlywayMigrationRunner(dataSource(CONCURRENT_DATABASE)).validate()
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            dropDatabase(CONCURRENT_DATABASE)
        }
    }

    @Test
    fun `recovers when concurrent MySQL history changes from absent to empty before baseline`() {
        requireEnabled()
        recreateDatabase(EMPTY_HISTORY_TEMPLATE_DATABASE)
        recreateDatabase(IN_FLIGHT_HISTORY_DATABASE)
        val absentHistoryObserved = CountDownLatch(1)
        val continueAfterHistoryCreation = CountDownLatch(1)
        val emptyHistoryObserved = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            FlywayMigrationRunner(dataSource(EMPTY_HISTORY_TEMPLATE_DATABASE)).migrate()

            val migration = executor.submit<Int> {
                FlywayMigrationRunner(
                    observingBootstrapDataSource(
                        dataSource(IN_FLIGHT_HISTORY_DATABASE),
                        absentHistoryObserved,
                        continueAfterHistoryCreation,
                        emptyHistoryObserved,
                    ),
                ).migrate()
            }
            assertTrue(absentHistoryObserved.await(10, TimeUnit.SECONDS))
            adminDataSource().connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE `$IN_FLIGHT_HISTORY_DATABASE`.`${FlywayMigrationRunner.HISTORY_TABLE}` " +
                            "LIKE `$EMPTY_HISTORY_TEMPLATE_DATABASE`.`${FlywayMigrationRunner.HISTORY_TABLE}`",
                    )
                }
            }
            continueAfterHistoryCreation.countDown()
            assertTrue(emptyHistoryObserved.await(10, TimeUnit.SECONDS))
            adminDataSource().connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO `$IN_FLIGHT_HISTORY_DATABASE`.`${FlywayMigrationRunner.HISTORY_TABLE}` " +
                            "SELECT * FROM `$EMPTY_HISTORY_TEMPLATE_DATABASE`." +
                            "`${FlywayMigrationRunner.HISTORY_TABLE}` WHERE installed_rank = 1",
                    )
                }
            }

            assertEquals(30, migration.get(30, TimeUnit.SECONDS))
            FlywayMigrationRunner(dataSource(IN_FLIGHT_HISTORY_DATABASE)).validate()
        } finally {
            continueAfterHistoryCreation.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            dropDatabase(IN_FLIGHT_HISTORY_DATABASE)
            dropDatabase(EMPTY_HISTORY_TEMPLATE_DATABASE)
        }
    }

    @Test
    fun `validates MySQL namespace marker when Connector J exposes booleans numerically`() {
        requireEnabled()
        recreateDatabase(NUMERIC_BOOLEAN_DATABASE)
        try {
            val dataSource = dataSource(NUMERIC_BOOLEAN_DATABASE, "tinyInt1isBit=false")
            assertEquals(30, FlywayMigrationRunner(dataSource).migrate())
            FlywayMigrationRunner(dataSource).validate()
        } finally {
            dropDatabase(NUMERIC_BOOLEAN_DATABASE)
        }
    }

    @Test
    fun `refuses MySQL database creation before any DDL when the data source has no current database`() {
        requireEnabled()
        dropDatabase(CREATE_SCHEMA_DATABASE)
        try {
            val failure = assertFailsWith<IllegalStateException> {
                FlywayMigrationRunner(adminDataSource(), CREATE_SCHEMA_DATABASE, true).migrate()
            }

            assertTrue(failure.message.orEmpty().contains("cannot safely create MySQL database"))
            assertFalse(databaseExists(CREATE_SCHEMA_DATABASE))
        } finally {
            dropDatabase(CREATE_SCHEMA_DATABASE)
        }
    }

    private fun recreateDatabase(database: String) {
        adminDataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE IF EXISTS `$database`")
                statement.execute(
                    "CREATE DATABASE `$database` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                )
            }
        }
    }

    private fun dropDatabase(database: String) {
        adminDataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE IF EXISTS `$database`")
            }
        }
    }

    private fun databaseExists(database: String): Boolean = adminDataSource().connection.use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?",
        ).use { statement ->
            statement.setString(1, database)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getInt(1) == 1
            }
        }
    }

    private fun dataSource(database: String, extraParameter: String? = null): DataSource = MysqlDataSource().apply {
        val baseUrl = databaseUrl(database)
        setURL(
            if (extraParameter == null) {
                baseUrl
            } else {
                baseUrl + if ('?' in baseUrl) "&$extraParameter" else "?$extraParameter"
            },
        )
        user = environment("FILEWEFT_MYSQL_USER", "root")
        password = environment("FILEWEFT_MYSQL_PASSWORD", "")
    }

    private fun observingBootstrapDataSource(
        delegate: DataSource,
        absentHistoryObserved: CountDownLatch,
        continueAfterHistoryCreation: CountDownLatch,
        emptyHistoryObserved: CountDownLatch,
    ): DataSource = object : DataSource by delegate {
        override fun getConnection(): Connection = observingConnection(
            delegate.connection,
            absentHistoryObserved,
            continueAfterHistoryCreation,
            emptyHistoryObserved,
        )

        override fun getConnection(username: String?, password: String?): Connection =
            observingConnection(
                delegate.getConnection(username, password),
                absentHistoryObserved,
                continueAfterHistoryCreation,
                emptyHistoryObserved,
            )
    }

    private fun observingConnection(
        delegate: Connection,
        absentHistoryObserved: CountDownLatch,
        continueAfterHistoryCreation: CountDownLatch,
        emptyHistoryObserved: CountDownLatch,
    ): Connection = proxy(Connection::class.java, delegate) { methodName, arguments, result ->
        when {
            methodName == "getMetaData" -> observingMetadata(
                result as DatabaseMetaData,
                absentHistoryObserved,
                continueAfterHistoryCreation,
            )
            methodName == "prepareStatement" &&
                arguments.firstOrNull()?.toString()?.let { sql ->
                    sql.contains("`${FlywayMigrationRunner.HISTORY_TABLE}`") &&
                        sql.contains("ORDER BY installed_rank")
                } == true -> observingPreparedStatement(result as PreparedStatement, emptyHistoryObserved)
            else -> result
        }
    }

    private fun observingMetadata(
        delegate: DatabaseMetaData,
        absentHistoryObserved: CountDownLatch,
        continueAfterHistoryCreation: CountDownLatch,
    ): DatabaseMetaData = proxy(DatabaseMetaData::class.java, delegate) { methodName, arguments, result ->
        if (
            methodName == "getTables" &&
            arguments.getOrNull(2)?.toString()?.replace("\\", "") ==
                FlywayMigrationRunner.HISTORY_TABLE
        ) {
            observingEmptyResultSet(
                result as ResultSet,
                absentHistoryObserved,
                continueAfterHistoryCreation,
            )
        } else {
            result
        }
    }

    private fun observingPreparedStatement(
        delegate: PreparedStatement,
        emptyHistoryObserved: CountDownLatch,
    ): PreparedStatement = proxy(PreparedStatement::class.java, delegate) { methodName, _, result ->
        if (methodName == "executeQuery") {
            observingEmptyResultSet(result as ResultSet, emptyHistoryObserved)
        } else {
            result
        }
    }

    private fun observingEmptyResultSet(
        delegate: ResultSet,
        emptyHistoryObserved: CountDownLatch,
        continueAfterObservation: CountDownLatch? = null,
    ): ResultSet {
        val resultSetHasRows = AtomicBoolean(false)
        return proxy(ResultSet::class.java, delegate) { methodName, _, result ->
            if (methodName == "next") {
                val hasRow = result == true
                if (hasRow) {
                    resultSetHasRows.set(true)
                } else if (!resultSetHasRows.get()) {
                    emptyHistoryObserved.countDown()
                    if (continueAfterObservation != null) {
                        check(continueAfterObservation.await(10, TimeUnit.SECONDS)) {
                            "Timed out while widening the MySQL absent-to-empty bootstrap window"
                        }
                    }
                }
            }
            result
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> proxy(
        contract: Class<T>,
        delegate: T,
        transform: (String, Array<out Any?>, Any?) -> Any?,
    ): T = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(contract),
    ) { _, method, arguments ->
        val safeArguments = arguments ?: emptyArray()
        val result = try {
            method.invoke(delegate, *safeArguments)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
        transform(method.name, safeArguments, result)
    } as T

    private fun adminDataSource(): MysqlDataSource = MysqlDataSource().apply {
        setURL(environment("FILEWEFT_MYSQL_ADMIN_URL", "jdbc:mysql://localhost:3306"))
        user = environment("FILEWEFT_MYSQL_ADMIN_USER", "root")
        password = environment("FILEWEFT_MYSQL_ADMIN_PASSWORD", "")
    }

    private fun databaseUrl(database: String): String {
        val configured = environment("FILEWEFT_MYSQL_URL", "jdbc:mysql://localhost:3306/fileweft")
        val queryIndex = configured.indexOf('?')
        val withoutQuery = if (queryIndex >= 0) configured.substring(0, queryIndex) else configured
        val query = if (queryIndex >= 0) configured.substring(queryIndex) else ""
        val databaseSlash = withoutQuery.indexOf('/', "jdbc:mysql://".length)
        require(databaseSlash >= 0) { "FILEWEFT_MYSQL_URL must include a database path" }
        return withoutQuery.substring(0, databaseSlash + 1) + database + query
    }

    private fun requireEnabled() {
        check(System.getenv("FILEWEFT_RUN_MYSQL_TESTS") == "true") {
            "MySQL integration tests must run only through the fail-closed Gradle task"
        }
    }

    private fun environment(name: String, fallback: String): String = System.getenv(name) ?: fallback

    private companion object {
        const val DEFAULT_BOUNDARY_DATABASE = "fileweft_schema_boundary"
        const val CONCURRENT_DATABASE = "fileweft_concurrent_bootstrap"
        const val CREATE_SCHEMA_DATABASE = "fileweft_create_schema_boundary"
        const val EMPTY_HISTORY_TEMPLATE_DATABASE = "fileweft_empty_history_template"
        const val IN_FLIGHT_HISTORY_DATABASE = "fileweft_in_flight_history"
        const val NUMERIC_BOOLEAN_DATABASE = "fileweft_numeric_boolean_marker"
    }
}
