package ai.icen.fw.web.spring.boot2

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
import ai.icen.fw.web.api.v1.upload.StartResumableUploadCommand
import ai.icen.fw.web.api.v1.upload.StartResumableUploadRequest
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.upload.ResumableUploadApiFacade
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V1ResumableUploadControllerMockMvcTest {
    @Test
    fun `exposes all five routes as safe JSON envelopes and keeps the part body streaming`() {
        val fixture = UploadControllerFixture()
        val mockMvc = mvc(fixture, "trace-upload-1")

        mockMvc.perform(
            post(UPLOADS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY, "caller-start-key")
                .content(START_JSON),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "$UPLOADS_PATH/upload-1"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value("trace-upload-1"))
            .andExpect(jsonPath("$.data.uploadId").value("upload-1"))
            .andExpect(jsonPath("$.data.status").value(ResumableUploadStatuses.UPLOADING))
            .andExpect(jsonPath("$.data.uploadedParts").isArray)
            .andExpect(content().string(not(containsString("caller-start-key"))))
            .andExpect(content().string(not(containsString("ownerId"))))
            .andExpect(content().string(not(containsString("tenantId"))))
            .andExpect(content().string(not(containsString("storageUploadId"))))
            .andExpect(content().string(not(containsString("storageLocation"))))
            .andExpect(content().string(not(containsString("eTag"))))
            .andExpect(content().string(not(containsString("lastError"))))

        mockMvc.perform(get("$UPLOADS_PATH/upload-1"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.traceId").value("trace-upload-1"))
            .andExpect(jsonPath("$.data.uploadedParts[0].partNumber").value(1))
            .andExpect(jsonPath("$.data.uploadedParts[0].contentLength").value(4))

        mockMvc.perform(
            put("$UPLOADS_PATH/upload-1/parts/2")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(PART_LENGTH, "4")
                .content(byteArrayOf(0x00, 0x01, 0x7f, 0xff.toByte())),
        )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.data.uploadId").value("upload-1"))
            .andExpect(jsonPath("$.data.partNumber").value(2))
            .andExpect(jsonPath("$.data.contentLength").value(4))

        mockMvc.perform(post("$UPLOADS_PATH/upload-1/complete"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.data.uploadId").value("upload-1"))
            .andExpect(jsonPath("$.data.fileObjectId").value("file-object-1"))
            .andExpect(jsonPath("$.data.fileAssetId").value("file-asset-1"))

        mockMvc.perform(delete("$UPLOADS_PATH/upload-1"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.data.uploadId").value("upload-1"))
            .andExpect(jsonPath("$.data.status").value(ResumableUploadStatuses.ABORTED))

        assertEquals(listOf("caller-start-key"), fixture.startIdempotencyKeys)
        assertEquals("report.pdf", fixture.startRequest?.fileName)
        assertEquals(8L, fixture.startRequest?.contentLength)
        assertEquals("application/pdf", fixture.startRequest?.contentType)
        assertEquals("sha256:${"a".repeat(64)}", fixture.startRequest?.contentHash)
        assertContentEquals(byteArrayOf(0x00, 0x01, 0x7f, 0xff.toByte()), fixture.partBody)
        assertNotNull(fixture.partContent)
        assertFailsWith<IOException> { fixture.partContent!!.read() }
    }

    @Test
    fun `created location preserves host context and servlet prefixes and can be followed`() {
        val mockMvc = mvc(UploadControllerFixture())
        val collection = "/host/gateway$UPLOADS_PATH"
        val location = mockMvc.perform(
            post(collection)
                .contextPath("/host")
                .servletPath("/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY, "prefixed-create-key")
                .content(START_JSON),
        )
            .andExpect(status().isCreated)
            .andReturn().response.getHeader(HttpHeaders.LOCATION)

        assertEquals("$collection/upload-1", location)
        mockMvc.perform(
            get(requireNotNull(location))
                .contextPath("/host")
                .servletPath("/gateway"),
        ).andExpect(status().isOk)
    }

    @Test
    fun `rejects missing and repeated transport headers inside the fixed 400 envelope`() {
        val application = Mockito.mock(ResumableUploadService::class.java)
        val facade = ResumableUploadApiFacade(application)
        val mockMvc = mvc(facade)

        mockMvc.perform(
            post(UPLOADS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(START_JSON),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        val duplicateKeyBody = mockMvc.perform(
            post(UPLOADS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY, "private-first-key", "private-second-key")
                .content(START_JSON),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andReturn().response.contentAsString

        mockMvc.perform(
            post(UPLOADS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY, "caller-start-key"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        mockMvc.perform(
            post(UPLOADS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY, "caller-start-key")
                .content("""{"fileName":"${"x".repeat(513)}","contentLength":1}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        mockMvc.perform(
            post(UPLOADS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY, "invalid-content-hash")
                .content("""{"fileName":"report.pdf","contentLength":1,"contentHash":"sha256:abc"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        mockMvc.perform(
            put("$UPLOADS_PATH/upload-1/parts/1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(byteArrayOf(1)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        val duplicateLengthBody = mockMvc.perform(
            put("$UPLOADS_PATH/upload-1/parts/1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(PART_LENGTH, "1", "private-length")
                .content(byteArrayOf(1)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andReturn().response.contentAsString

        listOf("0", "+1", "01", "9223372036854775808").forEach { invalidLength ->
            mockMvc.perform(
                put("$UPLOADS_PATH/upload-1/parts/1")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(PART_LENGTH, invalidLength)
                    .content(byteArrayOf(1)),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        listOf("not-a-number", "0", "+1", "01", "10001").forEach { invalidPartNumber ->
            mockMvc.perform(
                put("$UPLOADS_PATH/upload-1/parts/$invalidPartNumber")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(PART_LENGTH, "1")
                    .content(byteArrayOf(1)),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        mockMvc.perform(get("$UPLOADS_PATH/${"x".repeat(129)}"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        mockMvc.perform(
            put("$UPLOADS_PATH/upload-1/parts/1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(PART_LENGTH, "1"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        Mockito.verifyNoInteractions(application)
        assertFalse(duplicateKeyBody.contains("private-first-key"))
        assertFalse(duplicateKeyBody.contains("private-second-key"))
        assertFalse(duplicateLengthBody.contains("private-length"))
    }

    @Test
    fun `rejects a part body longer than its declared length through the safe envelope`() {
        val fixture = UploadControllerFixture()

        mvc(fixture).perform(
            put("$UPLOADS_PATH/upload-1/parts/1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(PART_LENGTH, "1")
                .content(byteArrayOf(1, 2)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Request is invalid."))
            .andExpect(content().string(not(containsString("declared=1"))))

        assertEquals(1, fixture.partCalls)
        assertContentEquals(byteArrayOf(1, 2), fixture.partBody)
    }

    @Test
    fun `maps malformed JSON and field type errors to the fixed traced 400 envelope`() {
        val mockMvc = mvc(
            ResumableUploadApiFacade(
                uploads = Mockito.mock(ResumableUploadService::class.java),
            ),
            "trace-json",
        )

        val malformed = mockMvc.perform(
            post(UPLOADS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY, "malformed-json")
                .content("""{"fileName":"private-name.pdf","contentLength":"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Request is invalid."))
            .andExpect(jsonPath("$.traceId").value("trace-json"))
            .andReturn().response.contentAsString

        val wrongType = mockMvc.perform(
            post(UPLOADS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY, "wrong-type")
                .content("""{"fileName":"report.pdf","contentLength":"private-length"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andReturn().response.contentAsString

        assertFalse(malformed.contains("private-name"))
        assertFalse(wrongType.contains("private-length"))
    }

    @Test
    fun `maps upload failures to 404 403 409 and 503 without leaking internal details`() {
        val mockMvc = mvc(UploadControllerFixture(), "trace-safe")

        mockMvc.perform(get("$UPLOADS_PATH/missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource was not found."))
            .andExpect(jsonPath("$.traceId").value("trace-safe"))
            .andExpect(content().string(not(containsString("tenant-secret"))))

        mockMvc.perform(get("$UPLOADS_PATH/forbidden"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("Access denied."))
            .andExpect(content().string(not(containsString("host-policy"))))

        mockMvc.perform(get("$UPLOADS_PATH/conflict"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
            .andExpect(jsonPath("$.message").value("Request conflicts with the current resource state."))
            .andExpect(content().string(not(containsString("storage-etag"))))

        mockMvc.perform(get("$UPLOADS_PATH/outcome-unknown"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("OUTCOME_UNKNOWN"))
            .andExpect(jsonPath("$.message").value("Request outcome is unknown; inspect the resource state before retrying."))
            .andExpect(content().string(not(containsString("jdbc:postgresql"))))
            .andExpect(content().string(not(containsString("password=secret"))))

        mockMvc.perform(get("$UPLOADS_PATH/broken"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
            .andExpect(content().string(not(containsString("s3://private-bucket"))))
    }

    @Test
    fun `does not add tenant owner storage or identity parameters to the public handlers`() {
        val handlerMethods = V1ResumableUploadController::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }

        assertEquals(setOf("start", "inspect", "uploadPart", "complete", "abort"), handlerMethods.map { it.name }.toSet())
        assertTrue(handlerMethods.none { method ->
            method.parameters.any { parameter ->
                listOf("tenant", "owner", "user", "storage", "etag").any { forbidden ->
                    parameter.name.contains(forbidden, ignoreCase = true)
                }
            }
        })
    }

    @Test
    fun `requires JSON for start and octet stream for parts`() {
        val mockMvc = mvc(UploadControllerFixture())

        mockMvc.perform(
            post(UPLOADS_PATH)
                .header(IDEMPOTENCY_KEY, "upload-content-type")
                .contentType(MediaType.TEXT_PLAIN)
                .content("not-json"),
        ).andExpectTransportFailure(
            415,
            "UNSUPPORTED_MEDIA_TYPE",
            "The request media type is not supported.",
        )

        mockMvc.perform(
            put("$UPLOADS_PATH/upload-1/parts/1")
                .header(PART_LENGTH, "2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        ).andExpectTransportFailure(
            415,
            "UNSUPPORTED_MEDIA_TYPE",
            "The request media type is not supported.",
        )

        mockMvc.perform(get("$UPLOADS_PATH/upload-1").accept(MediaType.APPLICATION_XML))
            .andExpectTransportFailure(
                406,
                "NOT_ACCEPTABLE",
                "The requested response representation is not acceptable.",
            )

        mockMvc.perform(patch("$UPLOADS_PATH/upload-1"))
            .andExpectTransportFailure(405, "METHOD_NOT_ALLOWED", "Method is not allowed.")
    }

    @Test
    fun `request failure resolver is path scoped and leaves host routes unresolved`() {
        val handler = V1ResumableUploadRequestFailureHandler(V1ApiResponseFactory())
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
                MockHttpServletRequest("PATCH", "$UPLOADS_PATH/upload-1"),
                uploadResponse,
                null,
                failure,
            ),
        )
        assertEquals(405, uploadResponse.status)
        assertEquals("GET", uploadResponse.getHeader(HttpHeaders.ALLOW))

        val prefixedRequest = MockHttpServletRequest(
            "PATCH",
            "/host/gateway/fileweft/v1/uploads;version=1/upload-1",
        ).apply {
            contextPath = "/host"
            servletPath = "/gateway"
            pathInfo = "/fileweft/v1/uploads;version=1/upload-1"
        }
        val prefixedResponse = MockHttpServletResponse()
        assertNotNull(resolver.resolveException(prefixedRequest, prefixedResponse, null, failure))
        assertEquals(405, prefixedResponse.status)
    }

    @Test
    fun `fails safely instead of creating a half-routable resource`() {
        mvc(UploadControllerFixture(startedUploadId = "unsafe/id"))
            .perform(
                post(UPLOADS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY, "unsafe-location-key")
                    .content(START_JSON),
            )
            .andExpect(status().isInternalServerError)
            .andExpect(header().doesNotExist("Location"))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(not(containsString("unsafe/id"))))
    }

    private fun mvc(fixture: UploadControllerFixture, traceId: String? = null): MockMvc =
        mvc(fixture.facade, traceId)

    private fun mvc(facade: ResumableUploadApiFacade, traceId: String? = null): MockMvc =
        V1ResumableUploadRequestFailureHandler(
            responses = V1ApiResponseFactory(),
            traceContextProvider = traceId?.let(::traceProvider),
        ).let { failureHandler -> MockMvcBuilders.standaloneSetup(
            V1ResumableUploadController(
                uploads = facade,
                responses = V1ApiResponseFactory(),
                traceContextProvider = traceId?.let(::traceProvider),
            ),
        )
            .setHandlerExceptionResolvers(failureHandler.uploadExceptionResolver())
            // A plain mapper proves the request is a Java-friendly mutable bean.
            .setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper()))
            .build() }

    private fun ResultActions.andExpectTransportFailure(
        statusCode: Int,
        code: String,
        message: String,
    ): ResultActions = andExpect(status().`is`(statusCode))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
        .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.message").value(message))

    private fun traceProvider(traceId: String): TraceContextProvider = object : TraceContextProvider {
        override fun currentTraceContext(): TraceContext = TraceContext(Identifier(traceId))
    }

    private companion object {
        const val UPLOADS_PATH: String = "/fileweft/v1/uploads"
        const val IDEMPOTENCY_KEY: String = "Idempotency-Key"
        const val PART_LENGTH: String = "X-FileWeft-Part-Length"
        val START_JSON: String =
            """{"fileName":"report.pdf","contentLength":8,"contentType":"application/pdf","contentHash":"sha256:${"a".repeat(64)}"}"""
    }
}

private class UploadControllerFixture(
    private val startedUploadId: String = "upload-1",
) {
    var startIdempotencyKeys: List<String>? = null
    var startRequest: StartResumableUploadRequest? = null
    var partBody: ByteArray = byteArrayOf()
    var partContent: InputStream? = null
    var partCalls: Int = 0

    val facade: ResumableUploadApiFacade = Mockito.mock(
        ResumableUploadApiFacade::class.java,
        Answer { invocation ->
            when (invocation.method.name) {
                "start" -> {
                    @Suppress("UNCHECKED_CAST")
                    val keys = invocation.arguments[0] as List<String>?
                    require(keys?.size == 1) { "Idempotency-Key must be supplied exactly once." }
                    startIdempotencyKeys = keys.toList()
                    startRequest = invocation.arguments[1] as StartResumableUploadRequest
                    StartResumableUploadCommand(
                        fileName = requireNotNull(startRequest?.fileName) { "Upload file name is required." },
                        contentLength = requireNotNull(startRequest?.contentLength) { "Upload content length is required." },
                        contentType = startRequest?.contentType,
                        contentHash = startRequest?.contentHash,
                    )
                    uploading(startedUploadId)
                }
                "inspect" -> inspect(invocation.arguments[0] as String)
                "uploadPart" -> {
                    partCalls += 1
                    val uploadId = invocation.arguments[0] as String
                    val partNumber = invocation.arguments[1] as Int
                    val contentLength = invocation.arguments[2] as Long
                    partContent = invocation.arguments[3] as InputStream
                    partBody = partContent!!.readBytes()
                    require(partBody.size.toLong() == contentLength) {
                        "Multipart part body length does not match its declared length."
                    }
                    ResumableUploadPartDto(uploadId, partNumber, contentLength, 30)
                }
                "complete" -> completion(invocation.arguments[0] as String)
                "abort" -> aborted(invocation.arguments[0] as String)
                else -> throw AssertionError("Unexpected facade method ${invocation.method.name}")
            }
        },
    )

    private fun inspect(uploadId: String): ResumableUploadDto = when (uploadId) {
        "missing" -> throw NoSuchElementException("tenant-secret upload row is absent")
        "forbidden" -> throw SecurityException("host-policy=restricted")
        "conflict" -> throw ResumableUploadStateException("storage-etag conflicted")
        "outcome-unknown" -> throw ApplicationTransactionOutcomeUnknownException(
            IllegalStateException("jdbc:postgresql://db/password=secret"),
        )
        "broken" -> throw RuntimeException("s3://private-bucket/tenant-secret")
        else -> uploading(uploadId, listOf(ResumableUploadPartDto(uploadId, 1, 4, 20)))
    }

    private fun uploading(
        uploadId: String = "upload-1",
        parts: List<ResumableUploadPartDto> = emptyList(),
    ): ResumableUploadDto = ResumableUploadDto(
        uploadId = uploadId,
        fileName = "report.pdf",
        contentLength = 8,
        status = ResumableUploadStatuses.UPLOADING,
        expiresAt = 10_000,
        createdTime = 10,
        updatedTime = 20,
        uploadedParts = parts,
        contentType = "application/pdf",
        contentHash = "sha256:${"a".repeat(64)}",
    )

    private fun completion(uploadId: String): ResumableUploadCompletionDto =
        ResumableUploadCompletionDto(uploadId, "file-object-1", "file-asset-1", 40)

    private fun aborted(uploadId: String): ResumableUploadDto = ResumableUploadDto(
        uploadId = uploadId,
        fileName = "report.pdf",
        contentLength = 8,
        status = ResumableUploadStatuses.ABORTED,
        expiresAt = 10_000,
        createdTime = 10,
        updatedTime = 40,
        uploadedParts = emptyList(),
        contentType = "application/pdf",
        contentHash = "sha256:${"a".repeat(64)}",
    )
}
