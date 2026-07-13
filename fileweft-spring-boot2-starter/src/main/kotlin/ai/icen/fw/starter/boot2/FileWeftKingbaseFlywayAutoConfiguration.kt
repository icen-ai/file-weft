package ai.icen.fw.starter.boot2

import ai.icen.fw.persistence.migration.KingbaseFlywayCompatibility
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

/** Adapts only Spring Boot's selected Flyway DataSource when it is KingbaseES. */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(DataSourceAutoConfiguration::class)
@AutoConfigureBefore(FlywayAutoConfiguration::class)
@ConditionalOnClass(FlywayConfigurationCustomizer::class)
@ConditionalOnProperty(
    prefix = "fileweft.persistence",
    name = ["kingbase-flyway-compatibility-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class FileWeftKingbaseFlywayAutoConfiguration {
    /**
     * Boot 2 selects or constructs its final Flyway DataSource before applying
     * ordered customizers, but does not inspect the database product until
     * Flyway is loaded afterwards. Adapt that final selection in place so a
     * sole application DataSource, a host @FlywayDataSource, and
     * spring.flyway.url all follow one path without adding another host bean.
     */
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

internal const val FILEWEFT_KINGBASE_FLYWAY_CUSTOMIZER_BEAN =
    "fileWeftKingbaseFlywayConfigurationCustomizer"
