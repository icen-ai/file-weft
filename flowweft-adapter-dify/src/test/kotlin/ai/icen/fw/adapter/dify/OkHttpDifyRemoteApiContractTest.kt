package ai.icen.fw.adapter.dify

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.time.Duration
import java.util.ArrayDeque

class OkHttpDifyRemoteApiContractTest {
    @Test
    fun `uses the documented create route and canonical 1_14 PATCH update fields`() {
        val transport = RecordingTransport(
            StubResponse(200, writeBody(TEST_DOCUMENT_ID, TEST_BATCH_ID)),
            StubResponse(200, writeBody(TEST_DOCUMENT_ID, "batch_2")),
        )
        val api = remote(transport)

        downloadedSource().use { source ->
            assertEquals(
                DifyWriteDisposition.ACCEPTED,
                api.create(source, "hello.txt", "text/plain", deadline()).disposition,
            )
        }
        downloadedSource().use { source ->
            assertEquals(
                DifyWriteDisposition.ACCEPTED,
                api.update(TEST_DOCUMENT_ID, source, "hello.txt", "text/plain", deadline()).disposition,
            )
        }

        assertEquals("POST", transport.requests[0].method)
        assertEquals(
            "/v1/datasets/$TEST_DATASET_ID/document/create-by-file",
            transport.requests[0].url.encodedPath,
        )
        assertEquals("PATCH", transport.requests[1].method)
        assertEquals(
            "/v1/datasets/$TEST_DATASET_ID/documents/$TEST_DOCUMENT_ID",
            transport.requests[1].url.encodedPath,
        )
        val updateBody = transport.requestBodies[1]
        assertTrue(updateBody.contains("\"indexing_technique\":\"high_quality\""))
        assertTrue(updateBody.contains("\"doc_form\":\"text_model\""))
        assertTrue(updateBody.contains("\"doc_language\":\"English\""))
        assertTrue(updateBody.contains("\"process_rule\""))
        transport.requests.forEach { request ->
            assertEquals("Bearer test-api-key", request.header("Authorization"))
            assertTrue(request.body?.contentType().toString().startsWith("multipart/form-data"))
            assertTrue(requireNotNull(request.body).isOneShot())
        }
    }

    @Test
    fun `uses exact delete indexing and document status routes`() {
        val transport = RecordingTransport(
            StubResponse(204, ""),
            StubResponse(
                200,
                """{"data":[{"id":"$TEST_DOCUMENT_ID","indexing_status":"completed"}]}""",
            ),
            StubResponse(
                200,
                """{"id":"$TEST_DOCUMENT_ID","indexing_status":"completed","enabled":true,"archived":false}""",
            ),
        )
        val api = remote(transport)

        assertEquals(DifyDeleteDisposition.ACCEPTED, api.delete(TEST_DOCUMENT_ID, deadline()))
        assertEquals(
            DifyProjectionIndexState.AVAILABLE,
            api.indexingStatus(TEST_BATCH_ID, TEST_DOCUMENT_ID, deadline()).state,
        )
        assertEquals(
            DifyProjectionIndexState.AVAILABLE,
            api.documentStatus(TEST_DOCUMENT_ID, deadline()).state,
        )

        assertEquals("DELETE", transport.requests[0].method)
        assertEquals("/v1/datasets/$TEST_DATASET_ID/documents/$TEST_DOCUMENT_ID", transport.requests[0].url.encodedPath)
        assertEquals("GET", transport.requests[1].method)
        assertEquals(
            "/v1/datasets/$TEST_DATASET_ID/documents/$TEST_BATCH_ID/indexing-status",
            transport.requests[1].url.encodedPath,
        )
        assertEquals("GET", transport.requests[2].method)
        assertEquals("metadata=without", transport.requests[2].url.encodedQuery)
    }

    @Test
    fun `never retries ambiguous writes but retries bounded status reads`() {
        val writeTransport = RecordingTransport(StubResponse(500, "provider failure secret=should-not-escape"))
        val writeApi = remote(writeTransport)

        val write = downloadedSource().use { source ->
            writeApi.create(source, "hello.txt", "text/plain", deadline())
        }

        assertEquals(DifyWriteDisposition.OUTCOME_UNKNOWN, write.disposition)
        assertEquals(1, writeTransport.requests.size)

        val readTransport = RecordingTransport(
            StubResponse(429, "rate limited"),
            StubResponse(500, "temporary"),
            StubResponse(
                200,
                """{"id":"$TEST_DOCUMENT_ID","indexing_status":"completed","enabled":true,"archived":false}""",
            ),
        )
        val readApi = remote(readTransport)

        val status = readApi.documentStatus(TEST_DOCUMENT_ID, deadline())

        assertEquals(DifyReadDisposition.SUCCESS, status.disposition)
        assertEquals(3, readTransport.requests.size)
    }

    @Test
    fun `validates accepted update identity and treats mismatch as ambiguous`() {
        val unexpected = "33333333-3333-3333-3333-333333333333"
        val transport = RecordingTransport(StubResponse(200, writeBody(unexpected, TEST_BATCH_ID)))
        val api = remote(transport)

        val result = downloadedSource().use { source ->
            api.update(TEST_DOCUMENT_ID, source, "hello.txt", "text/plain", deadline())
        }

        assertEquals(DifyWriteDisposition.OUTCOME_UNKNOWN, result.disposition)
    }

    @Test
    fun `every issued write rejection network failure and malformed success is outcome unknown`() {
        listOf(408, 425, 429, 500, 503).forEach { status ->
            val result = downloadedSource().use { source ->
                remote(RecordingTransport(StubResponse(status, "rejected")))
                    .create(source, "hello.txt", "text/plain", deadline())
            }
            assertEquals(DifyWriteDisposition.OUTCOME_UNKNOWN, result.disposition)
        }

        val malformed = downloadedSource().use { source ->
            remote(RecordingTransport(StubResponse(200, "{}")))
                .create(source, "hello.txt", "text/plain", deadline())
        }
        assertEquals(DifyWriteDisposition.OUTCOME_UNKNOWN, malformed.disposition)

        val duplicateIdentity = downloadedSource().use { source ->
            remote(
                RecordingTransport(
                    StubResponse(
                        200,
                        """{"document":{"id":"$TEST_DOCUMENT_ID","id":"33333333-3333-3333-3333-333333333333"},"batch":"$TEST_BATCH_ID"}""",
                    ),
                ),
            ).create(source, "hello.txt", "text/plain", deadline())
        }
        assertEquals(DifyWriteDisposition.OUTCOME_UNKNOWN, duplicateIdentity.disposition)

        val network = downloadedSource().use { source ->
            remote(ThrowingTransport()).create(source, "hello.txt", "text/plain", deadline())
        }
        assertEquals(DifyWriteDisposition.OUTCOME_UNKNOWN, network.disposition)
    }

    @Test
    fun `credential failure before execution is explicitly not sent`() {
        val transport = RecordingTransport(StubResponse(200, writeBody(TEST_DOCUMENT_ID, TEST_BATCH_ID)))
        val result = downloadedSource().use { source ->
            remote(
                transport,
                object : DifyApiKeyProvider {
                    override fun loadApiKey(): CharArray = throw IllegalStateException("unavailable")
                },
            ).create(source, "hello.txt", "text/plain", deadline())
        }

        assertEquals(DifyWriteDisposition.NOT_SENT, result.disposition)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `hardens an injected client against redirects proxies and unchecked DNS`() {
        val injectedProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8888))
        val hardened = hardenedDifyApiClient(
            testProfile(),
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .proxy(injectedProxy)
                .build(),
        )

        assertFalse(hardened.followRedirects)
        assertFalse(hardened.followSslRedirects)
        assertFalse(hardened.retryOnConnectionFailure)
        assertEquals(Proxy.NO_PROXY, hardened.proxy)
        assertTrue(hardened.dns is DifyValidatingDns)
    }

    private fun remote(
        transport: Interceptor,
        apiKeyProvider: DifyApiKeyProvider = StaticDifyApiKeyProvider("test-api-key"),
    ): OkHttpDifyRemoteApi = OkHttpDifyRemoteApi(
        profile = testProfile(),
        apiKeyProvider = apiKeyProvider,
        sleeper = object : DifySleeper {
            override fun sleep(millis: Long) = Unit
        },
        client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(transport)
            .build(),
    )

    private fun downloadedSource(): DifyDownloadedSource {
        val path = Files.createTempFile("flowweft-dify-http-test-", ".txt")
        Files.write(path, "hello".toByteArray(Charsets.UTF_8))
        return DifyDownloadedSource(path, 5L, TEST_SOURCE_HASH)
    }

    private fun deadline(): DifyDeadline = DifyDeadline.start(Duration.ofSeconds(2))

    private fun writeBody(documentId: String, batch: String): String =
        """{"document":{"id":"$documentId"},"batch":"$batch"}"""

    private data class StubResponse(val code: Int, val body: String)

    private class RecordingTransport(vararg responses: StubResponse) : Interceptor {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<Request>()
        val requestBodies = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            requestBodies += Buffer().also { sink -> request.body?.writeTo(sink) }.readUtf8()
            val response = checkNotNull(responses.pollFirst()) { "No stub response remains." }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(response.code)
                .message("stub")
                .body(response.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private class ThrowingTransport : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = throw IOException("simulated network failure")
    }
}
