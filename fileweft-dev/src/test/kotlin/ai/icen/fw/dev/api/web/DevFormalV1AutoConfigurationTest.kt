package ai.icen.fw.dev.api.web

import ai.icen.fw.application.document.DocumentDownloadService
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.dev.api.config.DevRole
import ai.icen.fw.dev.api.config.FileWeftDevProperties
import ai.icen.fw.dev.api.security.DevAuthenticationFilter
import ai.icen.fw.dev.api.security.DevSessionStore
import ai.icen.fw.dev.api.security.DevTraceContextProvider
import ai.icen.fw.dev.api.security.DevTraceFilter
import ai.icen.fw.dev.api.security.DevUserDirectory
import ai.icen.fw.dev.api.service.DevAuthService
import ai.icen.fw.starter.boot3.FileWeftAutoConfiguration
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.doctor.DoctorApiFacade
import ai.icen.fw.web.runtime.v1.document.DocumentApiDownloadFacade
import ai.icen.fw.web.runtime.v1.document.DocumentApiReadFacade
import ai.icen.fw.web.runtime.v1.document.DocumentApiWriteFacade
import ai.icen.fw.web.spring.boot3.FileWeftWebBoot3AutoConfiguration
import ai.icen.fw.web.spring.boot3.FileWeftWebBoot3ContentAutoConfiguration
import ai.icen.fw.web.spring.boot3.FileWeftWebBoot3DoctorAutoConfiguration
import ai.icen.fw.web.spring.boot3.FileWeftWebBoot3WriteAutoConfiguration
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentContentController
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentReadController
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentWriteController
import ai.icen.fw.web.spring.boot3.v1.doctor.V1DocumentDoctorController
import ai.icen.fw.web.spring.boot3.v1.doctor.V1SystemDoctorController
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import java.io.PrintWriter
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DevFormalV1AutoConfigurationTest {
    @TempDir
    lateinit var storageRoot: Path

    @Test
    fun `registers the formal v1 surface beside the existing Dev API from starter capabilities`() {
        contextRunner().run { context ->
            assertNotNull(context.getBean(DocumentQueryService::class.java))
            assertNotNull(context.getBean(DocumentDraftService::class.java))
            assertNotNull(context.getBean(DocumentDownloadService::class.java))

            assertEquals(1, context.getBeansOfType(DocumentApiReadFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(DocumentApiWriteFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(DocumentApiDownloadFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(V1DocumentReadController::class.java).size)
            assertEquals(1, context.getBeansOfType(V1DocumentWriteController::class.java).size)
            assertEquals(1, context.getBeansOfType(V1DocumentContentController::class.java).size)
            assertEquals(1, context.getBeansOfType(V1ApiResponseFactory::class.java).size)
            assertEquals(1, context.getBeansOfType(DoctorApiFacade::class.java).size)
            assertEquals(1, context.getBeansOfType(V1DocumentDoctorController::class.java).size)
            assertEquals(1, context.getBeansOfType(V1SystemDoctorController::class.java).size)

            assertEquals(1, context.getBeansOfType(DevAuthController::class.java).size)
            assertEquals(1, context.getBeansOfType(DevAuthenticationFilter::class.java).size)
            assertEquals(1, context.getBeansOfType(DevTraceFilter::class.java).size)
        }
    }

    @Test
    fun `protects the formal v1 routes with the existing Dev authentication filter`() {
        contextRunner().run { context ->
            val controller: V1DocumentReadController = context.getBean(V1DocumentReadController::class.java)
            val doctorController: V1DocumentDoctorController = context.getBean(V1DocumentDoctorController::class.java)
            val systemDoctorController: V1SystemDoctorController = context.getBean(V1SystemDoctorController::class.java)
            val traceFilter: jakarta.servlet.Filter = context.getBean(DevTraceFilter::class.java)
            val authenticationFilter: jakarta.servlet.Filter = context.getBean(DevAuthenticationFilter::class.java)
            val mvc = MockMvcBuilders
                .standaloneSetup(controller, doctorController, systemDoctorController)
                .addFilters<StandaloneMockMvcBuilder>(traceFilter, authenticationFilter)
                .build()

            mvc.perform(get("/fileweft/v1/documents").header("X-Trace-Id", "formal-auth-test"))
                .andExpect(status().isUnauthorized)
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(header().string("X-Trace-Id", "formal-auth-test"))
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"fileweft-dev\""))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.*").value(org.hamcrest.Matchers.hasSize<Any>(5)))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("Authentication is required."))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.message").value("Authentication is required."))
                .andExpect(jsonPath("$.traceId").value("formal-auth-test"))

            mvc.perform(get("/fileweft/v1"))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.*").value(org.hamcrest.Matchers.hasSize<Any>(5)))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))

            mvc.perform(get("/fileweft/v1/documents/document-1/doctor"))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))

            mvc.perform(get("/fileweft/v1/doctor"))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))

            mvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("请先登录开发测试平台。"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist())
        }
    }

    private fun contextRunner(): WebApplicationContextRunner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                FileWeftAutoConfiguration::class.java,
                FileWeftWebBoot3AutoConfiguration::class.java,
                FileWeftWebBoot3WriteAutoConfiguration::class.java,
                FileWeftWebBoot3ContentAutoConfiguration::class.java,
                FileWeftWebBoot3DoctorAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(DevProbeConfiguration::class.java)
        .withPropertyValues(
            "fileweft.storage.local-root=${storageRoot.toAbsolutePath()}",
            "fileweft.outbox.backlog-metrics-enabled=false",
        )

    @Configuration(proxyBeanMethods = false)
    @Import(DevAuthController::class, DevTraceFilter::class, DevAuthenticationFilter::class)
    internal class DevProbeConfiguration {
        @Bean
        fun dataSource(): DataSource = StubDataSource

        @Bean
        fun devSessionStore(): DevSessionStore = DevSessionStore()

        @Bean
        fun devTraceContextProvider(): DevTraceContextProvider = DevTraceContextProvider()

        @Bean
        fun devAuthService(sessions: DevSessionStore): DevAuthService {
            val properties = FileWeftDevProperties().apply {
                users += FileWeftDevProperties.User().apply {
                    id = "test-viewer"
                    username = "viewer@test"
                    password = "test-password"
                    displayName = "Test viewer"
                    tenantId = "test-tenant"
                    role = DevRole.VIEWER
                }
            }
            return DevAuthService(DevUserDirectory(properties), sessions)
        }
    }

    private object StubDataSource : DataSource {
        override fun getConnection(): Connection = throw UnsupportedOperationException("Test data source")

        override fun getConnection(username: String?, password: String?): Connection =
            throw UnsupportedOperationException("Test data source")

        override fun getLogWriter(): PrintWriter? = null

        override fun setLogWriter(out: PrintWriter?) = Unit

        override fun setLoginTimeout(seconds: Int) = Unit

        override fun getLoginTimeout(): Int = 0

        override fun getParentLogger(): Logger = Logger.getGlobal()

        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()

        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }
}
