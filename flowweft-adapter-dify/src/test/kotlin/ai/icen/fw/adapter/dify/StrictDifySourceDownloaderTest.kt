package ai.icen.fw.adapter.dify

import ai.icen.fw.spi.connector.ConnectorFileSource
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.file.Files
import java.time.Duration
import java.util.ArrayDeque

class StrictDifySourceDownloaderTest {
    @Test
    fun `downloads a trusted source into a verified disposable local file`() {
        val transport = SourceTransport(SourceResponse(200, "hello"))
        val downloader = downloader(transport)

        val result = downloader.download(source(TEST_SOURCE_HASH), deadline())

        assertNotNull(result.source)
        val downloaded = requireNotNull(result.source)
        assertEquals(5L, downloaded.length)
        assertEquals(TEST_SOURCE_HASH, downloaded.contentHash)
        assertTrue(Files.exists(downloaded.path))
        downloaded.close()
        assertFalse(Files.exists(downloaded.path))
    }

    @Test
    fun `rejects content mismatch redirects and authorization failures without retrying`() {
        val mismatchTransport = SourceTransport(SourceResponse(200, "tampered"))
        val mismatch = downloader(mismatchTransport).download(source(TEST_SOURCE_HASH), deadline())
        assertNull(mismatch.source)
        assertEquals(DifyFailureKind.PERMANENT, mismatch.failureKind)

        val redirectTransport = SourceTransport(SourceResponse(302, ""))
        val redirect = downloader(redirectTransport).download(source(TEST_SOURCE_HASH), deadline())
        assertEquals(DifyFailureKind.PERMANENT, redirect.failureKind)
        assertEquals(1, redirectTransport.calls)

        val unauthorizedTransport = SourceTransport(SourceResponse(401, ""))
        val unauthorized = downloader(unauthorizedTransport).download(source(TEST_SOURCE_HASH), deadline())
        assertEquals(DifyFailureKind.PERMANENT, unauthorized.failureKind)
        assertEquals(1, unauthorizedTransport.calls)
    }

    @Test
    fun `retries only bounded transient reads`() {
        val transport = SourceTransport(
            SourceResponse(503, ""),
            SourceResponse(429, ""),
            SourceResponse(200, "hello"),
        )

        val result = downloader(transport).download(source(TEST_SOURCE_HASH), deadline())

        result.source?.close()
        assertNotNull(result.source)
        assertEquals(3, transport.calls)
    }

    @Test
    fun `pinned DNS exposes only the validated host and address snapshot`() {
        val address = publicTestAddress()
        val dns = DifyPinnedDns("files.example.test", listOf(address))

        assertEquals(listOf(address), dns.lookup("FILES.EXAMPLE.TEST"))
        assertThrows(UnknownHostException::class.java) { dns.lookup("other.example.test") }
    }

    private fun downloader(transport: SourceTransport): StrictDifySourceDownloader = StrictDifySourceDownloader(
        profile = testProfile(),
        sleeper = object : DifySleeper {
            override fun sleep(millis: Long) = Unit
        },
        client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(transport)
            .build(),
        addressResolver = object : DifyTrustedAddressResolver {
            override fun resolve(hostname: String): List<InetAddress> {
                assertEquals("files.example.test", hostname)
                return listOf(publicTestAddress())
            }
        },
    )

    private fun publicTestAddress(): InetAddress = InetAddress.getByAddress(
        byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
    )

    private fun source(hash: String): ConnectorFileSource = ConnectorFileSource(
        URI("https://files.example.test/hello.txt"),
        "hello.txt",
        "text/plain",
        hash,
    )

    private fun deadline(): DifyDeadline = DifyDeadline.start(Duration.ofSeconds(2))

    private data class SourceResponse(val code: Int, val body: String)

    private class SourceTransport(vararg responses: SourceResponse) : Interceptor {
        private val responses = ArrayDeque(responses.toList())
        var calls: Int = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            calls++
            val response = checkNotNull(responses.pollFirst()) { "No source response remains." }
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(response.code)
                .message("stub")
                .body(response.body.toResponseBody())
                .build()
        }
    }
}
