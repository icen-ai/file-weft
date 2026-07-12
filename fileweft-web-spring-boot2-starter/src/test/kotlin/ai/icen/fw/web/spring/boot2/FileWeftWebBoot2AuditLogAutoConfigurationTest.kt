package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.audit.DocumentAuditLogQueryService
import ai.icen.fw.web.api.v1.audit.DocumentAuditLogPageQuery
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.audit.DocumentAuditLogApiFacade
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.io.support.SpringFactoriesLoader
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileWeftWebBoot2AuditLogAutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(FileWeftWebBoot2AuditLogAutoConfiguration::class.java),
    )

    @Test
    fun `registers audit-log auto configuration through spring factories`() {
        val factories = SpringFactoriesLoader.loadFactoryNames(
            EnableAutoConfiguration::class.java,
            FileWeftWebBoot2AuditLogAutoConfiguration::class.java.classLoader,
        )

        assertTrue(factories.contains(FileWeftWebBoot2AuditLogAutoConfiguration::class.java.name))
    }

    @Test
    fun `keeps a fail-closed route when no query service is installed`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeansOfType(DocumentAuditLogApiFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(DocumentV1AuditLogController::class.java).size)
            assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
            assertThrows<IllegalStateException> {
                context.getBean(DocumentAuditLogApiFacade::class.java)
                    .page("document-1", DocumentAuditLogPageQuery())
            }
        }
    }

    @Test
    fun `wires the single query-service candidate into the formal facade`() {
        val fixture = Boot2AuditLogControllerFixture()
        val service = fixture.service()

        contextRunner.withBean(DocumentAuditLogQueryService::class.java, Supplier { service }).run { context ->
            assertNotNull(context.getBeanProvider(DocumentV1AuditLogController::class.java).getIfAvailable())
            val page = context.getBean(DocumentAuditLogApiFacade::class.java)
                .page("document-1", DocumentAuditLogPageQuery(limit = 1))
            assertEquals(listOf("audit-b"), page.items.map { item -> item.id })
            assertEquals(1, fixture.pageRequests.size)
        }
    }

    @Test
    fun `rejects multiple query-service candidates even when one is primary`() {
        contextRunner.withUserConfiguration(MultipleAuditLogQueries::class.java).run { context ->
            val failure = assertNotNull(context.startupFailure)
            assertTrue(failure.causeChain().any { cause ->
                cause.message?.contains("multiple query-service candidates") == true
            })
        }
    }

    @Test
    fun `backs off from host facade response factory and controller`() {
        val hostFacade = DocumentAuditLogApiFacade(emptyList())
        val hostResponses = V1ApiResponseFactory()
        val hostController = Mockito.mock(DocumentV1AuditLogController::class.java)

        contextRunner
            .withBean(DocumentAuditLogApiFacade::class.java, Supplier { hostFacade })
            .withBean(V1ApiResponseFactory::class.java, Supplier { hostResponses })
            .withBean(DocumentV1AuditLogController::class.java, Supplier { hostController })
            .run { context ->
                assertSame(hostFacade, context.getBean(DocumentAuditLogApiFacade::class.java))
                assertSame(hostResponses, context.getBean(V1ApiResponseFactory::class.java))
                assertSame(hostController, context.getBean(DocumentV1AuditLogController::class.java))
                assertEquals(1, context.getBeansOfType(DocumentAuditLogApiFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(DocumentV1AuditLogController::class.java).size)
            }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { failure -> failure.cause }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleAuditLogQueries {
        @Bean
        @Primary
        fun primaryAuditLogs(): DocumentAuditLogQueryService = Boot2AuditLogControllerFixture().service()

        @Bean
        fun secondaryAuditLogs(): DocumentAuditLogQueryService = Boot2AuditLogControllerFixture().service()
    }
}
