package ai.icen.fw.adapter.s3

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.MultipartCompletionRejectedException
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageUploadRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class S3StorageAdapterNetworkPolicyTest {
    @Test
    fun `rejects a location-spliced opaque upload before every multipart network operation`() {
        FakeS3CompletionServer(expectedLengthDelta = 0).use { server ->
            server.start()
            adapter(server).use { adapter ->
                val first = adapter.beginMultipartUpload(uploadRequest(server.content))
                val second = adapter.beginMultipartUpload(uploadRequest(server.content))
                val spliced = MultipartUpload(first.uploadId, second.location)
                val callsBeforeSplice = server.requestCalls.get()

                assertThrows(IllegalArgumentException::class.java) {
                    adapter.uploadPart(spliced, 1, ByteArrayInputStream(byteArrayOf(1)), 1)
                }
                assertThrows(IllegalArgumentException::class.java) { adapter.listUploadedParts(spliced) }
                assertThrows(IllegalArgumentException::class.java) {
                    adapter.completeMultipartUpload(spliced, listOf(MultipartPart(1, PART_ETAG)))
                }
                assertThrows(IllegalArgumentException::class.java) { adapter.abortMultipartUpload(spliced) }

                assertEquals(callsBeforeSplice, server.requestCalls.get(), "A location mismatch must make no request.")
                assertEquals(0, server.listPartCalls.get())
                assertEquals(0, server.completeCalls.get())
                assertEquals(0, server.headCalls.get(), "A spliced completion must never reconcile another key.")
                assertEquals(0, server.objectGetCalls.get())
                assertEquals(0, server.abortCalls.get())
            }
        }
    }

    @Test
    fun `retries list probe but sends complete once and reconciles an ambiguous outcome`() {
        FakeS3CompletionServer(expectedLengthDelta = 0).use { server ->
            server.start()
            adapter(server).use { adapter ->
                val upload = adapter.beginMultipartUpload(uploadRequest(server.content))

                val stored = adapter.completeMultipartUpload(
                    upload,
                    listOf(MultipartPart(1, PART_ETAG)),
                )

                assertEquals(server.content.size.toLong(), stored.contentLength)
                assertEquals(sha256(server.content), stored.contentHash)
                assertEquals(2, server.listPartCalls.get(), "The side-effect-free list probe should retry once.")
                assertEquals(1, server.completeCalls.get(), "CompleteMultipartUpload must never retry transparently.")
                assertEquals(1, server.headCalls.get())
                assertEquals(1, server.objectGetCalls.get())
            }
        }
    }

    @Test
    fun `does not report ambiguous completion success when the durable declaration differs`() {
        FakeS3CompletionServer(expectedLengthDelta = 1).use { server ->
            server.start()
            adapter(server).use { adapter ->
                val upload = adapter.beginMultipartUpload(uploadRequest(server.content))

                val failure = assertThrows(S3StorageOperationException::class.java) {
                    adapter.completeMultipartUpload(upload, listOf(MultipartPart(1, PART_ETAG)))
                }

                assertEquals(S3StorageOperation.COMPLETE_MULTIPART_UPLOAD, failure.operation)
                assertTrue(failure.retryable)
                assertEquals(1, server.completeCalls.get())
                assertEquals(1, server.headCalls.get())
                assertEquals(0, server.objectGetCalls.get())
                assertNull(failure.cause)
                assertEquals(1, failure.suppressed.size)
                assertTrue(failure.suppressed.single() is S3StorageOperationException)
                assertEquals(
                    S3StorageFailureCategory.INTEGRITY,
                    (failure.suppressed.single() as S3StorageOperationException).category,
                )

                val rendered = StringWriter().also { writer ->
                    failure.printStackTrace(PrintWriter(writer))
                }.toString()
                listOf(
                    "private-access-key",
                    "private-secret-key",
                    "private-session-token",
                    "signed-query-value",
                    "Authorization: Bearer",
                ).forEach { sensitive -> assertFalse(rendered.contains(sensitive), sensitive) }
            }
        }
    }

    @Test
    fun `wraps a complete 200 embedded InvalidPart error as a definitive rejection`() {
        FakeS3CompletionServer(
            expectedLengthDelta = 0,
            completionStatus = 200,
            completionErrorCode = "InvalidPart",
            retryFirstList = false,
        ).use { server ->
            server.start()
            adapter(server).use { adapter ->
                val upload = adapter.beginMultipartUpload(uploadRequest(server.content))

                val failure = assertThrows(MultipartCompletionRejectedException::class.java) {
                    adapter.completeMultipartUpload(upload, listOf(MultipartPart(1, PART_ETAG)))
                }

                assertTrue(failure.cause is S3StorageOperationException)
                assertEquals(
                    S3StorageOperation.COMPLETE_MULTIPART_UPLOAD,
                    (failure.cause as S3StorageOperationException).operation,
                )
                assertEquals(1, server.listPartCalls.get())
                assertEquals(1, server.completeCalls.get())
                assertEquals(0, server.headCalls.get())
                assertEquals(0, server.objectGetCalls.get())
            }
        }
    }

    @Test
    fun `fits a 304 byte provider id exactly and rejects 305 bytes with cleanup`() {
        FakeS3CompletionServer(
            expectedLengthDelta = 0,
            providerUploadId = "p".repeat(304),
            retryFirstList = false,
        ).use { server ->
            server.start()
            adapter(server).use { adapter ->
                val upload = adapter.beginMultipartUpload(uploadRequest(server.content))

                assertEquals(512, upload.uploadId.value.length)
                adapter.abortMultipartUpload(upload)
                assertEquals(1, server.abortCalls.get())
            }
        }

        FakeS3CompletionServer(
            expectedLengthDelta = 0,
            providerUploadId = "p".repeat(305),
            retryFirstList = false,
        ).use { server ->
            server.start()
            adapter(server).use { adapter ->
                val failure = assertThrows(S3StorageOperationException::class.java) {
                    adapter.beginMultipartUpload(uploadRequest(server.content))
                }

                assertEquals(S3StorageFailureCategory.INTEGRITY, failure.category)
                assertEquals(1, server.abortCalls.get(), "An unpersistable provider session must be aborted once.")
                assertEquals(server.initiatedPath.get(), server.abortedPath.get())
                assertTrue(
                    server.abortedQuery.get().split('&').contains("uploadId=${"p".repeat(305)}"),
                    "Cleanup must abort the exact provider upload id returned by initiation.",
                )
            }
        }
    }

    @Test
    fun `keeps a legacy raw provider upload id usable even when it starts with the v2 marker`() {
        val legacyProviderId = "fw-s3-v2.legacy-provider-upload-id"
        FakeS3CompletionServer(
            expectedLengthDelta = 0,
            providerUploadId = legacyProviderId,
            retryFirstList = false,
        ).use { server ->
            server.start()
            adapter(server).use { adapter ->
                val begun = adapter.beginMultipartUpload(uploadRequest(server.content))
                val legacy = MultipartUpload(Identifier(legacyProviderId), begun.location)

                assertEquals(listOf(MultipartPart(1, PART_ETAG)), adapter.listUploadedParts(legacy))
                assertEquals(1, server.listPartCalls.get())
            }
        }
    }

    private fun adapter(server: FakeS3CompletionServer): S3StorageAdapter = S3StorageAdapter(
        S3StorageConfiguration(
            endpoint = URI("http://127.0.0.1:${server.port}"),
            region = "us-east-1",
            accessKey = "private-access-key",
            secretKey = "private-secret-key",
            bucket = BUCKET,
        ),
        S3StorageClientPolicy(
            connectionTimeout = Duration.ofSeconds(2),
            socketTimeout = Duration.ofSeconds(2),
            apiCallAttemptTimeout = Duration.ofSeconds(3),
            apiCallTimeout = Duration.ofSeconds(10),
            maxAttempts = 3,
        ),
    )

    private fun uploadRequest(content: ByteArray): StorageUploadRequest = StorageUploadRequest(
        tenantId = Identifier("network-policy-tenant"),
        objectName = "outcome-unknown.bin",
        contentLength = content.size.toLong(),
        contentType = "application/octet-stream",
        contentHash = sha256(content),
        metadata = mapOf("fixture" to "network-policy"),
    )

    private class FakeS3CompletionServer(
        private val expectedLengthDelta: Int,
        private val completionStatus: Int = 500,
        private val completionErrorCode: String = "InternalError",
        private val providerUploadId: String = PROVIDER_UPLOAD_ID,
        private val retryFirstList: Boolean = true,
    ) : AutoCloseable {
        val content: ByteArray = "flowweft-ambiguous-completion".toByteArray(StandardCharsets.UTF_8)
        val requestCalls = AtomicInteger()
        val listPartCalls = AtomicInteger()
        val completeCalls = AtomicInteger()
        val headCalls = AtomicInteger()
        val objectGetCalls = AtomicInteger()
        val abortCalls = AtomicInteger()
        val initiatedPath = AtomicReference<String>()
        val abortedPath = AtomicReference<String>()
        val abortedQuery = AtomicReference<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
        }

        val port: Int
            get() = server.address.port

        fun start() {
            server.start()
        }

        override fun close() {
            server.stop(0)
        }

        private fun handle(exchange: HttpExchange) {
            try {
                requestCalls.incrementAndGet()
                exchange.requestBody.use { input ->
                    val buffer = ByteArray(4 * 1024)
                    while (input.read(buffer) != -1) {
                        // Drain signed request bodies before writing a response.
                    }
                }
                val query = exchange.requestURI.rawQuery.orEmpty()
                when {
                    exchange.requestMethod == "POST" && query.contains("uploads") -> initiate(exchange)
                    exchange.requestMethod == "GET" && query.contains("uploadId=") -> listParts(exchange)
                    exchange.requestMethod == "POST" && query.contains("uploadId=") -> complete(exchange)
                    exchange.requestMethod == "DELETE" && query.contains("uploadId=") -> abort(exchange)
                    exchange.requestMethod == "HEAD" -> headObject(exchange)
                    exchange.requestMethod == "GET" -> getObject(exchange)
                    else -> error(exchange, 400, "InvalidRequest")
                }
            } finally {
                exchange.close()
            }
        }

        private fun initiate(exchange: HttpExchange) {
            initiatedPath.set(exchange.requestURI.rawPath)
            xml(
                exchange,
                200,
                """
                <InitiateMultipartUploadResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Bucket>$BUCKET</Bucket>
                  <Key>objects</Key>
                  <UploadId>$providerUploadId</UploadId>
                </InitiateMultipartUploadResult>
                """.trimIndent(),
            )
        }

        private fun listParts(exchange: HttpExchange) {
            if (listPartCalls.incrementAndGet() == 1 && retryFirstList) {
                error(exchange, 500, "InternalError")
                return
            }
            xml(
                exchange,
                200,
                """
                <ListPartsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Bucket>$BUCKET</Bucket>
                  <Key>objects</Key>
                  <UploadId>$providerUploadId</UploadId>
                  <PartNumberMarker>0</PartNumberMarker>
                  <NextPartNumberMarker>1</NextPartNumberMarker>
                  <MaxParts>1000</MaxParts>
                  <IsTruncated>false</IsTruncated>
                  <Part>
                    <PartNumber>1</PartNumber>
                    <LastModified>2026-07-16T00:00:00.000Z</LastModified>
                    <ETag>&quot;part-etag&quot;</ETag>
                    <Size>${content.size}</Size>
                  </Part>
                </ListPartsResult>
                """.trimIndent(),
            )
        }

        private fun complete(exchange: HttpExchange) {
            completeCalls.incrementAndGet()
            error(exchange, completionStatus, completionErrorCode)
        }

        private fun abort(exchange: HttpExchange) {
            abortCalls.incrementAndGet()
            abortedPath.set(exchange.requestURI.rawPath)
            abortedQuery.set(exchange.requestURI.rawQuery.orEmpty())
            exchange.sendResponseHeaders(204, -1)
        }

        private fun headObject(exchange: HttpExchange) {
            headCalls.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("Content-Length", content.size.toString())
            exchange.responseHeaders.add("ETag", OBJECT_ETAG)
            exchange.responseHeaders.add("x-amz-meta-fileweft-declaration-version", "multipart-v1")
            exchange.responseHeaders.add(
                "x-amz-meta-fileweft-content-length",
                (content.size + expectedLengthDelta).toString(),
            )
            exchange.responseHeaders.add("x-amz-meta-fileweft-content-sha256", sha256(content))
            exchange.sendResponseHeaders(200, -1)
        }

        private fun getObject(exchange: HttpExchange) {
            objectGetCalls.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("ETag", OBJECT_ETAG)
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.write(content)
        }

        private fun error(exchange: HttpExchange, status: Int, code: String) {
            xml(
                exchange,
                status,
                """
                <Error>
                  <Code>$code</Code>
                  <Message>private-secret-key private-session-token signed-query-value Authorization: Bearer</Message>
                  <RequestId>private-access-key</RequestId>
                </Error>
                """.trimIndent(),
            )
        }

        private fun xml(exchange: HttpExchange, status: Int, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/xml")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }

    companion object {
        private const val BUCKET = "fileweft-integration"
        private const val PROVIDER_UPLOAD_ID = "provider-upload-id"
        private const val PART_ETAG = "\"part-etag\""
        private const val OBJECT_ETAG = "\"object-etag\""

        private fun sha256(content: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(content)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
