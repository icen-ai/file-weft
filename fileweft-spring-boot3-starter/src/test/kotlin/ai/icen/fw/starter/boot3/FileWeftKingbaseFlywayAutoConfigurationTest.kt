package ai.icen.fw.starter.boot3

import org.flywaydb.core.api.configuration.FluentConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.AnnotationAwareOrderComparator
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer
import java.io.PrintWriter
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileWeftKingbaseFlywayAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(FileWeftKingbaseFlywayAutoConfiguration::class.java)

    @Test
    fun `wraps Flyway final Kingbase data source without replacing the main data source`() {
        val mainDataSource = MetadataDataSource.kingbase()

        contextRunner
            .withBean("dataSource", DataSource::class.java, { mainDataSource })
            .run { context ->
                assertNull(context.startupFailure)
                assertSame(mainDataSource, context.getBean("dataSource", DataSource::class.java))

                val configuration = applyCustomizers(context, mainDataSource)
                val flywayDataSource = configuration.dataSource
                assertNotSame(mainDataSource, flywayDataSource)

                flywayDataSource.connection.use { connection ->
                    val metadata = connection.metaData
                    assertEquals("PostgreSQL", metadata.databaseProductName)
                    assertEquals(KINGBASE_URL, metadata.url)
                    assertEquals("V008R006C009B0014", metadata.databaseProductVersion)
                    assertEquals(8, metadata.databaseMajorVersion)
                    assertEquals(6, metadata.databaseMinorVersion)
                    assertEquals("Kingbase JDBC Driver", metadata.driverName)
                    assertEquals("8.6.1", metadata.driverVersion)
                    assertEquals(8, metadata.driverMajorVersion)
                    assertEquals(6, metadata.driverMinorVersion)
                    assertNotSame(mainDataSource.lastConnection, connection)
                }

                mainDataSource.connection.use { connection ->
                    assertEquals("KingbaseES", connection.metaData.databaseProductName)
                }
            }
    }

    @Test
    fun `leaves a PostgreSQL connection unwrapped`() {
        val postgresDataSource = MetadataDataSource.postgres()

        contextRunner
            .withBean("dataSource", DataSource::class.java, { postgresDataSource })
            .run { context ->
                assertNull(context.startupFailure)
                val flywayDataSource = applyCustomizers(context, postgresDataSource).dataSource
                assertNotSame(postgresDataSource, flywayDataSource)

                flywayDataSource.connection.use { connection ->
                    assertSame(postgresDataSource.lastConnection, connection)
                    assertEquals("PostgreSQL", connection.metaData.databaseProductName)
                    assertEquals(POSTGRES_URL, connection.metaData.url)
                }
            }
    }

    @Test
    fun `property opt out does not register the compatibility customizer`() {
        contextRunner
            .withPropertyValues("fileweft.persistence.kingbase-flyway-compatibility-enabled=false")
            .run { context ->
                assertNull(context.startupFailure)
                assertFalse(context.containsBean(FILEWEFT_CUSTOMIZER_BEAN))
                assertTrue(context.getBeansOfType(FlywayConfigurationCustomizer::class.java).isEmpty())
            }
    }

    @Test
    fun `runs after a host customizer selects a dedicated Kingbase data source`() {
        val mainDataSource = MetadataDataSource.postgres()
        val dedicatedKingbaseDataSource = MetadataDataSource.kingbase()

        contextRunner
            .withBean("dataSource", DataSource::class.java, { mainDataSource })
            .withBean(
                "hostFlywayDataSourceCustomizer",
                FlywayConfigurationCustomizer::class.java,
                { SelectingFlywayDataSourceCustomizer(dedicatedKingbaseDataSource) },
            )
            .run { context ->
                assertNull(context.startupFailure)
                val configuration = applyCustomizers(context, mainDataSource)
                val selectedDataSource = configuration.dataSource

                assertSame(mainDataSource, context.getBean("dataSource", DataSource::class.java))
                assertEquals(0, mainDataSource.connectionCount.get())
                assertNotSame(dedicatedKingbaseDataSource, selectedDataSource)
                selectedDataSource.connection.use { connection ->
                    assertEquals("PostgreSQL", connection.metaData.databaseProductName)
                    assertEquals(KINGBASE_URL, connection.metaData.url)
                    assertNotSame(dedicatedKingbaseDataSource.lastConnection, connection)
                }
            }
    }

    private fun applyCustomizers(
        context: org.springframework.context.ApplicationContext,
        initialDataSource: DataSource,
    ): FluentConfiguration {
        val configuration = RecordingFluentConfiguration(initialDataSource)
        val customizers = context.getBeansOfType(FlywayConfigurationCustomizer::class.java).values.toMutableList()
        AnnotationAwareOrderComparator.sort(customizers)
        customizers.forEach { customizer -> customizer.customize(configuration) }
        return configuration
    }

    private class RecordingFluentConfiguration(
        private var selectedDataSource: DataSource,
    ) : FluentConfiguration() {
        override fun getDataSource(): DataSource = selectedDataSource

        override fun dataSource(dataSource: DataSource): FluentConfiguration {
            selectedDataSource = dataSource
            return this
        }
    }

    private class SelectingFlywayDataSourceCustomizer(
        private val selectedDataSource: DataSource,
    ) : FlywayConfigurationCustomizer, Ordered {
        override fun customize(configuration: FluentConfiguration) {
            configuration.dataSource(selectedDataSource)
        }

        override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
    }

    private class MetadataDataSource(
        private val productName: String,
        private val url: String,
    ) : DataSource {
        val connectionCount = AtomicInteger()
        @Volatile var lastConnection: Connection? = null
            private set

        override fun getConnection(): Connection {
            connectionCount.incrementAndGet()
            return connectionProxy().also { connection -> lastConnection = connection }
        }

        override fun getConnection(username: String, password: String): Connection = connection

        private fun connectionProxy(): Connection {
            var closed = false
            val metadata = proxy<DatabaseMetaData> { method ->
                when (method.name) {
                    "getDatabaseProductName" -> productName
                    "getURL" -> url
                    "getDatabaseProductVersion" -> if (productName.startsWith("Kingbase")) "V008R006C009B0014" else "16.4"
                    "getDatabaseMajorVersion" -> if (productName.startsWith("Kingbase")) 8 else 16
                    "getDatabaseMinorVersion" -> if (productName.startsWith("Kingbase")) 6 else 4
                    "getDriverName" -> if (productName.startsWith("Kingbase")) "Kingbase JDBC Driver" else "PostgreSQL JDBC Driver"
                    "getDriverVersion" -> if (productName.startsWith("Kingbase")) "8.6.1" else "42.7.4"
                    "getDriverMajorVersion" -> if (productName.startsWith("Kingbase")) 8 else 42
                    "getDriverMinorVersion" -> if (productName.startsWith("Kingbase")) 6 else 7
                    else -> defaultValue(method.returnType)
                }
            }
            return proxy { method ->
                when (method.name) {
                    "getMetaData" -> metadata
                    "close" -> {
                        closed = true
                        null
                    }
                    "isClosed" -> closed
                    else -> defaultValue(method.returnType)
                }
            }
        }

        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getAnonymousLogger()
        override fun <T : Any> unwrap(iface: Class<T>): T = throw UnsupportedOperationException()
        override fun isWrapperFor(iface: Class<*>): Boolean = false

        companion object {
            fun kingbase(): MetadataDataSource = MetadataDataSource("KingbaseES", KINGBASE_URL)
            fun postgres(): MetadataDataSource = MetadataDataSource("PostgreSQL", POSTGRES_URL)
        }
    }

    private companion object {
        const val FILEWEFT_CUSTOMIZER_BEAN = "fileWeftKingbaseFlywayConfigurationCustomizer"
        const val KINGBASE_URL = "jdbc:kingbase8://localhost:54321/fileweft"
        const val POSTGRES_URL = "jdbc:postgresql://localhost:5432/fileweft"

        @Suppress("UNCHECKED_CAST")
        inline fun <reified T> proxy(crossinline answer: (Method) -> Any?): T =
            Proxy.newProxyInstance(
                FileWeftKingbaseFlywayAutoConfigurationTest::class.java.classLoader,
                arrayOf(T::class.java),
            ) { _, method, _ -> answer(method) } as T

        fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0F
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }
    }
}
