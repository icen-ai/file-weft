package com.fileweft.dev.platform

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    fun devPlatformRepository(jdbcTemplate: JdbcTemplate): DevPlatformRepository = DevPlatformRepository(jdbcTemplate)

    @Bean
    fun devPlatformClock(): Clock = Clock.systemUTC()

    @Bean
    fun devPlatformService(
        repository: DevPlatformRepository,
        faultControl: DevPlatformFaultControl,
        clock: Clock,
    ): DevPlatformService = DevPlatformService(repository, faultControl, clock)
}
