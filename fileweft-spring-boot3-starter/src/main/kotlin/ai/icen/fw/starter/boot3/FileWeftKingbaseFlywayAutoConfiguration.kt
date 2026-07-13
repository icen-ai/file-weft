package ai.icen.fw.starter.boot3

import ai.icen.fw.persistence.migration.KingbaseFlywayCompatibility
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

/** Adapts only Spring Boot's selected Flyway DataSource when it is KingbaseES. */
@AutoConfiguration(before = [FlywayAutoConfiguration::class])
@ConditionalOnClass(FlywayConfigurationCustomizer::class)
@ConditionalOnProperty(
    prefix = "fileweft.persistence",
    name = ["kingbase-flyway-compatibility-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class FileWeftKingbaseFlywayAutoConfiguration {
    @Bean(FILEWEFT_KINGBASE_FLYWAY_CUSTOMIZER_BEAN)
    @ConditionalOnMissingBean(name = [FILEWEFT_KINGBASE_FLYWAY_CUSTOMIZER_BEAN])
    fun fileWeftKingbaseFlywayConfigurationCustomizer(): FlywayConfigurationCustomizer =
        FileWeftKingbaseFlywayConfigurationCustomizer()
}

@Order(Ordered.LOWEST_PRECEDENCE)
private class FileWeftKingbaseFlywayConfigurationCustomizer : FlywayConfigurationCustomizer {
    override fun customize(configuration: FluentConfiguration) {
        val selectedDataSource = configuration.dataSource ?: return
        configuration.dataSource(KingbaseFlywayCompatibility.adaptIfNecessary(selectedDataSource))
    }
}

private const val FILEWEFT_KINGBASE_FLYWAY_CUSTOMIZER_BEAN =
    "fileWeftKingbaseFlywayConfigurationCustomizer"
