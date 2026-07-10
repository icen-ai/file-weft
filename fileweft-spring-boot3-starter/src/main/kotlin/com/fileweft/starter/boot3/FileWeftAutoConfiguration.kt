package com.fileweft.starter.boot3

import com.fileweft.adapter.authorization.DefaultAuthorizationProvider
import com.fileweft.adapter.identity.DefaultUserRealmProvider
import com.fileweft.adapter.storage.LocalStorageAdapter
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.tenant.TenantProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.nio.file.Paths

@AutoConfiguration
@EnableConfigurationProperties(FileWeftProperties::class)
class FileWeftAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(TenantProvider::class)
    fun fileWeftTenantProvider(properties: FileWeftProperties): TenantProvider = object : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(Identifier(properties.defaultTenantId))
    }

    @Bean
    @ConditionalOnMissingBean(UserRealmProvider::class)
    fun fileWeftUserRealmProvider(): UserRealmProvider = DefaultUserRealmProvider()

    @Bean
    @ConditionalOnMissingBean(AuthorizationProvider::class)
    fun fileWeftAuthorizationProvider(): AuthorizationProvider = DefaultAuthorizationProvider()

    @Bean
    @ConditionalOnMissingBean(StorageAdapter::class)
    fun fileWeftStorageAdapter(properties: FileWeftProperties): StorageAdapter {
        require(properties.storage.localRoot.isNotBlank()) { "fileweft.storage.local-root must not be blank." }
        return LocalStorageAdapter(Paths.get(properties.storage.localRoot))
    }
}
