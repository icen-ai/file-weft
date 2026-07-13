package ai.icen.fw.web.spring.boot2

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.upload.ResumableUploadApiFacade
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.web.MockServletContext
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class V1ResumableUploadResolverCoexistenceTest {
    @Test
    fun `upload transport resolver does not consume host controller advice failures`() {
        val context = AnnotationConfigWebApplicationContext().apply {
            servletContext = MockServletContext()
            register(TestConfiguration::class.java)
            refresh()
        }
        try {
            val mvc = MockMvcBuilders.webAppContextSetup(context).build()

            mvc.perform(
                post("/host/resource")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{"),
            )
                .andExpect(status().isUnprocessableEntity)
                .andExpect(content().string("host-advice"))

            mvc.perform(patch("/host/resource"))
                .andExpect(status().isUnprocessableEntity)
                .andExpect(content().string("host-advice"))

            mvc.perform(patch("/fileweft/v1/uploads/upload-1"))
                .andExpect(status().isMethodNotAllowed)
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
        } finally {
            context.close()
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    class TestConfiguration {
        @Bean
        fun responses(): V1ApiResponseFactory = V1ApiResponseFactory()

        @Bean
        fun uploadFacade(): ResumableUploadApiFacade = Mockito.mock(ResumableUploadApiFacade::class.java)

        @Bean
        fun uploadController(
            facade: ResumableUploadApiFacade,
            responses: V1ApiResponseFactory,
        ): V1ResumableUploadController = V1ResumableUploadController(facade, responses, null)

        @Bean
        fun uploadFailureHandler(responses: V1ApiResponseFactory): V1ResumableUploadRequestFailureHandler =
            V1ResumableUploadRequestFailureHandler(responses, null as TraceContextProvider?)

        @Bean
        fun hostController(): HostController = HostController()

        @Bean
        fun hostAdvice(): HostAdvice = HostAdvice()
    }

    @RestController
    class HostController {
        @GetMapping("/host/resource")
        fun inspect(): Map<String, String> = mapOf("status" to "ok")

        @PostMapping("/host/resource", consumes = [MediaType.APPLICATION_JSON_VALUE])
        fun create(@RequestBody body: Map<String, Any>): Map<String, Any> = body
    }

    @RestControllerAdvice
    class HostAdvice {
        @ExceptionHandler(
            value = [HttpMessageNotReadableException::class, HttpRequestMethodNotSupportedException::class],
        )
        fun handle(): ResponseEntity<String> = ResponseEntity.unprocessableEntity().body("host-advice")
    }
}
