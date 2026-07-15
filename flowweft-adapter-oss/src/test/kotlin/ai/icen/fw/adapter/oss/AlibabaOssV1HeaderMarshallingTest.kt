package ai.icen.fw.adapter.oss

import com.aliyun.oss.ClientConfiguration
import com.aliyun.oss.OSSClientBuilder
import com.aliyun.oss.common.comm.Protocol
import com.aliyun.oss.common.comm.SignVersion
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Wire-level regressions for headers not copied by the operation-specific
 * OSS SDK V1 marshallers.
 *
 * The SDK's generic operation layer merges WebServiceRequest headers before
 * V4 signing. These tests exercise the real pinned SDK and inspect the HTTP
 * request at a loopback OSS fixture instead of only inspecting request model
 * objects. Production endpoints remain HTTPS-only; HTTP is confined to this
 * loopback marshalling fixture.
 */
class AlibabaOssV1HeaderMarshallingTest {
    private lateinit var server: HttpServer
    private lateinit var client: AlibabaOssV1Client
    private val rangeBehavior = AtomicReference<String?>()
    private val forbidOverwrite = AtomicReference<String?>()
    private val rangeAuthorization = AtomicReference<String?>()
    private val completionAuthorization = AtomicReference<String?>()
    private val rangedGetVersionId = AtomicReference<String?>()
    private val headVersionId = AtomicReference<String?>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            start()
        }
        val sdkConfiguration = ClientConfiguration().apply {
            setProtocol(Protocol.HTTP)
            setSignatureVersion(SignVersion.V4)
            setSLDEnabled(true)
            setConnectionTimeout(2_000)
            setConnectionRequestTimeout(2_000)
            setSocketTimeout(2_000)
            setRequestTimeout(2_000)
            setRequestTimeoutEnabled(true)
            setMaxErrorRetry(0)
            setCrcCheckEnabled(false)
            setVerifyObjectStrictEnable(false)
            setRedirectEnable(false)
            setExtractSettingFromEndpoint(false)
        }
        val credentials = StaticOssCredentialsProvider("wire-access-key", "wire-access-secret")
        val sdkCredentials = FlowWeftCredentialsProvider(credentials)
        val sdkClient = OSSClientBuilder.create()
            .endpoint("http://127.0.0.1:${server.address.port}")
            .credentialsProvider(sdkCredentials)
            .clientConfiguration(sdkConfiguration)
            .region(TEST_REGION)
            .build()
        client = AlibabaOssV1Client(
            OssStorageConfiguration(
                URI.create("https://oss-cn-hangzhou.aliyuncs.com"),
                TEST_REGION,
                TEST_BUCKET,
                credentials,
            ),
            sdkCredentials,
            sdkClient,
        )
    }

    @AfterEach
    fun tearDown() {
        if (::client.isInitialized) client.close()
        if (::server.isInitialized) server.stop(0)
    }

    @Test
    fun `PUT captures immutable response version and overwrite guard`() {
        val content = "put".toByteArray()

        val result = client.putObject(
            "put-object",
            content.size.toLong(),
            "text/plain",
            emptyMap(),
            ByteArrayInputStream(content),
        )

        assertEquals("put-version", result.versionId)
        assertEquals("put-etag", result.eTag)
        assertEquals("true", forbidOverwrite.get())
    }

    @Test
    fun `ranged GET sends standard range behavior on the signed wire request`() {
        client.getObject(
            "range-object",
            0,
            0,
            OssRevisionCondition(OssRevisionKind.VERSION_ID, "version-1"),
        ).use { response ->
            response.body.use { content -> assertEquals('x'.code, content.read()) }
        }

        assertEquals("standard", rangeBehavior.get())
        assertEquals("version-1", rangedGetVersionId.get())
        assertTrue(rangeAuthorization.get()?.startsWith("OSS4-HMAC-SHA256 ") == true)
    }

    @Test
    fun `HEAD and presigned GET marshal an exact safely encoded version id`() {
        val metadata = client.headObject("head-object", "version-2")
        assertEquals("version-2", metadata.versionId)
        assertEquals("version-2", headVersionId.get())

        val exactVersion = "version+/=opaque"
        val uri = client.presignGetObject(
            "presigned-object",
            exactVersion,
            System.currentTimeMillis() + 60_000,
            OssCredentials("wire-access-key", "wire-access-secret"),
        )
        val rawVersion = requireNotNull(
            uri.rawQuery.split('&')
                .single { parameter -> parameter.substringBefore('=').equals("versionId", ignoreCase = true) }
                .substringAfter('='),
        )
        assertEquals(exactVersion, URLDecoder.decode(rawVersion, StandardCharsets.UTF_8.name()))
    }

    @Test
    fun `multipart completion sends forbid overwrite on the signed wire request`() {
        val result = client.completeMultipartUpload(
            "multipart-object",
            "upload-id",
            listOf(OssCompletedPart(1, "part-etag")),
        )

        assertEquals("complete-version", result.versionId)
        assertEquals("complete-etag", result.eTag)
        assertEquals("true", forbidOverwrite.get())
        assertTrue(completionAuthorization.get()?.startsWith("OSS4-HMAC-SHA256 ") == true)
    }

    private fun handle(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod) {
                "GET" -> handleGet(exchange)
                "HEAD" -> handleHead(exchange)
                "PUT" -> handlePut(exchange)
                "POST" -> handleComplete(exchange)
                else -> respond(exchange, 405, "")
            }
        } finally {
            exchange.close()
        }
    }

    private fun handleGet(exchange: HttpExchange) {
        rangeBehavior.set(exchange.requestHeaders.getFirst(RANGE_BEHAVIOR_HEADER))
        rangeAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
        rangedGetVersionId.set(queryParameter(exchange.requestURI, "versionId"))
        exchange.responseHeaders.apply {
            set("Content-Type", "application/octet-stream")
            set("Content-Range", "bytes 0-0/1")
            set("ETag", "\"range-etag\"")
            set("x-oss-request-id", "wire-range-request")
            set("x-oss-version-id", "version-1")
        }
        respond(exchange, 206, "x")
    }

    private fun handleHead(exchange: HttpExchange) {
        headVersionId.set(queryParameter(exchange.requestURI, "versionId"))
        exchange.responseHeaders.apply {
            set("Content-Length", "1")
            set("Content-Type", "text/plain")
            set("Content-MD5", "CY9rzUYh03PK3k6DJie09g==")
            set("ETag", "\"head-etag\"")
            set("Last-Modified", "Wed, 15 Jul 2026 00:00:00 GMT")
            set("x-oss-hash-crc64ecma", "1")
            set("x-oss-request-id", "wire-head-request")
            set("x-oss-version-id", "version-2")
        }
        exchange.sendResponseHeaders(200, -1)
    }

    private fun handlePut(exchange: HttpExchange) {
        exchange.requestBody.use { it.readBytes() }
        forbidOverwrite.set(exchange.requestHeaders.getFirst(FORBID_OVERWRITE_HEADER))
        exchange.responseHeaders.apply {
            set("ETag", "\"put-etag\"")
            set("x-oss-request-id", "wire-put-request")
            set("x-oss-version-id", "put-version")
        }
        respond(exchange, 200, "")
    }

    private fun handleComplete(exchange: HttpExchange) {
        exchange.requestBody.use { it.readBytes() }
        forbidOverwrite.set(exchange.requestHeaders.getFirst(FORBID_OVERWRITE_HEADER))
        completionAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
        val endpoint = "http://127.0.0.1:${server.address.port}"
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <CompleteMultipartUploadResult>
              <Location>$endpoint/$TEST_BUCKET/multipart-object</Location>
              <Bucket>$TEST_BUCKET</Bucket>
              <Key>multipart-object</Key>
              <ETag>&quot;complete-etag&quot;</ETag>
            </CompleteMultipartUploadResult>
        """.trimIndent()
        exchange.responseHeaders.apply {
            set("Content-Type", "application/xml")
            set("ETag", "\"complete-etag\"")
            set("x-oss-request-id", "wire-complete-request")
            set("x-oss-version-id", "complete-version")
        }
        respond(exchange, 200, body)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { response -> response.write(bytes) }
    }

    private fun queryParameter(uri: URI, name: String): String? = uri.rawQuery
        ?.split('&')
        ?.firstOrNull { parameter -> parameter.substringBefore('=').equals(name, ignoreCase = true) }
        ?.substringAfter('=')
        ?.let { value -> URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }

    private companion object {
        const val TEST_REGION = "cn-hangzhou"
        const val TEST_BUCKET = "flowweft-wire-test"
        const val RANGE_BEHAVIOR_HEADER = "x-oss-range-behavior"
        const val FORBID_OVERWRITE_HEADER = "x-oss-forbid-overwrite"
    }
}
