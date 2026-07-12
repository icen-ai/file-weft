package ai.icen.fw.starter.boot2

import ai.icen.fw.persistence.migration.FileWeftMigrationMode
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.sql.init.dependency.DatabaseInitializerDetector
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.beans.factory.BeanFactoryUtils
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Primary
import java.lang.reflect.Modifier
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileWeftMigrationConfigurationTest {
    @BeforeEach
    fun resetProbes() {
        listOf(SingleDataSource, PrimaryDataSource, SecondaryDataSource, OverrideDataSource, OrderedDataSource)
            .forEach(NeverConnectingDataSource::reset)
        InitializationOrder.clear()
    }

    @Test
    fun `disabled mode creates no runner or initializer and never touches the database`() {
        contextRunner(SingleDataSourceConfiguration::class.java).run { context ->
            assertEquals(null, context.startupFailure)
            assertEquals(FileWeftMigrationMode.DISABLED, context.getBean(FileWeftProperties::class.java).persistence.migrationMode)
            assertTrue(context.getBeansOfType(FlywayMigrationRunner::class.java).isEmpty())
            assertTrue(context.getBeansOfType(FileWeftMigrationInitializer::class.java).isEmpty())
            assertEquals(0, SingleDataSource.connectionAttempts.get())
        }
    }

    @Test
    fun `published helper facade exposes no Kotlin function types to Java`() {
        val holder = runCatching {
            Class.forName("${FileWeftMigrationConfiguration::class.java.`package`.name}.FileWeftMigrationConfigurationKt")
        }.getOrNull()
        val kotlinFunctionMethods = holder?.declaredMethods.orEmpty()
            .filter { method -> Modifier.isPublic(method.modifiers) }
            .filter { method ->
                method.returnType.name.startsWith("kotlin.jvm.functions.Function") ||
                    method.parameterTypes.any { type -> type.name.startsWith("kotlin.jvm.functions.Function") }
            }

        assertTrue(kotlinFunctionMethods.isEmpty(), kotlinFunctionMethods.joinToString())
    }

    @Test
    fun `lowercase validate and migrate properties enable a runner without eager database access`() {
        listOf("validate" to FileWeftMigrationMode.VALIDATE, "migrate" to FileWeftMigrationMode.MIGRATE).forEach { (value, mode) ->
            contextRunner(HostInitializerConfiguration::class.java)
                .withPropertyValues(
                    "fileweft.persistence.migration-mode=$value",
                    "fileweft.persistence.schema=fileweft_app",
                )
                .run { context ->
                    assertEquals(null, context.startupFailure)
                    assertEquals(mode, context.getBean(FileWeftProperties::class.java).persistence.migrationMode)
                    assertEquals(1, context.getBeansOfType(FlywayMigrationRunner::class.java).size)
                    assertSame(HostInitializer, context.getBean(FileWeftMigrationInitializer::class.java))
                    assertEquals(0, SingleDataSource.connectionAttempts.get())
                }
        }
    }

    @Test
    fun `enabled mode rejects blank and non-canonical schema before opening a connection`() {
        contextRunner(SingleDataSourceConfiguration::class.java)
            .withPropertyValues("fileweft.persistence.migration-mode=VALIDATE")
            .run { context ->
                assertTrue(context.startupFailure.causeMessages().any { it.contains("fileweft.persistence.schema must not be blank") })
                assertEquals(0, SingleDataSource.connectionAttempts.get())
            }
        contextRunner(SingleDataSourceConfiguration::class.java)
            .withPropertyValues(
                "fileweft.persistence.migration-mode=MIGRATE",
                "fileweft.persistence.schema=fileweft_app\u00a0",
            )
            .run { context ->
                val messages = context.startupFailure.causeMessages()
                assertTrue(
                    messages.any {
                        it.contains("leading or trailing whitespace") ||
                            it.contains("must not start or end with Unicode whitespace")
                    },
                    messages.joinToString(" | "),
                )
                assertEquals(0, SingleDataSource.connectionAttempts.get())
            }
    }

    @Test
    fun `create schema is accepted only for migrate and never touches the database while disabled`() {
        listOf(FileWeftMigrationMode.DISABLED, FileWeftMigrationMode.VALIDATE).forEach { mode ->
            contextRunner(SingleDataSourceConfiguration::class.java)
                .withPropertyValues(
                    "fileweft.persistence.migration-mode=$mode",
                    "fileweft.persistence.schema=fileweft_app",
                    "fileweft.persistence.create-schema=true",
                )
                .run { context ->
                    assertTrue(context.startupFailure.causeMessages().any { it.contains("allowed only") })
                    assertEquals(0, SingleDataSource.connectionAttempts.get())
                }
        }
        contextRunner(HostInitializerConfiguration::class.java)
            .withPropertyValues(
                "fileweft.persistence.migration-mode=MIGRATE",
                "fileweft.persistence.schema=fileweft_app",
                "fileweft.persistence.create-schema=true",
            )
            .run { context ->
                assertEquals(null, context.startupFailure)
                assertTrue(context.getBean(FileWeftProperties::class.java).persistence.createSchema)
                assertEquals(0, SingleDataSource.connectionAttempts.get())
            }
    }

    @Test
    fun `global lazy initialization still validates invalid settings eagerly`() {
        lazyContextRunner(SingleDataSourceConfiguration::class.java)
            .withPropertyValues("fileweft.persistence.migration-mode=VALIDATE")
            .run { context ->
                assertTrue(context.startupFailure.causeMessages().any { it.contains("fileweft.persistence.schema must not be blank") })
                assertEquals(0, SingleDataSource.connectionAttempts.get())
            }
    }

    @Test
    fun `global lazy initialization still invokes the default validate initializer`() {
        lazyContextRunner(SingleDataSourceConfiguration::class.java)
            .withPropertyValues(
                "fileweft.persistence.migration-mode=VALIDATE",
                "fileweft.persistence.schema=fileweft_app",
            )
            .run { context ->
                val messages = context.startupFailure.causeMessages()
                assertTrue(
                    messages.any { it.contains("must not open a database connection") },
                    messages.joinToString(" | "),
                )
                assertTrue(SingleDataSource.connectionAttempts.get() > 0)
            }
    }

    @Test
    fun `database initialization dependency runs a lazy host SQL initializer before FileWeft`() {
        contextRunner(OrderedDatabaseInitializationConfiguration::class.java)
            .withPropertyValues(
                "fileweft.persistence.migration-mode=VALIDATE",
                "fileweft.persistence.schema=fileweft_app",
            )
            .run { context ->
                val messages = context.startupFailure.causeMessages()
                assertTrue(
                    messages.any { it.contains("must not open a database connection") },
                    messages.joinToString(" | "),
                )
                assertEquals(listOf("host", "fileweft"), InitializationOrder)
                assertEquals(1, OrderedDataSource.connectionAttempts.get())
            }
    }

    @Test
    fun `enabled mode fails closed for multiple data sources even when one is primary`() {
        contextRunner(MultipleDataSourceConfiguration::class.java)
            .withPropertyValues(
                "fileweft.persistence.migration-mode=MIGRATE",
                "fileweft.persistence.schema=fileweft_app",
            )
            .run { context ->
                val messages = context.startupFailure.causeMessages()
                assertTrue(messages.any { it.contains("found 2 DataSource beans") })
                assertTrue(messages.any { it.contains("explicit FlywayMigrationRunner") })
                assertEquals(0, PrimaryDataSource.connectionAttempts.get())
                assertEquals(0, SecondaryDataSource.connectionAttempts.get())
            }
    }

    @Test
    fun `explicit runner resolves multiple data sources without auto selection`() {
        contextRunner(MultipleDataSourceHostOverridesConfiguration::class.java)
            .withPropertyValues(
                "fileweft.persistence.migration-mode=VALIDATE",
                "fileweft.persistence.schema=fileweft_app",
            )
            .run { context ->
                assertEquals(null, context.startupFailure)
                assertSame(MultipleDataSourceHostRunner, context.getBean(FlywayMigrationRunner::class.java))
                assertSame(HostInitializer, context.getBean(FileWeftMigrationInitializer::class.java))
                assertEquals(0, PrimaryDataSource.connectionAttempts.get())
                assertEquals(0, SecondaryDataSource.connectionAttempts.get())
            }
    }

    @Test
    fun `customer runner and initializer make both defaults back off`() {
        contextRunner(HostOverridesConfiguration::class.java)
            .withPropertyValues(
                "fileweft.persistence.migration-mode=VALIDATE",
                "fileweft.persistence.schema=fileweft_app",
            )
            .run { context ->
                assertEquals(null, context.startupFailure)
                assertFalse(context.containsBean("fileWeftMigrationRunner"))
                assertFalse(context.containsBean("fileWeftMigrationInitializer"))
                assertSame(HostRunner, context.getBean(FlywayMigrationRunner::class.java))
                assertSame(HostInitializer, context.getBean(FileWeftMigrationInitializer::class.java))
                assertEquals(0, OverrideDataSource.connectionAttempts.get())
            }
    }

    @Test
    fun `publishes migration configuration metadata and orders after host Flyway`() {
        val annotation = assertNotNull(FileWeftAutoConfiguration::class.java.getAnnotation(AutoConfiguration::class.java))
        assertTrue(annotation.after.any { it == FlywayAutoConfiguration::class })
        val validatorMethod = FileWeftMigrationConfiguration::class.java.getDeclaredMethod(
            "fileWeftMigrationSettingsValidator",
            FileWeftProperties::class.java,
            org.springframework.beans.factory.ListableBeanFactory::class.java,
        )
        assertFalse(assertNotNull(validatorMethod.getAnnotation(Lazy::class.java)).value)
        val initializerMethod = FileWeftMigrationConfiguration::class.java.getDeclaredMethod(
            "fileWeftMigrationInitializer",
            FlywayMigrationRunner::class.java,
            FileWeftProperties::class.java,
        )
        assertNotNull(initializerMethod.getAnnotation(DependsOnDatabaseInitialization::class.java))
        assertFalse(assertNotNull(initializerMethod.getAnnotation(Lazy::class.java)).value)

        val metadata = requireNotNull(javaClass.classLoader.getResourceAsStream("META-INF/spring-configuration-metadata.json"))
            .use { input -> ObjectMapper().readTree(input) }
        val properties = metadata.path("properties").toList()
        val migrationMode = properties.single { it.path("name").asText() == "fileweft.persistence.migration-mode" }
        val schema = properties.single { it.path("name").asText() == "fileweft.persistence.schema" }
        val createSchema = properties.single { it.path("name").asText() == "fileweft.persistence.create-schema" }
        assertEquals("ai.icen.fw.persistence.migration.FileWeftMigrationMode", migrationMode.path("type").asText())
        assertEquals("DISABLED", migrationMode.path("defaultValue").asText())
        assertTrue(migrationMode.path("description").asText().contains("no migration database access"))
        assertTrue(schema.path("description").asText().contains("leading or trailing whitespace"))
        assertFalse(createSchema.path("defaultValue").booleanValue())
        assertTrue(createSchema.path("description").asText().contains("MIGRATE"))
    }

    private fun contextRunner(configuration: Class<*>): ApplicationContextRunner = ApplicationContextRunner()
        .withUserConfiguration(configuration, FileWeftMigrationConfiguration::class.java)

    private fun lazyContextRunner(configuration: Class<*>): ApplicationContextRunner = contextRunner(configuration)
        .withPropertyValues("spring.main.lazy-initialization=true")
        .withInitializer { context ->
            context.addBeanFactoryPostProcessor(LazyInitializationBeanFactoryPostProcessor())
        }

    private fun Throwable?.causeMessages(): List<String> = generateSequence(this) { it.cause }.mapNotNull { it.message }.toList()

    @Configuration(proxyBeanMethods = false)
    internal class SingleDataSourceConfiguration {
        @Bean fun dataSource(): DataSource = SingleDataSource
    }

    @Configuration(proxyBeanMethods = false)
    internal class HostInitializerConfiguration {
        @Bean fun dataSource(): DataSource = SingleDataSource
        @Bean fun hostMigrationInitializer(): FileWeftMigrationInitializer = HostInitializer
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleDataSourceConfiguration {
        @Bean @Primary fun primaryDataSource(): DataSource = PrimaryDataSource
        @Bean fun secondaryDataSource(): DataSource = SecondaryDataSource
    }

    @Configuration(proxyBeanMethods = false)
    internal class HostOverridesConfiguration {
        @Bean fun dataSource(): DataSource = OverrideDataSource
        @Bean fun hostMigrationRunner(): FlywayMigrationRunner = HostRunner
        @Bean fun hostMigrationInitializer(): FileWeftMigrationInitializer = HostInitializer
    }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleDataSourceHostOverridesConfiguration {
        @Bean @Primary fun primaryDataSource(): DataSource = PrimaryDataSource
        @Bean fun secondaryDataSource(): DataSource = SecondaryDataSource
        @Bean fun hostMigrationRunner(): FlywayMigrationRunner = MultipleDataSourceHostRunner
        @Bean fun hostMigrationInitializer(): FileWeftMigrationInitializer = HostInitializer
    }

    @Configuration(proxyBeanMethods = false)
    internal class OrderedDatabaseInitializationConfiguration {
        @Bean fun dataSource(): DataSource = OrderedDataSource
        @Bean fun selectedFileWeftMigrationRunner(): FlywayMigrationRunner =
            FlywayMigrationRunner(OrderedDataSource, "fileweft_app")

        @Bean
        @Lazy
        fun hostSqlInitializer(): TestRecordingDatabaseInitializer = TestRecordingDatabaseInitializer {
            InitializationOrder += "host"
        }
    }

    private companion object {
        val SingleDataSource = NeverConnectingDataSource()
        val PrimaryDataSource = NeverConnectingDataSource()
        val SecondaryDataSource = NeverConnectingDataSource()
        val OverrideDataSource = NeverConnectingDataSource()
        val InitializationOrder = mutableListOf<String>()
        val OrderedDataSource = NeverConnectingDataSource { InitializationOrder += "fileweft" }
        val HostRunner = FlywayMigrationRunner(OverrideDataSource, "fileweft_app")
        val MultipleDataSourceHostRunner = FlywayMigrationRunner(PrimaryDataSource, "fileweft_app")
        val HostInitializer = object : FileWeftMigrationInitializer {
            override fun initialize() = Unit
        }
    }
}

private class NeverConnectingDataSource(
    private val beforeConnectionFailure: () -> Unit = {},
) : DataSource {
    val connectionAttempts = AtomicInteger()
    fun reset() = connectionAttempts.set(0)
    override fun getConnection(): Connection {
        connectionAttempts.incrementAndGet()
        beforeConnectionFailure()
        throw AssertionError("The migration test must not open a database connection.")
    }
    override fun getConnection(username: String?, password: String?): Connection = getConnection()
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) = Unit
    override fun setLoginTimeout(seconds: Int) = Unit
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = Logger.getGlobal()
    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}

internal class TestRecordingDatabaseInitializer(
    private val initialization: () -> Unit,
) : InitializingBean {
    override fun afterPropertiesSet() = initialization()
}

class TestDatabaseInitializerDetector : DatabaseInitializerDetector {
    override fun detect(beanFactory: ConfigurableListableBeanFactory): Set<String> =
        BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
            beanFactory,
            TestRecordingDatabaseInitializer::class.java,
            false,
            false,
        ).toSet()
}
