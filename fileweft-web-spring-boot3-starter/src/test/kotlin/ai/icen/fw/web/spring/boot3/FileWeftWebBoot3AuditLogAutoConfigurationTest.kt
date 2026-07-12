package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.audit.DocumentAuditLogQueryService
import ai.icen.fw.web.api.v1.audit.DocumentAuditLogPageQuery
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.audit.DocumentAuditLogApiFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentAuditLogController
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.nio.charset.StandardCharsets
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileWeftWebBoot3AuditLogAutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(
            JacksonAutoConfiguration::class.java,
            FileWeftWebBoot3AuditLogAutoConfiguration::class.java,
        ),
    )

    @Test
    fun `registers audit-log auto configuration after the matching runtime starter`() {
        val resource = requireNotNull(
            javaClass.classLoader.getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            ),
        )
        val registrations = resource.use { input -> String(input.readBytes(), StandardCharsets.UTF_8) }
        val annotation = FileWeftWebBoot3AuditLogAutoConfiguration::class.java
            .getAnnotation(AutoConfiguration::class.java)

        assertTrue(registrations.lineSequence().any { line ->
            line.trim() == FileWeftWebBoot3AuditLogAutoConfiguration::class.java.name
        })
        assertEquals(
            listOf("ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"),
            annotation.afterName.toList(),
        )
    }

    @Test
    fun `keeps a fail-closed route when no query service is installed`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeansOfType(DocumentAuditLogApiFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(V1DocumentAuditLogController::class.java).size)
            assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
            assertThrows<IllegalStateException> {
                context.getBean(DocumentAuditLogApiFacade::class.java)
                    .page("document-1", DocumentAuditLogPageQuery())
            }
        }
    }

    @Test
    fun `wires the single query-service candidate into the formal facade`() {
        val fixture = Boot3AuditLogControllerFixture()
        val service = fixture.service()

        contextRunner.withBean(DocumentAuditLogQueryService::class.java, Supplier { service }).run { context ->
            assertNotNull(context.getBeanProvider(V1DocumentAuditLogController::class.java).getIfAvailable())
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
        val hostController = Mockito.mock(V1DocumentAuditLogController::class.java)

        contextRunner
            .withBean(DocumentAuditLogApiFacade::class.java, Supplier { hostFacade })
            .withBean(V1ApiResponseFactory::class.java, Supplier { hostResponses })
            .withBean(V1DocumentAuditLogController::class.java, Supplier { hostController })
            .run { context ->
                assertSame(hostFacade, context.getBean(DocumentAuditLogApiFacade::class.java))
                assertSame(hostResponses, context.getBean(V1ApiResponseFactory::class.java))
                assertSame(hostController, context.getBean(V1DocumentAuditLogController::class.java))
                assertEquals(1, context.getBeansOfType(DocumentAuditLogApiFacade::class.java).size)
                assertEquals(1, context.getBeansOfType(V1DocumentAuditLogController::class.java).size)
            }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { failure -> failure.cause }

    @Configuration(proxyBeanMethods = false)
    internal class MultipleAuditLogQueries {
        @Bean
        @Primary
        fun primaryAuditLogs(): DocumentAuditLogQueryService = Boot3AuditLogControllerFixture().service()

        @Bean
        fun secondaryAuditLogs(): DocumentAuditLogQueryService = Boot3AuditLogControllerFixture().service()
    }
}
