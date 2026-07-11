package com.fileweft.dev.platform

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.Ordered
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class DevPlatformConfiguration {
    @Bean(initMethod = "migrate")
    fun devPlatformMigrationRunner(dataSource: DataSource): DevPlatformMigrationRunner = DevPlatformMigrationRunner(dataSource)

    @Bean
    fun devPlatformFaultControl(): DevPlatformFaultControl = DevPlatformFaultControl()

    @Bean
    fun devPlatformAuthenticator(properties: DevPlatformProperties): DevPlatformAuthenticator =
        DevPlatformAuthenticator(properties.sharedSecret)

    @Bean
    fun devPlatformAuthenticationFilter(
        authenticator: DevPlatformAuthenticator,
    ): FilterRegistrationBean<DevPlatformAuthenticationFilter> = FilterRegistrationBean(
        DevPlatformAuthenticationFilter(authenticator),
    ).apply {
        addUrlPatterns("/platform/v1/*")
        order = Ordered.HIGHEST_PRECEDENCE
    }

    @Bean
    fun devPlatformDownloadPolicy(properties: DevPlatformProperties): DevPlatformDownloadPolicy = DevPlatformDownloadPolicy(
        properties.allowedDownloadHosts,
        properties.maxDownloadBytes,
    )

    @Bean
    fun devPlatformRepository(jdbcTemplate: JdbcTemplate): DevPlatformRepository = DevPlatformRepository(jdbcTemplate)

    @Bean
    fun devPlatformClock(): Clock = Clock.systemUTC()

    @Bean
    fun devPlatformService(
        repository: DevPlatformRepository,
        faultControl: DevPlatformFaultControl,
        downloadPolicy: DevPlatformDownloadPolicy,
        clock: Clock,
    ): DevPlatformService = DevPlatformService(repository, faultControl, downloadPolicy, clock)
}
