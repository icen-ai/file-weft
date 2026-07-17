package ai.icen.fw.starter.boot2

import ai.icen.fw.persistence.migration.FileWeftMigrationMode
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import org.springframework.beans.factory.BeanFactoryUtils
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Lazy
import org.springframework.core.type.AnnotatedTypeMetadata
import javax.sql.DataSource

/** Host-overridable startup hook for FlowWeft-owned migration validation or execution. */
interface FileWeftMigrationInitializer {
    fun initialize()
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileWeftProperties::class)
@Import(DatabaseInitializationDependencyConfigurer::class)
class FileWeftMigrationConfiguration {
    @Bean
    @Lazy(false)
    fun fileWeftMigrationSettingsValidator(
        properties: FileWeftProperties,
        beanFactory: org.springframework.beans.factory.ListableBeanFactory,
    ): org.springframework.beans.factory.InitializingBean = org.springframework.beans.factory.InitializingBean {
        val persistence = properties.persistence
        FileWeftMigrationSettings.validate(persistence)
        if (persistence.migrationMode != FileWeftMigrationMode.DISABLED) {
            val runnerNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
                beanFactory,
                FlywayMigrationRunner::class.java,
                false,
                false,
            ).distinct()
            if (runnerNames.isEmpty()) {
                val dataSourceCount = fileWeftApplicationDataSourceBeanNames(beanFactory).size
                error(
                    "FlowWeft migration mode ${persistence.migrationMode} found $dataSourceCount DataSource beans " +
                        "but no unambiguous FlywayMigrationRunner. Register an explicit FlywayMigrationRunner bean " +
                        "or set fileweft.persistence.migration-mode=DISABLED.",
                )
            }
            check(runnerNames.size == 1) {
                "FlowWeft migration mode ${persistence.migrationMode} requires exactly one FlywayMigrationRunner " +
                    "but found ${runnerNames.size}."
            }
        }
    }

    @Bean
    @Conditional(FileWeftMigrationEnabledCondition::class, ExactlyOneDataSourceCondition::class)
    @ConditionalOnMissingBean(FlywayMigrationRunner::class)
    fun fileWeftMigrationRunner(
        dataSource: DataSource,
        properties: FileWeftProperties,
    ): FlywayMigrationRunner {
        val persistence = properties.persistence
        FileWeftMigrationSettings.validate(persistence)
        return FlywayMigrationRunner(
            dataSource,
            FileWeftMigrationSettings.requiredSchema(persistence),
            persistence.createSchema,
        )
    }

    @Bean
    @Conditional(FileWeftMigrationEnabledCondition::class)
    @ConditionalOnBean(FlywayMigrationRunner::class)
    @ConditionalOnMissingBean(FileWeftMigrationInitializer::class)
    @DependsOnDatabaseInitialization
    @Lazy(false)
    fun fileWeftMigrationInitializer(
        runner: FlywayMigrationRunner,
        properties: FileWeftProperties,
    ): FileWeftMigrationInitializer = DefaultFileWeftMigrationInitializer(runner, properties.persistence)
}

private class DefaultFileWeftMigrationInitializer(
    private val runner: FlywayMigrationRunner,
    private val properties: FileWeftProperties.PersistenceProperties,
) : FileWeftMigrationInitializer, org.springframework.beans.factory.InitializingBean {
    override fun afterPropertiesSet() = initialize()

    override fun initialize() {
        FileWeftMigrationSettings.validate(properties)
        when (properties.migrationMode) {
            FileWeftMigrationMode.DISABLED -> Unit
            FileWeftMigrationMode.VALIDATE -> runner.validate()
            FileWeftMigrationMode.MIGRATE -> runner.migrate()
        }
    }
}

private object FileWeftMigrationSettings {
    fun requiredSchema(properties: FileWeftProperties.PersistenceProperties): String {
        check(properties.migrationMode != FileWeftMigrationMode.DISABLED) {
            "FlowWeft migration schema is not used when migration mode is DISABLED."
        }
        val schema = properties.schema
        require(schema.isNotBlank()) {
            "fileweft.persistence.schema must not be blank when fileweft.persistence.migration-mode is not DISABLED."
        }
        require(!schema.first().isFileWeftWhitespace() && !schema.last().isFileWeftWhitespace()) {
            "fileweft.persistence.schema must not have leading or trailing whitespace."
        }
        return schema
    }

    fun validate(properties: FileWeftProperties.PersistenceProperties) {
        require(!properties.createSchema || properties.migrationMode == FileWeftMigrationMode.MIGRATE) {
            "fileweft.persistence.create-schema=true is allowed only when fileweft.persistence.migration-mode=MIGRATE."
        }
        if (properties.migrationMode != FileWeftMigrationMode.DISABLED) {
            requiredSchema(properties)
        }
    }

    private fun Char.isFileWeftWhitespace(): Boolean =
        Character.isWhitespace(this) || Character.isSpaceChar(this)
}

private class FileWeftMigrationEnabledCondition : AnyNestedCondition(ConfigurationPhase.REGISTER_BEAN) {
    @ConditionalOnProperty(prefix = "fileweft.persistence", name = ["migration-mode"], havingValue = "VALIDATE")
    class Validate

    @ConditionalOnProperty(prefix = "fileweft.persistence", name = ["migration-mode"], havingValue = "MIGRATE")
    class Migrate
}

private class ExactlyOneDataSourceCondition : SpringBootCondition() {
    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val beanFactory = context.beanFactory
            ?: return ConditionOutcome.noMatch("No BeanFactory is available to resolve a DataSource")
        val candidates = fileWeftApplicationDataSourceBeanNames(beanFactory)
        return if (candidates.size == 1) {
            ConditionOutcome.match("Exactly one DataSource bean is available")
        } else {
            ConditionOutcome.noMatch("Expected exactly one DataSource bean but found ${candidates.size}")
        }
    }
}

internal fun fileWeftApplicationDataSourceBeanNames(beanFactory: ListableBeanFactory): List<String> =
    BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
        beanFactory,
        DataSource::class.java,
        false,
        false,
    ).distinct()
