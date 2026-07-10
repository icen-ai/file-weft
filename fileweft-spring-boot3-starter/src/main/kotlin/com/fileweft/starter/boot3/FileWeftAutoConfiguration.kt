package com.fileweft.starter.boot3

import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

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
    fun fileWeftUserRealmProvider(): UserRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity? = null
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    @Bean
    @ConditionalOnMissingBean(AuthorizationProvider::class)
    fun fileWeftAuthorizationProvider(): AuthorizationProvider = object : AuthorizationProvider {
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision =
            AuthorizationDecision(false, "No AuthorizationProvider has been configured.")
    }
}
