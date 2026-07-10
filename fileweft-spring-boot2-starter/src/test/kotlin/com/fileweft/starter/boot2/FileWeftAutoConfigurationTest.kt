package com.fileweft.starter.boot2

import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.spi.authorization.AuthorizationAction
import com.fileweft.spi.authorization.AuthorizationEnvironment
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.authorization.AuthorizationResource
import com.fileweft.spi.authorization.AuthorizationSubject
import com.fileweft.spi.identity.UserRealmProvider
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FileWeftAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileWeftAutoConfiguration::class.java))

    @Test
    fun `binds the default tenant property`() {
        contextRunner.withPropertyValues("fileweft.default-tenant-id=tenant-a").run { context ->
            assertEquals("tenant-a", context.getBean(TenantProvider::class.java).currentTenant().tenantId.value)
        }
    }

    @Test
    fun `does not replace a customer tenant provider`() {
        contextRunner.withUserConfiguration(CustomerConfiguration::class.java).run { context ->
            assertSame(context.getBean("customerTenantProvider"), context.getBean(TenantProvider::class.java))
        }
    }

    @Test
    fun `defaults to no user and deny all authorization`() {
        contextRunner.run { context ->
            assertNull(context.getBean(UserRealmProvider::class.java).currentUser())
            assertFalse(context.getBean(AuthorizationProvider::class.java).authorize(request()).allowed)
        }
    }

    private fun request() = AuthorizationRequest(
        AuthorizationSubject(Identifier("user"), "USER"),
        AuthorizationResource(Identifier("document"), "DOCUMENT", Identifier("tenant")),
        AuthorizationAction("document:read"),
        AuthorizationEnvironment(),
    )

    @Configuration(proxyBeanMethods = false)
    class CustomerConfiguration {
        @Bean
        fun customerTenantProvider(): TenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("customer"))
        }
    }
}
