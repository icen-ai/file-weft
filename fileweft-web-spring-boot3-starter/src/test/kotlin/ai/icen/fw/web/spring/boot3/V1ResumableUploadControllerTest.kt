package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.ResumableUploadStateException
import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.v1.upload.ResumableUploadCompletionDto
import ai.icen.fw.web.api.v1.upload.ResumableUploadDto
import ai.icen.fw.web.api.v1.upload.ResumableUploadPartDto
import ai.icen.fw.web.api.v1.upload.ResumableUploadStatuses
import ai.icen.fw.web.api.v1.upload.StartResumableUploadRequest
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.upload.ResumableUploadApiFacade
import ai.icen.fw.web.spring.boot3.v1.upload.V1ResumableUploadController
import ai.icen.fw.web.spring.boot3.v1.upload.V1ResumableUploadRequestFailureHandler
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V1ResumableUploadControllerTest {
    @Test
    fun `exposes the five JSON-envelope routes and keeps part content streaming`() {
        val calls = mutableListOf<UploadCall>()
        var delegatedPartContent: InputStream? = null
        var delegatedPartBytes: ByteArray? = null
        val facade = Mockito.mock(ResumableUploadApiFacade::class.java, Answer<Any?> { invocation ->
            calls += UploadCall(invocation.method.name, invocation.arguments.toList())
            when (invocation.method.name) {
                "start" -> uploading()
                "inspect" -> uploading(
                    parts = listOf(ResumableUploadPartDto(UPLOAD_ID, 1, 6, 102)),
                )
                "uploadPart" -> {
                    delegatedPartContent = invocation.arguments[3] as InputStream
                    delegatedPartBytes = delegatedPartContent!!.readBytes()
                    ResumableUploadPartDto(UPLOAD_ID, 1, 6, 102)
                }
                "complete" -> completion()
                "abort" -> aborted()
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val mvc = mockMvc(facade)
        val payload = "分片内容".toByteArray(StandardCharsets.UTF_8)

        mvc.perform(
            post(UPLOADS_PATH)
                .header(IDEMPOTENCY_KEY, "upload-create-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"fileName":"证据.pdf","contentLength":6,"contentType":"application/pdf","contentHash":"sha256:${"a".repeat(64)}"}""",
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.LOCATION, "$UPLOADS_PATH/$UPLOAD_ID"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))
            .andExpect(jsonPath("$.data.uploadId").value(UPLOAD_ID))
            .andExpect(jsonPath("$.data.status").value(ResumableUploadStatuses.UPLOADING))
            .andExpect(jsonPath("$.data.tenantId").doesNotExist())
            .andExpect(jsonPath("$.data.ownerId").doesNotExist())
            .andExpect(jsonPath("$.data.assetType").doesNotExist())
            .andExpect(jsonPath("$.data.storageLocation").doesNotExist())
            .andExpect(jsonPath("$.data.lastError").doesNotExist())

        mvc.perform(get("$UPLOADS_PATH/$UPLOAD_ID"))
            .andExpectUploadSuccess()
            .andExpect(jsonPath("$.data.uploadedParts[0].partNumber").value(1))
            .andExpect(jsonPath("$.data.uploadedParts[0].eTag").doesNotExist())

        mvc.perform(
            put("$UPLOADS_PATH/$UPLOAD_ID/parts/1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(PART_LENGTH, payload.size.toString())
                .content(payload),
        )
            .andExpectUploadSuccess()
            .andExpect(jsonPath("$.data.uploadId").value(UPLOAD_ID))
            .andExpect(jsonPath("$.data.partNumber").value(1))
            .andExpect(jsonPath("$.data.eTag").doesNotExist())

        mvc.perform(post("$UPLOADS_PATH/$UPLOAD_ID/complete"))
            .andExpectUploadSuccess()
            .andExpect(jsonPath("$.data.fileObjectId").value("file-1"))
            .andExpect(jsonPath("$.data.fileAssetId").value("asset-1"))
            .andExpect(jsonPath("$.data.storagePath").doesNotExist())

        mvc.perform(delete("$UPLOADS_PATH/$UPLOAD_ID"))
            .andExpectUploadSuccess()
            .andExpect(jsonPath("$.data.status").value(ResumableUploadStatuses.ABORTED))

        assertEquals(listOf("start", "inspect", "uploadPart", "complete", "abort"), calls.map(UploadCall::method))
        assertEquals(listOf("upload-create-1"), calls[0].arguments[0])
        val startRequest = calls[0].arguments[1] as StartResumableUploadRequest
        assertEquals("证据.pdf", startRequest.fileName)
        assertEquals(6L, startRequest.contentLength)
        assertEquals("application/pdf", startRequest.contentType)
        assertEquals("sha256:${"a".repeat(64)}", startRequest.contentHash)
        assertEquals(1, calls[2].arguments[1])
        assertEquals(payload.size.toLong(), calls[2].arguments[2])
        assertContentEquals(payload, delegatedPartBytes)
        assertNotNull(delegatedPartContent)
        assertFailsWith<IOException> { delegatedPartContent!!.read() }
    }

    @Test
    fun `created location preserves host context and servlet prefixes and can be followed`() {
        val facade = Mockito.mock(ResumableUploadApiFacade::class.java, Answer<Any?> { invocation ->
            when (invocation.method.name) {
                "start", "inspect" -> uploading()
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val mvc = mockMvc(facade)
        val collection = "/host/gateway$UPLOADS_PATH"
        val location = mvc.perform(
            post(collection)
                .contextPath("/host")
                .servletPath("/gateway")
                .header(IDEMPOTENCY_KEY, "prefixed-create-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileName":"proof.pdf","contentLength":3}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.getHeader(HttpHeaders.LOCATION)

        assertEquals("$collection/$UPLOAD_ID", location)
        mvc.perform(
            get(requireNotNull(location))
                .contextPath("/host")
                .servletPath("/gateway"),
        ).andExpect(status().isOk)
    }

    @Test
    fun `rejects duplicate headers invalid path values and empty bodies with fixed 400 envelopes`() {
        val facade = validationFacade()
        val mvc = mockMvc(facade)
        val validJson = """{"fileName":"proof.pdf","contentLength":3}"""

        mvc.perform(
            post(UPLOADS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validJson),
        ).andExpectInvalidRequest()

        val duplicateKey = mvc.perform(
            post(UPLOADS_PATH)
                .header(IDEMPOTENCY_KEY, "private-key-one", "private-key-two")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validJson),
        ).andExpectInvalidRequest().andReturn().response.contentAsString

        mvc.perform(
            post(UPLOADS_PATH)
                .header(IDEMPOTENCY_KEY, "upload-empty-body")
                .contentType(MediaType.APPLICATION_JSON),
        ).andExpectInvalidRequest()

        val oversizedFileName = "private-name-" + "x".repeat(513)
        val oversizedBody = mvc.perform(
            post(UPLOADS_PATH)
                .header(IDEMPOTENCY_KEY, "upload-oversized-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileName":"$oversizedFileName","contentLength":1}"""),
        ).andExpectInvalidRequest().andReturn().response.contentAsString

        mvc.perform(
            post(UPLOADS_PATH)
                .header(IDEMPOTENCY_KEY, "upload-invalid-hash")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileName":"proof.pdf","contentLength":3,"contentHash":"sha256:abc"}"""),
        ).andExpectInvalidRequest()

        val invalidId = "x".repeat(129)
        mvc.perform(get("$UPLOADS_PATH/$invalidId"))
            .andExpectInvalidRequest()

        val duplicateLength = mvc.perform(
            put("$UPLOADS_PATH/$UPLOAD_ID/parts/1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(PART_LENGTH, "3", "private-length")
                .content(byteArrayOf(1, 2, 3)),
        ).andExpectInvalidRequest().andReturn().response.contentAsString

        mvc.perform(
            put("$UPLOADS_PATH/$UPLOAD_ID/parts/1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(byteArrayOf(1, 2, 3)),
        ).andExpectInvalidRequest()

        listOf("0", "+1", "01", "99999999999999999999").forEach { invalidLength ->
            mvc.perform(
                put("$UPLOADS_PATH/$UPLOAD_ID/parts/1")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(PART_LENGTH, invalidLength)
                    .content(byteArrayOf(1, 2, 3)),
            ).andExpectInvalidRequest()
        }

        listOf("not-a-number", "0", "+1", "01", "10001").forEach { invalidPartNumber ->
            mvc.perform(
                put("$UPLOADS_PATH/$UPLOAD_ID/parts/$invalidPartNumber")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(PART_LENGTH, "3")
                    .content(byteArrayOf(1, 2, 3)),
            ).andExpectInvalidRequest()
        }

        mvc.perform(
            put("$UPLOADS_PATH/$UPLOAD_ID/parts/1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(PART_LENGTH, "3"),
        ).andExpectInvalidRequest()

        assertFalse(duplicateKey.contains("private-key-one"))
        assertFalse(duplicateKey.contains("private-key-two"))
        assertFalse(duplicateLength.contains("private-length"))
        assertFalse(oversizedBody.contains("private-name"))
    }

    @Test
    fun `maps malformed JSON and field type errors to the fixed traced 400 envelope`() {
        val mvc = mockMvc(validationFacade())

        val malformed = mvc.perform(
            post(UPLOADS_PATH)
                .header(IDEMPOTENCY_KEY, "upload-malformed-json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileName":"private-name.pdf","contentLength":"""),
        ).andExpectInvalidRequest().andReturn().response.contentAsString

        val wrongType = mvc.perform(
            post(UPLOADS_PATH)
                .header(IDEMPOTENCY_KEY, "upload-wrong-type")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileName":"proof.pdf","contentLength":"private-length"}"""),
        ).andExpectInvalidRequest().andReturn().response.contentAsString

        assertFalse(malformed.contains("private-name"))
        assertFalse(wrongType.contains("private-length"))
    }

    @Test
    fun `maps a body longer than its declared part length to a fixed 400 envelope`() {
        val facade = Mockito.mock(ResumableUploadApiFacade::class.java, Answer<Any?> { invocation ->
            if (invocation.method.name == "uploadPart") {
                val declaredLength = invocation.arguments[2] as Long
                val content = invocation.arguments[3] as InputStream
                val bytes = content.readBytes()
                require(bytes.size.toLong() == declaredLength) {
                    "private request body length ${bytes.size} did not match $declaredLength"
                }
                ResumableUploadPartDto(UPLOAD_ID, 1, declaredLength, 102)
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })

        val body = mockMvc(facade)
            .perform(
                put("$UPLOADS_PATH/$UPLOAD_ID/parts/1")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(PART_LENGTH, "2")
                    .content("private-body"),
            )
            .andExpectInvalidRequest()
            .andReturn().response.contentAsString

        assertFalse(body.contains("private-body"))
        assertFalse(body.contains("did not match"))
    }

    @Test
    fun `maps 404 403 409 and 503 failures without exposing exception details`() {
        val facade = Mockito.mock(ResumableUploadApiFacade::class.java, Answer<Any?> { invocation ->
            when (invocation.method.name) {
                "inspect" -> throw NoSuchElementException("tenant-9/storage/private-not-found")
                "uploadPart" -> throw SecurityException("classified policy reason")
                "complete" -> throw ResumableUploadStateException("remote upload state and etag")
                "abort" -> throw ApplicationTransactionOutcomeUnknownException(
                    IllegalStateException("jdbc://private-db/password"),
                )
                "start" -> throw IllegalStateException("s3://private-bucket/internal-key")
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val mvc = mockMvc(facade)

        val notFound = mvc.perform(get("$UPLOADS_PATH/$UPLOAD_ID"))
            .andExpectFailure(404, "NOT_FOUND", "Resource was not found.")
            .andReturn().response.contentAsString
        val forbidden = mvc.perform(
            put("$UPLOADS_PATH/$UPLOAD_ID/parts/1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(PART_LENGTH, "1")
                .content(byteArrayOf(1)),
        ).andExpectFailure(403, "FORBIDDEN", "Access denied.")
            .andReturn().response.contentAsString
        val conflict = mvc.perform(post("$UPLOADS_PATH/$UPLOAD_ID/complete"))
            .andExpectFailure(409, "CONFLICT", "Request conflicts with the current resource state.")
            .andReturn().response.contentAsString
        val unavailable = mvc.perform(delete("$UPLOADS_PATH/$UPLOAD_ID"))
            .andExpectFailure(
                503,
                "OUTCOME_UNKNOWN",
                "Request outcome is unknown; inspect the resource state before retrying.",
            )
            .andReturn().response.contentAsString
        val internal = mvc.perform(
            post(UPLOADS_PATH)
                .header(IDEMPOTENCY_KEY, "upload-broken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileName":"proof.pdf","contentLength":1}"""),
        )
            .andExpectFailure(500, "INTERNAL_ERROR", "An unexpected error occurred.")
            .andReturn().response.contentAsString

        val combined = listOf(notFound, forbidden, conflict, unavailable, internal).joinToString()
        listOf(
            "tenant-9",
            "private-not-found",
            "classified",
            "etag",
            "private-db",
            "password",
            "private-bucket",
            "internal-key",
        ).forEach { secret ->
            assertFalse(combined.contains(secret), secret)
        }
    }

    @Test
    fun `public handlers expose no tenant owner storage or etag inputs`() {
        val publicHandlers = V1ResumableUploadController::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }

        assertEquals(
            setOf("start", "inspect", "uploadPart", "complete", "abort"),
            publicHandlers.map { method -> method.name }.toSet(),
        )
        assertTrue(publicHandlers.none { method ->
            method.parameters.any { parameter ->
                listOf("tenant", "owner", "user", "storage", "etag").any { forbidden ->
                    parameter.name.contains(forbidden, ignoreCase = true)
                }
            }
        })
    }

    @Test
    fun `requires the documented request content types`() {
        val mvc = mockMvc(Mockito.mock(ResumableUploadApiFacade::class.java))

        mvc.perform(
            post(UPLOADS_PATH)
                .header(IDEMPOTENCY_KEY, "upload-content-type")
                .contentType(MediaType.TEXT_PLAIN)
                .content("not-json"),
        ).andExpectFailure(
            415,
            "UNSUPPORTED_MEDIA_TYPE",
            "The request media type is not supported.",
        )

        mvc.perform(
            put("$UPLOADS_PATH/$UPLOAD_ID/parts/1")
                .header(PART_LENGTH, "2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        ).andExpectFailure(
            415,
            "UNSUPPORTED_MEDIA_TYPE",
            "The request media type is not supported.",
        )

        mvc.perform(get("$UPLOADS_PATH/$UPLOAD_ID").accept(MediaType.APPLICATION_XML))
            .andExpectFailure(
                406,
                "NOT_ACCEPTABLE",
                "The requested response representation is not acceptable.",
            )

        mvc.perform(patch("$UPLOADS_PATH/$UPLOAD_ID"))
            .andExpectFailure(405, "METHOD_NOT_ALLOWED", "Method is not allowed.")
    }

    @Test
    fun `request failure resolver is path scoped and leaves host routes unresolved`() {
        val handler = V1ResumableUploadRequestFailureHandler(RESPONSES, TRACE_CONTEXT_PROVIDER)
        val resolver = handler.uploadExceptionResolver()
        val failure = org.springframework.web.HttpRequestMethodNotSupportedException("PATCH", listOf("GET"))
        val hostResponse = MockHttpServletResponse()

        assertNull(
            resolver.resolveException(
                MockHttpServletRequest("PATCH", "/host/resource"),
                hostResponse,
                null,
                failure,
            ),
        )
        assertEquals(200, hostResponse.status)

        val uploadResponse = MockHttpServletResponse()
        assertNotNull(
            resolver.resolveException(
                MockHttpServletRequest("PATCH", "$UPLOADS_PATH/$UPLOAD_ID"),
                uploadResponse,
                null,
                failure,
            ),
        )
        assertEquals(405, uploadResponse.status)
        assertEquals("GET", uploadResponse.getHeader(HttpHeaders.ALLOW))

        val prefixedRequest = MockHttpServletRequest(
            "PATCH",
            "/host/gateway/fileweft/v1/uploads;version=1/$UPLOAD_ID",
        ).apply {
            contextPath = "/host"
            servletPath = "/gateway"
            pathInfo = "/fileweft/v1/uploads;version=1/$UPLOAD_ID"
        }
        val prefixedResponse = MockHttpServletResponse()
        assertNotNull(resolver.resolveException(prefixedRequest, prefixedResponse, null, failure))
        assertEquals(405, prefixedResponse.status)
    }

    @Test
    fun `fails safely instead of creating a half-routable resource`() {
        val facade = Mockito.mock(ResumableUploadApiFacade::class.java, Answer<Any?> { invocation ->
            if (invocation.method.name == "start") uploading("unsafe/id") else Mockito.RETURNS_DEFAULTS.answer(invocation)
        })

        val body = mockMvc(facade)
            .perform(
                post(UPLOADS_PATH)
                    .header(IDEMPOTENCY_KEY, "upload-unsafe-location")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"fileName":"proof.pdf","contentLength":3}"""),
            )
            .andExpect(status().isInternalServerError)
            .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andReturn().response.contentAsString

        assertFalse(body.contains("unsafe/id"))
    }

    private fun validationFacade(): ResumableUploadApiFacade = ResumableUploadApiFacade(
        uploads = Mockito.mock(ResumableUploadService::class.java),
    )

    private fun mockMvc(facade: ResumableUploadApiFacade): MockMvc =
        V1ResumableUploadRequestFailureHandler(RESPONSES, TRACE_CONTEXT_PROVIDER).let { failureHandler ->
            MockMvcBuilders
                .standaloneSetup(V1ResumableUploadController(facade, RESPONSES, TRACE_CONTEXT_PROVIDER))
                .setHandlerExceptionResolvers(failureHandler.uploadExceptionResolver())
                .setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper()))
                .build()
        }

    private fun ResultActions.andExpectUploadSuccess(): ResultActions =
        andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))

    private fun ResultActions.andExpectInvalidRequest(): ResultActions =
        andExpectFailure(400, "INVALID_REQUEST", "Request is invalid.")

    private fun ResultActions.andExpectFailure(statusCode: Int, code: String, message: String): ResultActions =
        andExpect(status().`is`(statusCode))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.message").value(message))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID))

    private fun uploading(
        uploadId: String = UPLOAD_ID,
        parts: List<ResumableUploadPartDto> = emptyList(),
    ): ResumableUploadDto = ResumableUploadDto(
        uploadId = uploadId,
        fileName = "证据.pdf",
        contentLength = 6,
        status = ResumableUploadStatuses.UPLOADING,
        expiresAt = 10_000,
        createdTime = 100,
        updatedTime = 102,
        uploadedParts = parts,
        contentType = "application/pdf",
        contentHash = "sha256:${"a".repeat(64)}",
    )

    private fun aborted(): ResumableUploadDto = ResumableUploadDto(
        uploadId = UPLOAD_ID,
        fileName = "证据.pdf",
        contentLength = 6,
        status = ResumableUploadStatuses.ABORTED,
        expiresAt = 10_000,
        createdTime = 100,
        updatedTime = 103,
        uploadedParts = emptyList(),
        contentType = "application/pdf",
        contentHash = "sha256:${"a".repeat(64)}",
    )

    private fun completion(): ResumableUploadCompletionDto = ResumableUploadCompletionDto(
        uploadId = UPLOAD_ID,
        fileObjectId = "file-1",
        fileAssetId = "asset-1",
        completedAt = 104,
    )

    private data class UploadCall(
        val method: String,
        val arguments: List<Any?>,
    )

    private companion object {
        const val UPLOADS_PATH: String = "/fileweft/v1/uploads"
        const val UPLOAD_ID: String = "upload-1"
        const val IDEMPOTENCY_KEY: String = "Idempotency-Key"
        const val PART_LENGTH: String = "X-FileWeft-Part-Length"
        const val TRACE_ID: String = "trace-upload-3"

        val RESPONSES: V1ApiResponseFactory = V1ApiResponseFactory()
        val TRACE_CONTEXT_PROVIDER: TraceContextProvider = object : TraceContextProvider {
            override fun currentTraceContext(): TraceContext = TraceContext(Identifier(TRACE_ID))
        }
    }
}
