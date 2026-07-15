package ai.icen.fw.web.spring.boot2

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

/** Path-scoped safe JSON mapping for catalog failures raised before controller invocation. */
class DocumentCatalogV1RequestFailureHandler(
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) : WebMvcConfigurer {
    private val jsonView = MappingJackson2JsonView().apply { setExtractValueFromSingleKeyModel(true) }
    private val pathHelper = UrlPathHelper()

    override fun extendHandlerExceptionResolvers(resolvers: MutableList<HandlerExceptionResolver>) {
        resolvers.add(0, catalogExceptionResolver())
    }

    internal fun catalogExceptionResolver(): HandlerExceptionResolver = HandlerExceptionResolver { request, response, _, failure ->
        val path = pathHelper.getLookupPathForRequest(request)
        if (!isCatalogPath(path)) return@HandlerExceptionResolver null
        val publicFailure = when (failure) {
            is HttpMessageNotReadableException -> IllegalArgumentException("Document catalog request JSON is invalid.")
            is HttpMediaTypeNotSupportedException -> V1UnsupportedMediaTypeException()
            is HttpMediaTypeNotAcceptableException -> V1NotAcceptableException()
            is HttpRequestMethodNotSupportedException -> V1MethodNotAllowedException()
            else -> return@HandlerExceptionResolver null
        }
        val mapped = responses.failure(publicFailure, currentTraceId())
        response.status = mapped.status.statusCode
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.setHeader(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE)
        response.setHeader(HttpHeaders.PRAGMA, NO_CACHE)
        response.setHeader(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
        if (failure is HttpRequestMethodNotSupportedException) {
            failure.supportedHttpMethods?.takeIf { it.isNotEmpty() }?.let { methods ->
                response.setHeader(HttpHeaders.ALLOW, methods.joinToString(", ") { it.toString() })
            }
        }
        ModelAndView(jsonView, mapOf(RESPONSE_MODEL_KEY to mapped.response))
    }

    private fun isCatalogPath(path: String): Boolean {
        if (path == FOLDERS_PATH) return true
        if (!path.startsWith(DOCUMENTS_PREFIX) || !path.endsWith(MOVE_SUFFIX)) return false
        val documentId = path.substring(DOCUMENTS_PREFIX.length, path.length - MOVE_SUFFIX.length)
        return documentId.isNotEmpty() && '/' !in documentId
    }

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val FOLDERS_PATH = "/fileweft/v1/catalog/folders"
        const val DOCUMENTS_PREFIX = "/fileweft/v1/documents/"
        const val MOVE_SUFFIX = "/catalog-folder"
        const val PRIVATE_NO_STORE = "private, no-store"
        const val NO_CACHE = "no-cache"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
        const val RESPONSE_MODEL_KEY = "response"
    }
}
