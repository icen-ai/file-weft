package ai.icen.fw.adapter.dify

import ai.icen.fw.spi.connector.ConnectorFileSource
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import java.util.Arrays
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal class DifyDeadline private constructor(
    private val deadlineNanos: Long,
) {
    fun remainingMillis(): Long {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) throw DifyDeadlineExceededException()
        return (remainingNanos / 1_000_000L).coerceAtLeast(1L)
    }

    fun canWait(delayMillis: Long): Boolean = try {
        remainingMillis() > delayMillis
    } catch (_: DifyDeadlineExceededException) {
        false
    }

    companion object {
        fun start(timeout: Duration): DifyDeadline {
            require(
                !timeout.isNegative && !timeout.isZero &&
                    (timeout.seconds > 0L || timeout.nano >= 1_000_000)
            ) {
                "Dify invocation timeout must be at least one millisecond."
            }
            val timeoutNanos = try {
                timeout.toNanos()
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
            val now = System.nanoTime()
            val deadline = try {
                Math.addExact(now, timeoutNanos)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
            return DifyDeadline(deadline)
        }
    }
}

internal class DifyDeadlineExceededException : RuntimeException("Dify invocation deadline was exceeded.")

internal enum class DifyFailureKind {
    RETRYABLE,
    PERMANENT,
    AMBIGUOUS,
}

internal fun validatedDifyContentType(value: String?): String? {
    if (value == null) return null
    val safe = safeText(value, "Dify source content type", 255)
    require(safe.toMediaTypeOrNull() != null) { "Dify source content type is invalid." }
    return safe
}

internal class DifyDownloadedSource(
    val path: Path,
    val length: Long,
    val contentHash: String,
) : AutoCloseable {
    override fun close() {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            path.toFile().deleteOnExit()
        }
    }
}

internal class DifySourceDownloadResult private constructor(
    val source: DifyDownloadedSource?,
    val failureKind: DifyFailureKind?,
    val diagnostic: String?,
) {
    companion object {
        fun success(source: DifyDownloadedSource): DifySourceDownloadResult =
            DifySourceDownloadResult(source, null, null)

        fun failure(kind: DifyFailureKind, diagnostic: String): DifySourceDownloadResult =
            DifySourceDownloadResult(null, kind, diagnostic)
    }
}

internal interface DifySourceDownloader {
    fun download(source: ConnectorFileSource, deadline: DifyDeadline): DifySourceDownloadResult
}

internal interface DifySleeper {
    @Throws(InterruptedException::class)
    fun sleep(millis: Long)
}

internal object ThreadDifySleeper : DifySleeper {
    override fun sleep(millis: Long) = Thread.sleep(millis)
}

internal class StrictDifySourceDownloader(
    private val profile: DifyKnowledgeBaseProfile,
    private val sleeper: DifySleeper = ThreadDifySleeper,
    client: OkHttpClient? = null,
    private val addressResolver: DifyTrustedAddressResolver = PolicyDifyTrustedAddressResolver(profile.sourceTrustPolicy),
) : DifySourceDownloader {
    private val client: OkHttpClient = (client ?: OkHttpClient.Builder().build()).newBuilder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(profile.connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(profile.readTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .build()

    override fun download(source: ConnectorFileSource, deadline: DifyDeadline): DifySourceDownloadResult {
        val expectedHash = try {
            canonicalSha256(requireNotNull(source.contentHash) {
                "Dify synchronization requires a canonical source content hash."
            }, "Dify source content hash")
        } catch (_: IllegalArgumentException) {
            return DifySourceDownloadResult.failure(
                DifyFailureKind.PERMANENT,
                "Source content hash is missing or invalid.",
            )
        }
        try {
            profile.sourceTrustPolicy.requireTrustedSourceUri(source.downloadUri)
        } catch (_: IllegalArgumentException) {
            return DifySourceDownloadResult.failure(DifyFailureKind.PERMANENT, "Source URI is not trusted by this connector profile.")
        }

        var attempt = 1
        var delayMillis = profile.initialReadRetryDelay.toMillis()
        while (true) {
            val result = downloadOnce(source.downloadUri, expectedHash, deadline)
            if (result.source != null || result.failureKind != DifyFailureKind.RETRYABLE || attempt >= profile.maximumReadAttempts) {
                return result
            }
            if (!deadline.canWait(delayMillis)) {
                return DifySourceDownloadResult.failure(DifyFailureKind.RETRYABLE, "Source download deadline was exceeded.")
            }
            try {
                sleeper.sleep(delayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return DifySourceDownloadResult.failure(DifyFailureKind.RETRYABLE, "Source download was interrupted.")
            }
            attempt++
            delayMillis = min(delayMillis * 2L, MAX_RETRY_DELAY_MILLIS)
        }
    }

    private fun downloadOnce(uri: URI, expectedHash: String, deadline: DifyDeadline): DifySourceDownloadResult {
        val addresses = try {
            addressResolver.resolve(requireNotNull(uri.host))
        } catch (_: UnknownHostException) {
            return DifySourceDownloadResult.failure(
                DifyFailureKind.RETRYABLE,
                "Source host did not resolve to a permitted address.",
            )
        } catch (_: RuntimeException) {
            return DifySourceDownloadResult.failure(
                DifyFailureKind.PERMANENT,
                "Source host could not be validated safely.",
            )
        }
        val request = try {
            Request.Builder().url(uri.toURL()).get().build()
        } catch (_: IllegalArgumentException) {
            return DifySourceDownloadResult.failure(DifyFailureKind.PERMANENT, "Source URI cannot be requested safely.")
        }
        val pinnedClient = client.newBuilder()
            .dns(DifyPinnedDns(requireNotNull(uri.host), addresses))
            .proxy(Proxy.NO_PROXY)
            .build()
        val call = pinnedClient.newCall(request)
        return try {
            call.timeout().timeout(deadline.remainingMillis(), TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                if (response.isRedirect) {
                    return DifySourceDownloadResult.failure(DifyFailureKind.PERMANENT, "Source redirects are not permitted.")
                }
                if (response.code != 200) {
                    val kind = if (response.code in RETRYABLE_SOURCE_STATUSES) DifyFailureKind.RETRYABLE else DifyFailureKind.PERMANENT
                    return DifySourceDownloadResult.failure(kind, "Source download was rejected by the trusted origin.")
                }
                val body = response.body
                    ?: return DifySourceDownloadResult.failure(DifyFailureKind.RETRYABLE, "Source download returned no body.")
                if (body.contentLength() > profile.maximumSourceBytes) {
                    return DifySourceDownloadResult.failure(DifyFailureKind.PERMANENT, "Source exceeds the configured connector size limit.")
                }
                copyBounded(body.byteStream(), expectedHash)
            }
        } catch (_: DifyDeadlineExceededException) {
            call.cancel()
            DifySourceDownloadResult.failure(DifyFailureKind.RETRYABLE, "Source download deadline was exceeded.")
        } catch (_: IOException) {
            DifySourceDownloadResult.failure(DifyFailureKind.RETRYABLE, "Source download could not complete.")
        }
    }

    private fun copyBounded(input: InputStream, expectedHash: String): DifySourceDownloadResult {
        val temp = Files.createTempFile("flowweft-dify-source-", ".bin")
        var count = 0L
        var keep = false
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            restrictTempFile(temp)
            Files.newOutputStream(temp).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read.toLong()
                    if (count > profile.maximumSourceBytes) {
                        return DifySourceDownloadResult.failure(
                            DifyFailureKind.PERMANENT,
                            "Source exceeds the configured connector size limit.",
                        )
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
            val actualHash = "sha256:" + digest.digest().joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            if (actualHash != expectedHash) {
                return DifySourceDownloadResult.failure(DifyFailureKind.PERMANENT, "Source content hash verification failed.")
            }
            keep = true
            return DifySourceDownloadResult.success(DifyDownloadedSource(temp, count, actualHash))
        } finally {
            if (!keep) {
                try {
                    Files.deleteIfExists(temp)
                } catch (_: IOException) {
                    temp.toFile().deleteOnExit()
                }
            }
        }
    }

    private fun restrictTempFile(path: Path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        } catch (_: UnsupportedOperationException) {
            // Windows ACLs inherit from the process temp directory; deletion is still mandatory.
        } catch (_: IOException) {
            // Failure to tighten optional POSIX permissions does not widen a platform ACL.
        }
    }

    private companion object {
        val RETRYABLE_SOURCE_STATUSES = setOf(408, 425, 429, 500, 502, 503, 504)
        const val MAX_RETRY_DELAY_MILLIS = 2_000L
    }
}

internal enum class DifyWriteDisposition {
    ACCEPTED,
    NOT_SENT,
    OUTCOME_UNKNOWN,
}

internal class DifyWriteResult(
    val disposition: DifyWriteDisposition,
    val documentId: String? = null,
    val batchId: String? = null,
)

internal enum class DifyDeleteDisposition {
    ACCEPTED,
    API_ABSENT,
    RETRYABLE_REJECTED,
    PERMANENT_REJECTED,
    AMBIGUOUS,
}

internal enum class DifyReadDisposition {
    SUCCESS,
    API_ABSENT,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
}

internal class DifyStatusReadResult(
    val disposition: DifyReadDisposition,
    val state: DifyProjectionIndexState = DifyProjectionIndexState.UNKNOWN,
)

internal class DifyHealthReadResult(
    val disposition: DifyReadDisposition,
    val datasetMatches: Boolean = false,
    val apiEnabled: Boolean = false,
    val managedDataset: Boolean = false,
    val embeddingAvailable: Boolean = false,
)

internal interface DifyRemoteApi {
    fun create(source: DifyDownloadedSource, fileName: String, contentType: String?, deadline: DifyDeadline): DifyWriteResult

    fun update(
        documentId: String,
        source: DifyDownloadedSource,
        fileName: String,
        contentType: String?,
        deadline: DifyDeadline,
    ): DifyWriteResult

    fun delete(documentId: String, deadline: DifyDeadline): DifyDeleteDisposition

    fun indexingStatus(batchId: String, documentId: String, deadline: DifyDeadline): DifyStatusReadResult

    fun documentStatus(documentId: String, deadline: DifyDeadline): DifyStatusReadResult

    fun health(deadline: DifyDeadline): DifyHealthReadResult
}

internal class OkHttpDifyRemoteApi(
    private val profile: DifyKnowledgeBaseProfile,
    private val apiKeyProvider: DifyApiKeyProvider,
    private val sleeper: DifySleeper = ThreadDifySleeper,
    mapper: ObjectMapper = ObjectMapper(),
    client: OkHttpClient? = null,
) : DifyRemoteApi {
    private val client: OkHttpClient = hardenedDifyApiClient(profile, client)
    private val mapper: ObjectMapper = mapper.copy()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val apiBase = profile.apiBaseUri.toString().trimEnd('/')

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val RETRYABLE_READ_STATUSES = setOf(408, 425, 429)
        const val MAX_RETRY_DELAY_MILLIS = 2_000L
    }

    override fun create(
        source: DifyDownloadedSource,
        fileName: String,
        contentType: String?,
        deadline: DifyDeadline,
    ): DifyWriteResult {
        val request = try {
            multipartRequest(
                method = "POST",
                url = "$apiBase/datasets/${profile.datasetId}/document/create-by-file",
                source = source,
                fileName = fileName,
                contentType = contentType,
                data = createData(),
            )
        } catch (_: RuntimeException) {
            return DifyWriteResult(DifyWriteDisposition.NOT_SENT)
        }
        return parseWrite(executeOnce(request, deadline), expectedDocumentId = null)
    }

    override fun update(
        documentId: String,
        source: DifyDownloadedSource,
        fileName: String,
        contentType: String?,
        deadline: DifyDeadline,
    ): DifyWriteResult {
        val canonicalDocumentId = try {
            canonicalUuid(documentId, "Dify document id")
        } catch (_: RuntimeException) {
            return DifyWriteResult(DifyWriteDisposition.NOT_SENT)
        }
        val request = try {
            multipartRequest(
                method = "PATCH",
                url = "$apiBase/datasets/${profile.datasetId}/documents/$canonicalDocumentId",
                source = source,
                fileName = fileName,
                contentType = contentType,
                data = updateData(),
            )
        } catch (_: RuntimeException) {
            return DifyWriteResult(DifyWriteDisposition.NOT_SENT)
        }
        return parseWrite(executeOnce(request, deadline), expectedDocumentId = canonicalDocumentId)
    }

    override fun delete(documentId: String, deadline: DifyDeadline): DifyDeleteDisposition {
        val canonicalDocumentId = canonicalUuid(documentId, "Dify document id")
        val request = Request.Builder()
            .url("$apiBase/datasets/${profile.datasetId}/documents/$canonicalDocumentId")
            .delete()
            .build()
        val result = executeOnce(request, deadline)
        if (result.kind == TransportKind.NETWORK_FAILURE) return DifyDeleteDisposition.AMBIGUOUS
        if (result.kind == TransportKind.LOCAL_FAILURE) return DifyDeleteDisposition.PERMANENT_REJECTED
        return when (result.responseCode) {
            204 -> DifyDeleteDisposition.ACCEPTED
            404 -> DifyDeleteDisposition.API_ABSENT
            408, 425, 429 -> DifyDeleteDisposition.RETRYABLE_REJECTED
            in 500..599 -> DifyDeleteDisposition.AMBIGUOUS
            else -> DifyDeleteDisposition.PERMANENT_REJECTED
        }
    }

    override fun indexingStatus(batchId: String, documentId: String, deadline: DifyDeadline): DifyStatusReadResult {
        val safeBatch = safePathToken(batchId, "Dify indexing batch id", 128)
        val canonicalDocumentId = canonicalUuid(documentId, "Dify document id")
        val request = Request.Builder()
            .url("$apiBase/datasets/${profile.datasetId}/documents/$safeBatch/indexing-status")
            .get()
            .build()
        val transport = executeRead(request, deadline)
        if (transport.kind != TransportKind.RESPONSE) return readFailure(transport)
        if (transport.responseCode == 404) return DifyStatusReadResult(DifyReadDisposition.API_ABSENT)
        if (transport.responseCode != 200) return classifyReadHttp(transport.responseCode)
        return try {
            val entries = mapper.readTree(transport.body).path("data")
            if (!entries.isArray) return DifyStatusReadResult(DifyReadDisposition.PERMANENT_FAILURE)
            val matching = entries.filter { it.path("id").asText() == canonicalDocumentId }
            if (matching.size != 1) return DifyStatusReadResult(DifyReadDisposition.PERMANENT_FAILURE)
            mapIndexState(matching.single().path("indexing_status").asText())
        } catch (_: Exception) {
            DifyStatusReadResult(DifyReadDisposition.PERMANENT_FAILURE)
        }
    }

    override fun documentStatus(documentId: String, deadline: DifyDeadline): DifyStatusReadResult {
        val canonicalDocumentId = canonicalUuid(documentId, "Dify document id")
        val request = Request.Builder()
            .url("$apiBase/datasets/${profile.datasetId}/documents/$canonicalDocumentId?metadata=without")
            .get()
            .build()
        val transport = executeRead(request, deadline)
        if (transport.kind != TransportKind.RESPONSE) return readFailure(transport)
        if (transport.responseCode == 404) return DifyStatusReadResult(DifyReadDisposition.API_ABSENT)
        if (transport.responseCode != 200) return classifyReadHttp(transport.responseCode)
        return try {
            val node = mapper.readTree(transport.body)
            if (node.path("id").asText() != canonicalDocumentId) {
                return DifyStatusReadResult(DifyReadDisposition.PERMANENT_FAILURE)
            }
            val indexed = mapIndexState(node.path("indexing_status").asText())
            if (
                indexed.disposition == DifyReadDisposition.SUCCESS &&
                indexed.state == DifyProjectionIndexState.AVAILABLE &&
                (!node.path("enabled").asBoolean(false) || node.path("archived").asBoolean(false))
            ) {
                DifyStatusReadResult(DifyReadDisposition.SUCCESS, DifyProjectionIndexState.FAILED)
            } else {
                indexed
            }
        } catch (_: Exception) {
            DifyStatusReadResult(DifyReadDisposition.PERMANENT_FAILURE)
        }
    }

    override fun health(deadline: DifyDeadline): DifyHealthReadResult {
        val request = Request.Builder().url("$apiBase/datasets/${profile.datasetId}").get().build()
        val transport = executeRead(request, deadline)
        if (transport.kind != TransportKind.RESPONSE) {
            return DifyHealthReadResult(
                if (transport.kind == TransportKind.NETWORK_FAILURE) DifyReadDisposition.RETRYABLE_FAILURE
                else DifyReadDisposition.PERMANENT_FAILURE,
            )
        }
        if (transport.responseCode != 200) {
            val disposition = if (transport.responseCode in RETRYABLE_READ_STATUSES || transport.responseCode in 500..599) {
                DifyReadDisposition.RETRYABLE_FAILURE
            } else {
                DifyReadDisposition.PERMANENT_FAILURE
            }
            return DifyHealthReadResult(disposition)
        }
        return try {
            val node = mapper.readTree(transport.body)
            DifyHealthReadResult(
                disposition = DifyReadDisposition.SUCCESS,
                datasetMatches = node.path("id").asText() == profile.datasetId,
                apiEnabled = node.path("enable_api").asBoolean(false),
                managedDataset = node.path("provider").asText() == "vendor",
                embeddingAvailable = node.path("embedding_available").asBoolean(false) ||
                    node.path("indexing_technique").asText() == "economy",
            )
        } catch (_: Exception) {
            DifyHealthReadResult(DifyReadDisposition.PERMANENT_FAILURE)
        }
    }

    private fun multipartRequest(
        method: String,
        url: String,
        source: DifyDownloadedSource,
        fileName: String,
        contentType: String?,
        data: String,
    ): Request {
        val safeFileName = validateFileName(fileName)
        val mediaType = validatedDifyContentType(contentType)?.toMediaType() ?: "application/octet-stream".toMediaType()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", safeFileName, source.path.toFile().asRequestBody(mediaType))
            .addFormDataPart("data", null, data.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return Request.Builder().url(url).method(method, DifyOneShotRequestBody(body)).build()
    }

    /*
     * The stable create and canonical PATCH update body has no arbitrary metadata field.
     * Do not invent one: this adapter uses a tenant-dedicated dataset and keeps
     * projection/ACL truth in DifyProjectionStore.
     */
    private fun createData(): String = mapper.writeValueAsString(
        linkedMapOf(
            "indexing_technique" to profile.indexing.indexingTechnique.wireValue,
            "doc_form" to profile.indexing.documentForm.wireValue,
            "doc_language" to profile.indexing.documentLanguage,
            "process_rule" to mapOf("mode" to "automatic"),
        ),
    )

    /** The canonical PATCH update accepts the same indexing fields as create-by-file. */
    private fun updateData(): String = createData()

    private fun parseWrite(transport: TransportResult, expectedDocumentId: String?): DifyWriteResult {
        return when (transport.kind) {
            TransportKind.LOCAL_FAILURE -> DifyWriteResult(DifyWriteDisposition.NOT_SENT)
            TransportKind.NETWORK_FAILURE -> DifyWriteResult(DifyWriteDisposition.OUTCOME_UNKNOWN)
            TransportKind.RESPONSE -> {
                when (transport.responseCode) {
                    200 -> parseAcceptedWrite(transport.body, expectedDocumentId)
                    else -> DifyWriteResult(DifyWriteDisposition.OUTCOME_UNKNOWN)
                }
            }
        }
    }

    private fun parseAcceptedWrite(body: ByteArray, expectedDocumentId: String?): DifyWriteResult = try {
        val node = mapper.readTree(body)
        val documentId = canonicalUuid(node.path("document").path("id").asText(), "Dify response document id")
        val batchId = safePathToken(node.path("batch").asText(), "Dify response batch id", 128)
        if (expectedDocumentId != null && documentId != expectedDocumentId) {
            DifyWriteResult(DifyWriteDisposition.OUTCOME_UNKNOWN)
        } else {
            DifyWriteResult(DifyWriteDisposition.ACCEPTED, documentId, batchId)
        }
    } catch (_: Exception) {
        DifyWriteResult(DifyWriteDisposition.OUTCOME_UNKNOWN)
    }

    private fun executeRead(request: Request, deadline: DifyDeadline): TransportResult {
        var attempt = 1
        var delayMillis = profile.initialReadRetryDelay.toMillis()
        while (true) {
            val result = executeOnce(request, deadline)
            val retryable = result.kind == TransportKind.NETWORK_FAILURE ||
                (result.kind == TransportKind.RESPONSE &&
                    (result.responseCode in RETRYABLE_READ_STATUSES || result.responseCode in 500..599))
            if (!retryable || attempt >= profile.maximumReadAttempts) return result
            if (!deadline.canWait(delayMillis)) return result
            try {
                sleeper.sleep(delayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return TransportResult(TransportKind.NETWORK_FAILURE)
            }
            attempt++
            delayMillis = min(delayMillis * 2L, MAX_RETRY_DELAY_MILLIS)
        }
    }

    private fun executeOnce(request: Request, deadline: DifyDeadline): TransportResult {
        val loaded: CharArray? = try {
            apiKeyProvider.loadApiKey()
        } catch (_: Exception) {
            return TransportResult(TransportKind.LOCAL_FAILURE)
        }
        if (loaded == null) return TransportResult(TransportKind.LOCAL_FAILURE)
        val authorization = try {
            "Bearer " + String(validatedApiKey(loaded))
        } catch (_: Exception) {
            return TransportResult(TransportKind.LOCAL_FAILURE)
        } finally {
            Arrays.fill(loaded, '\u0000')
        }
        val authenticated = try {
            request.newBuilder().header("Authorization", authorization).build()
        } catch (_: RuntimeException) {
            return TransportResult(TransportKind.LOCAL_FAILURE)
        }
        val call = try {
            client.newCall(authenticated)
        } catch (_: RuntimeException) {
            return TransportResult(TransportKind.LOCAL_FAILURE)
        }
        val timeoutMillis = try {
            deadline.remainingMillis()
        } catch (_: DifyDeadlineExceededException) {
            return TransportResult(TransportKind.LOCAL_FAILURE)
        }
        try {
            call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: RuntimeException) {
            return TransportResult(TransportKind.LOCAL_FAILURE)
        }
        return try {
            call.execute().use { response ->
                val body = response.body
                val length = body?.contentLength() ?: 0L
                if (length > profile.maximumApiResponseBytes) {
                    // The request already crossed the network boundary. For a
                    // write this is an ambiguous outcome, never a safe reject.
                    return TransportResult(TransportKind.NETWORK_FAILURE)
                }
                val bytes = body?.byteStream()?.let { readBounded(it, profile.maximumApiResponseBytes) } ?: ByteArray(0)
                TransportResult(TransportKind.RESPONSE, response.code, bytes)
            }
        } catch (_: DifyDeadlineExceededException) {
            call.cancel()
            TransportResult(TransportKind.NETWORK_FAILURE)
        } catch (_: IOException) {
            TransportResult(TransportKind.NETWORK_FAILURE)
        }
    }

    private fun readBounded(input: InputStream, maximum: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream(min(maximum, 8192L).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read.toLong()
            if (total > maximum) throw IOException("Dify API response exceeded configured limit.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun mapIndexState(value: String): DifyStatusReadResult = when (value) {
        "waiting", "parsing", "cleaning", "splitting", "indexing" ->
            DifyStatusReadResult(DifyReadDisposition.SUCCESS, DifyProjectionIndexState.INDEXING)
        "completed" -> DifyStatusReadResult(DifyReadDisposition.SUCCESS, DifyProjectionIndexState.AVAILABLE)
        "error", "paused" -> DifyStatusReadResult(DifyReadDisposition.SUCCESS, DifyProjectionIndexState.FAILED)
        else -> DifyStatusReadResult(DifyReadDisposition.PERMANENT_FAILURE)
    }

    private fun readFailure(result: TransportResult): DifyStatusReadResult = DifyStatusReadResult(
        if (result.kind == TransportKind.NETWORK_FAILURE) DifyReadDisposition.RETRYABLE_FAILURE
        else DifyReadDisposition.PERMANENT_FAILURE,
    )

    private fun classifyReadHttp(status: Int): DifyStatusReadResult = DifyStatusReadResult(
        if (status in RETRYABLE_READ_STATUSES || status in 500..599) {
            DifyReadDisposition.RETRYABLE_FAILURE
        } else {
            DifyReadDisposition.PERMANENT_FAILURE
        },
    )

    private fun validateFileName(value: String): String {
        val safe = safeText(value, "Dify source file name", 255)
        require('/' !in safe && '\\' !in safe && safe != "." && safe != "..") {
            "Dify source file name must not contain a path."
        }
        return safe
    }

    private fun safePathToken(value: String, label: String, maximumLength: Int): String {
        val safe = safeText(value, label, maximumLength)
        require(safe.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "$label contains unsafe path characters." }
        return safe
    }

    private enum class TransportKind {
        RESPONSE,
        LOCAL_FAILURE,
        NETWORK_FAILURE,
    }

    private class TransportResult(
        val kind: TransportKind,
        val responseCode: Int = 0,
        val body: ByteArray = ByteArray(0),
    )

}

/** Prevents OkHttp response-follow-up logic from replaying a Dify create or update body. */
private class DifyOneShotRequestBody(
    private val delegate: RequestBody,
) : RequestBody() {
    override fun contentType() = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)

    override fun isOneShot(): Boolean = true
}

internal fun hardenedDifyApiClient(profile: DifyKnowledgeBaseProfile, client: OkHttpClient?): OkHttpClient =
    (client ?: OkHttpClient.Builder().build()).newBuilder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .dns(DifyValidatingDns(profile.allowPrivateApiAddresses))
        .connectTimeout(profile.connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(profile.readTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .writeTimeout(profile.readTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .build()
