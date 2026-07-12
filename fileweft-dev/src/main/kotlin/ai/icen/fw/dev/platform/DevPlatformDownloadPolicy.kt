package ai.icen.fw.dev.platform

import java.net.HttpURLConnection
import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * Restricts the development receiver to explicitly trusted object-storage
 * hosts.  A downstream connector must never turn an arbitrary request body
 * into a general-purpose HTTP client.
 */
class DevPlatformDownloadPolicy(
    allowedHosts: Collection<String>,
    private val maxDownloadBytes: Long,
) {
    private val allowedHosts: Set<String> = allowedHosts.map(::normalizeHost).toSet()

    init {
        require(this.allowedHosts.isNotEmpty()) { "At least one development platform download host must be configured." }
        require(maxDownloadBytes > 0) { "Development platform maximum download bytes must be positive." }
    }

    fun verifyDownload(uri: URI): Long {
        validate(uri)
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = DOWNLOAD_TIMEOUT_MILLIS
            readTimeout = DOWNLOAD_TIMEOUT_MILLIS
            instanceFollowRedirects = false
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw DevPlatformRetryableException("Source download returned HTTP ${connection.responseCode}.")
            }
            val advertisedLength = connection.contentLengthLong
            if (advertisedLength > maxDownloadBytes) {
                throw DevPlatformRetryableException("Source download exceeds the configured maximum size.")
            }
            var total = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        if (total > maxDownloadBytes - read.toLong()) {
                            throw DevPlatformRetryableException("Source download exceeds the configured maximum size.")
                        }
                        total += read.toLong()
                    }
                }
            }
            return total
        } catch (failure: DevPlatformRetryableException) {
            throw failure
        } catch (failure: Exception) {
            throw DevPlatformRetryableException("Source download could not complete.", failure)
        } finally {
            connection.disconnect()
        }
    }

    private fun validate(uri: URI) {
        require(uri.isAbsolute) { "Download URI must be absolute." }
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "Download URI must use HTTP or HTTPS."
        }
        require(uri.userInfo == null) { "Download URI must not include user information." }
        val host = uri.host ?: throw IllegalArgumentException("Download URI must include a host.")
        require(normalizeHost(host) in allowedHosts) { "Download URI host is not allowed by the development platform." }
    }

    private fun normalizeHost(value: String): String {
        val normalized = value.trim().trimEnd('.')
        require(normalized.isNotEmpty()) { "Development platform download host must not be blank." }
        return IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
    }

    private companion object {
        const val DOWNLOAD_TIMEOUT_MILLIS = 10_000
        const val BUFFER_SIZE = 8 * 1024
    }
}
