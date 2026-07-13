package ai.icen.fw.web.spring.boot3.v1.upload

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.V1MethodNotAllowedException
import ai.icen.fw.web.runtime.v1.V1NotAcceptableException
import ai.icen.fw.web.runtime.v1.V1UnsupportedMediaTypeException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.view.json.MappingJackson2JsonView
import org.springframework.web.util.UrlPathHelper

/** Adds a path-scoped resolver for request-mapping and decoding failures of the formal upload resource. */
class V1ResumableUploadRequestFailureHandler(
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) : WebMvcConfigurer {
    private val jsonView = MappingJackson2JsonView().apply { setExtractValueFromSingleKeyModel(true) }
    private val pathHelper = UrlPathHelper()

    override fun extendHandlerExceptionResolvers(resolvers: MutableList<HandlerExceptionResolver>) {
        resolvers.add(0, uploadExceptionResolver())
    }

    internal fun uploadExceptionResolver(): HandlerExceptionResolver = HandlerExceptionResolver { request, response, _, failure ->
        val path = pathHelper.getLookupPathForRequest(request)
        if (path != UPLOADS_PATH && !path.startsWith("$UPLOADS_PATH/")) {
            return@HandlerExceptionResolver null
        }
        val publicFailure = when (failure) {
            is HttpMessageNotReadableException ->
                IllegalArgumentException("Resumable upload request JSON is invalid.")
            is HttpMediaTypeNotSupportedException -> V1UnsupportedMediaTypeException()
            is HttpMediaTypeNotAcceptableException -> V1NotAcceptableException()
            is HttpRequestMethodNotSupportedException -> V1MethodNotAllowedException()
            else -> return@HandlerExceptionResolver null
        }
        val mapped = responses.failure(publicFailure, currentTraceId())
        response.status = mapped.status.statusCode
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store")
        response.setHeader(HttpHeaders.PRAGMA, "no-cache")
        response.setHeader("X-Content-Type-Options", "nosniff")
        if (failure is HttpRequestMethodNotSupportedException) {
            failure.supportedHttpMethods?.takeIf { methods -> methods.isNotEmpty() }?.let { methods ->
                response.setHeader(HttpHeaders.ALLOW, methods.joinToString(", ") { method -> method.toString() })
            }
        }
        ModelAndView(
            jsonView,
            mapOf(RESPONSE_MODEL_KEY to mapped.response),
        )
    }

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val UPLOADS_PATH: String = "/fileweft/v1/uploads"
        const val RESPONSE_MODEL_KEY: String = "response"
    }
}
