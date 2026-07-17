package ai.icen.fw.agent.adapter.http.okhttp

import ai.icen.fw.agent.adapter.http.AgentProtocolHttpCodecLimits
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpExchangeRequest
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpMethod
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpTransport
import ai.icen.fw.agent.adapter.http.AgentProtocolHttpWireResponse
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchRequest
import ai.icen.fw.agent.api.AgentRemoteTransportReceipt
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.net.URI
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class AgentProtocolOkHttpTransportConfiguration @JvmOverloads constructor(
    val limits: AgentProtocolHttpCodecLimits = AgentProtocolHttpCodecLimits(),
    val connectTimeoutMillis: Long = 5_000L,
    val readTimeoutMillis: Long = 30_000L,
    val writeTimeoutMillis: Long = 30_000L,
    val callTimeoutMillis: Long = 30_000L,
    val tlsIdentityKind: AgentProtocolHttpTlsIdentityKind = AgentProtocolHttpTlsIdentityKind.LEAF_SPKI_SHA256,
) {
    init {
        require(connectTimeoutMillis in 1L..120_000L) { "Agent protocol connect timeout is invalid." }
        require(readTimeoutMillis in 1L..300_000L) { "Agent protocol read timeout is invalid." }
        require(writeTimeoutMillis in 1L..300_000L) { "Agent protocol write timeout is invalid." }
        require(callTimeoutMillis in 1L..300_000L) { "Agent protocol call timeout is invalid." }
    }
}

/**
 * Hardened one-dispatch OkHttp transport. A fresh client, connection pool, pinned DNS and pinned
 * trust manager are built for every exchange; no proxy, redirect, retry, cookie or shared socket
 * state survives into another peer or tenant.
 */
class OkHttpAgentProtocolHttpTransport @JvmOverloads constructor(
    private val credentialProvider: AgentProtocolHttpCredentialProvider,
    private val evidenceRecorder: AgentProtocolHttpEvidenceRecorder,
    private val configuration: AgentProtocolOkHttpTransportConfiguration =
        AgentProtocolOkHttpTransportConfiguration(),
    private val clock: AgentProtocolHttpClock = AgentProtocolHttpClock.system(),
    private val receiptIdSource: AgentProtocolHttpReceiptIdSource = AgentProtocolHttpReceiptIdSource.randomUuid(),
) : AgentProtocolHttpTransport, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val activeCalls: MutableSet<Call> = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
    private val activeExchanges: MutableSet<AgentProtocolHttpExchangeFuture> =
        Collections.newSetFromMap(ConcurrentHashMap<AgentProtocolHttpExchangeFuture, Boolean>())
    private val deadlineScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        AgentProtocolHttpDaemonThreadFactory,
    )

    override fun exchange(request: AgentProtocolHttpExchangeRequest): CompletionStage<AgentProtocolHttpWireResponse> {
        val future = AgentProtocolHttpExchangeFuture()
        if (closed.get()) {
            future.reject("http-transport-closed", AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH, false)
            return future
        }
        activeExchanges.add(future)
        future.whenComplete { _, _ ->
            future.release()
            activeExchanges.remove(future)
        }
        if (closed.get()) {
            activeExchanges.remove(future)
            future.reject("http-transport-closed", AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH, false)
            return future
        }
        val dispatch = request.dispatch
        val now = clock.currentTimeMillis()
        try {
            requireApprovedDispatch(request, now)
        } catch (_: RuntimeException) {
            future.reject("http-dispatch-rejected", AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH, false)
            return future
        }
        val remaining = dispatch.invocation.deadlineAt - now
        val deadlineTask = try {
            deadlineScheduler.schedule(
                {
                    future.abortForDeadline()
                },
                remaining,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RuntimeException) {
            future.reject("http-transport-closed", AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH, false)
            return future
        }
        future.whenComplete { _, _ ->
            deadlineTask.cancel(false)
        }
        val credentialRequest = try {
            AgentProtocolHttpCredentialRequest(
                dispatch,
                request.wireRequest.httpMethod,
                dispatch.profile.resourceUri,
                request.wireRequest.bodyDigest,
                now,
            )
        } catch (_: RuntimeException) {
            future.reject("http-credential-request-rejected", AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH, false)
            return future
        }
        val credentialStage = try {
            requireNotNull(credentialProvider.acquire(credentialRequest))
        } catch (_: RuntimeException) {
            future.reject("http-credential-unavailable", AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH, false)
            return future
        }
        credentialStage.whenComplete { material, failure ->
            if (failure != null || material == null) {
                future.reject(
                    "http-credential-unavailable",
                    AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH,
                    false,
                )
            } else if (closed.get() || future.isDone) {
                material.close()
                if (closed.get()) {
                    future.reject(
                        "http-transport-closed",
                        AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH,
                        false,
                    )
                }
            } else {
                beginExchange(request, material, future)
            }
        }
        return future
    }

    private fun beginExchange(
        exchange: AgentProtocolHttpExchangeRequest,
        material: AgentProtocolHttpCredentialMaterial,
        future: AgentProtocolHttpExchangeFuture,
    ) {
        val dispatch = exchange.dispatch
        val startedAt = clock.currentTimeMillis()
        var client: OkHttpClient? = null
        try {
            requireCredentialMatches(dispatch, material)
            requireApprovedDispatch(exchange, startedAt)
            val endpoint = dispatch.profile.resourceUri
            val dns = AgentProtocolPinnedDns(endpoint.host, dispatch.networkResolution.addresses)
            val tracker = AgentProtocolHttpExchangeTracker(clock)
            val tls = pinnedTlsContext(
                material.tlsMaterial(),
                configuration.tlsIdentityKind,
                dispatch.profile.approvedTlsPeerIdentityDigest,
            )
            client = hardenedAgentProtocolClientBuilder(dns, configuration, remainingMillis(dispatch, startedAt))
                .sslSocketFactory(tls.context.socketFactory, tls.trustManager)
                .eventListener(tracker)
                .addNetworkInterceptor(
                    AgentProtocolBoundConnectionInterceptor(
                        dns,
                        configuration.tlsIdentityKind,
                        dispatch.profile.approvedTlsPeerIdentityDigest,
                        tracker,
                    ),
                )
                .build()
            val authenticatedHeaders = material.headersForTransport()
            val httpRequest = buildRequest(endpoint, exchange.wireRequest, authenticatedHeaders)
            material.close()
            val call = client.newCall(httpRequest)
            activeCalls.add(call)
            future.attach(call, tracker)
            if (closed.get() || future.isDone) {
                call.cancel()
                activeCalls.remove(call)
                closeClient(client)
                if (closed.get()) {
                    future.reject(
                        "http-transport-closed",
                        AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH,
                        false,
                    )
                }
                return
            }
            val callbackClient = client
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeCalls.remove(call)
                    closeClient(callbackClient)
                    handleNetworkFailure(dispatch, tracker, startedAt, future)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        handleResponse(dispatch, tracker, startedAt, response, future)
                    } catch (_: IOException) {
                        future.reject("http-response-read-failed", AgentProtocolHttpTransportOutcome.OUTCOME_UNKNOWN, true)
                    } catch (_: RuntimeException) {
                        future.reject("http-response-invalid", AgentProtocolHttpTransportOutcome.OUTCOME_UNKNOWN, true)
                    } finally {
                        activeCalls.remove(call)
                        response.close()
                        closeClient(callbackClient)
                    }
                }
            })
        } catch (_: RuntimeException) {
            material.close()
            client?.let(::closeClient)
            future.reject("http-transport-preparation-failed", AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH, false)
        }
    }

    private fun handleResponse(
        dispatch: AgentRemoteProtocolDispatchRequest,
        tracker: AgentProtocolHttpExchangeTracker,
        startedAt: Long,
        response: Response,
        future: AgentProtocolHttpExchangeFuture,
    ) {
        val maximumBody = min(
            configuration.limits.maximumBodyBytes,
            dispatch.invocation.maximumResponseBytes,
        )
        val capturedHeaders = try {
            captureBoundedHeaders(response.headers, configuration.limits)
        } catch (_: AgentProtocolHttpResponseLimitException) {
            recordRejectedResponse(
                dispatch,
                tracker,
                startedAt,
                response.code,
                headersComplete = false,
                bodyComplete = false,
                EMPTY_DIGEST,
                EMPTY_DIGEST,
                AgentProtocolHttpTransportOutcome.RESPONSE_LIMIT_REJECTED,
                future,
                "http-response-headers-limit",
            )
            return
        }
        val boundedBody = readBoundedResponse(response.body?.byteStream(), response.body?.contentLength(), maximumBody)
        val outcome = when {
            response.code in 300..399 -> AgentProtocolHttpTransportOutcome.REDIRECT_REJECTED
            !boundedBody.complete -> AgentProtocolHttpTransportOutcome.RESPONSE_LIMIT_REJECTED
            else -> AgentProtocolHttpTransportOutcome.RESPONSE
        }
        val completedAt = clock.currentTimeMillis()
        val evidence = try {
            exchangeEvidence(
                dispatch,
                tracker,
                startedAt,
                response.code,
                capturedHeaders.digest,
                sha256Bytes(boundedBody.bytes),
                headersComplete = true,
                bodyComplete = boundedBody.complete,
                outcome,
                completedAt,
            )
        } catch (_: RuntimeException) {
            future.reject("http-response-evidence-invalid", AgentProtocolHttpTransportOutcome.OUTCOME_UNKNOWN, true)
            return
        }
        val wireResponse = if (outcome == AgentProtocolHttpTransportOutcome.RESPONSE) {
            try {
                AgentProtocolHttpWireResponse(
                    response.code,
                    capturedHeaders.values,
                    boundedBody.bytes,
                    configuration.limits,
                )
            } catch (_: RuntimeException) {
                future.reject("http-response-invalid", AgentProtocolHttpTransportOutcome.OUTCOME_UNKNOWN, true)
                return
            }
        } else {
            null
        }
        recordEvidence(evidence, future) {
            when (outcome) {
                AgentProtocolHttpTransportOutcome.RESPONSE -> future.complete(requireNotNull(wireResponse))
                AgentProtocolHttpTransportOutcome.REDIRECT_REJECTED -> future.reject(
                    "http-redirect-rejected",
                    outcome,
                    true,
                )
                AgentProtocolHttpTransportOutcome.RESPONSE_LIMIT_REJECTED -> future.reject(
                    "http-response-body-limit",
                    outcome,
                    true,
                )
                else -> future.reject("http-response-invalid", AgentProtocolHttpTransportOutcome.OUTCOME_UNKNOWN, true)
            }
        }
    }

    private fun handleNetworkFailure(
        dispatch: AgentRemoteProtocolDispatchRequest,
        tracker: AgentProtocolHttpExchangeTracker,
        startedAt: Long,
        future: AgentProtocolHttpExchangeFuture,
    ) {
        val failure = future.classifyNetworkFailure(tracker)
        if (!failure.reachedPeer) {
            future.reject(failure.code, failure.outcome, false)
            return
        }
        val completedAt = clock.currentTimeMillis()
        val evidence = try {
            exchangeEvidence(
                dispatch,
                tracker,
                startedAt,
                0,
                EMPTY_DIGEST,
                EMPTY_DIGEST,
                headersComplete = false,
                bodyComplete = false,
                failure.outcome,
                completedAt,
            )
        } catch (_: RuntimeException) {
            future.reject(failure.code, failure.outcome, true)
            return
        }
        recordEvidence(evidence, future) {
            future.reject(failure.code, failure.outcome, true)
        }
    }

    private fun recordRejectedResponse(
        dispatch: AgentRemoteProtocolDispatchRequest,
        tracker: AgentProtocolHttpExchangeTracker,
        startedAt: Long,
        statusCode: Int,
        headersComplete: Boolean,
        bodyComplete: Boolean,
        headersDigest: String,
        bodyDigest: String,
        outcome: AgentProtocolHttpTransportOutcome,
        future: AgentProtocolHttpExchangeFuture,
        code: String,
    ) {
        val completedAt = clock.currentTimeMillis()
        val evidence = try {
            exchangeEvidence(
                dispatch,
                tracker,
                startedAt,
                statusCode,
                headersDigest,
                bodyDigest,
                headersComplete,
                bodyComplete,
                outcome,
                completedAt,
            )
        } catch (_: RuntimeException) {
            future.reject("http-response-evidence-invalid", AgentProtocolHttpTransportOutcome.OUTCOME_UNKNOWN, true)
            return
        }
        recordEvidence(evidence, future) { future.reject(code, outcome, true) }
    }

    private fun exchangeEvidence(
        dispatch: AgentRemoteProtocolDispatchRequest,
        tracker: AgentProtocolHttpExchangeTracker,
        startedAt: Long,
        statusCode: Int,
        headersDigest: String,
        bodyDigest: String,
        headersComplete: Boolean,
        bodyComplete: Boolean,
        outcome: AgentProtocolHttpTransportOutcome,
        completedAt: Long,
    ): AgentProtocolHttpExchangeEvidence {
        val connectedAddressDigest = tracker.connectedAddressDigest.get()
            ?: throw IllegalStateException("Agent protocol connected address evidence is unavailable.")
        val tlsIdentityDigest = tracker.tlsIdentityDigest.get()
            ?: throw IllegalStateException("Agent protocol TLS identity evidence is unavailable.")
        val requestHeadersAt = tracker.requestHeadersStartedAt
        val responseHeadersAt = tracker.responseHeadersReceivedAt
        val receipt = AgentRemoteTransportReceipt(
            receiptIdSource.nextId(),
            dispatch,
            connectedAddressDigest,
            tlsIdentityDigest,
            true,
            completedAt,
        )
        return AgentProtocolHttpExchangeEvidence(
            receipt,
            configuration.tlsIdentityKind,
            outcome,
            statusCode,
            headersDigest,
            bodyDigest,
            headersComplete,
            bodyComplete,
            startedAt,
            requestHeadersAt,
            responseHeadersAt,
            completedAt,
        )
    }

    private fun recordEvidence(
        evidence: AgentProtocolHttpExchangeEvidence,
        future: AgentProtocolHttpExchangeFuture,
        afterRecord: () -> Unit,
    ) {
        val stage = try {
            requireNotNull(evidenceRecorder.record(evidence))
        } catch (_: RuntimeException) {
            future.reject("http-evidence-record-failed", AgentProtocolHttpTransportOutcome.OUTCOME_UNKNOWN, true)
            return
        }
        stage.whenComplete { _, failure ->
            if (failure != null) {
                future.reject("http-evidence-record-failed", AgentProtocolHttpTransportOutcome.OUTCOME_UNKNOWN, true)
            } else {
                afterRecord()
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            activeExchanges.forEach(AgentProtocolHttpExchangeFuture::abortForClose)
            activeExchanges.clear()
            activeCalls.forEach(Call::cancel)
            activeCalls.clear()
            deadlineScheduler.shutdownNow()
        }
    }
}

internal fun hardenedAgentProtocolClientBuilder(
    dns: Dns,
    configuration: AgentProtocolOkHttpTransportConfiguration,
    remainingMillis: Long,
): OkHttpClient.Builder {
    val callTimeout = min(configuration.callTimeoutMillis, remainingMillis).coerceAtLeast(1L)
    return OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .proxyAuthenticator(Authenticator.NONE)
        .authenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .dns(dns)
        .connectionSpecs(Collections.singletonList(ConnectionSpec.MODERN_TLS))
        .connectTimeout(min(configuration.connectTimeoutMillis, callTimeout), TimeUnit.MILLISECONDS)
        .readTimeout(min(configuration.readTimeoutMillis, callTimeout), TimeUnit.MILLISECONDS)
        .writeTimeout(min(configuration.writeTimeoutMillis, callTimeout), TimeUnit.MILLISECONDS)
        .callTimeout(callTimeout, TimeUnit.MILLISECONDS)
}

private fun requireApprovedDispatch(request: AgentProtocolHttpExchangeRequest, atTime: Long) {
    val dispatch = request.dispatch
    val endpoint = dispatch.profile.resourceUri
    require(endpoint.scheme.equals("https", ignoreCase = true) && !endpoint.host.isNullOrBlank()) {
        "Agent protocol endpoint must use HTTPS."
    }
    require(endpoint.rawUserInfo == null && endpoint.rawFragment == null) {
        "Agent protocol endpoint contains forbidden URI components."
    }
    require(endpoint == dispatch.networkResolution.targetUri && dispatch.profile.maximumRedirects == 0) {
        "Agent protocol endpoint differs from the approved resolution."
    }
    require(atTime >= dispatch.requestedAt && atTime < dispatch.invocation.deadlineAt) {
        "Agent protocol dispatch is outside its authorized window."
    }
    dispatch.networkResolution.requirePublicAndCurrent(dispatch.networkRequest, atTime)
    dispatch.credentialLease.requireCurrentFor(dispatch.credentialRequest, atTime)
}

private fun remainingMillis(dispatch: AgentRemoteProtocolDispatchRequest, atTime: Long): Long =
    (dispatch.invocation.deadlineAt - atTime).also { remaining ->
        require(remaining > 0L) { "Agent protocol dispatch deadline elapsed." }
    }

private fun buildRequest(
    endpoint: URI,
    wire: ai.icen.fw.agent.adapter.http.AgentProtocolHttpWireRequest,
    authenticatedHeaders: Map<String, String>,
): Request {
    val builder = Request.Builder().url(endpoint.toASCIIString())
    wire.headers.asMap().forEach { (name, value) ->
        require(name.lowercase(Locale.ROOT) !in FORBIDDEN_CODEC_HEADERS) {
            "Codec request contains a transport-owned header."
        }
        builder.header(name, value)
    }
    authenticatedHeaders.forEach { (name, value) ->
        require(wire.headers.value(name) == null) { "Credential header collides with a codec header." }
        builder.header(name, value)
    }
    return when (wire.httpMethod) {
        AgentProtocolHttpMethod.GET -> {
            require(wire.bodySizeBytes == 0) { "Agent protocol GET request must not contain a body." }
            builder.get().build()
        }
        AgentProtocolHttpMethod.POST -> {
            val mediaType = wire.headers.value("content-type")
                ?.substringBefore(';')
                ?.trim()
                ?.toMediaType()
                ?: throw IllegalArgumentException("Agent protocol POST content type is missing.")
            builder.post(AgentProtocolOneShotRequestBody(mediaType, wire.body())).build()
        }
    }
}

private class AgentProtocolOneShotRequestBody(
    private val mediaType: okhttp3.MediaType,
    bytes: ByteArray,
) : RequestBody() {
    private val snapshot = bytes.copyOf()

    override fun contentType(): okhttp3.MediaType = mediaType

    override fun contentLength(): Long = snapshot.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        sink.write(snapshot)
    }

    override fun isOneShot(): Boolean = true
}

private class AgentProtocolBoundConnectionInterceptor(
    private val dns: AgentProtocolPinnedDns,
    private val identityKind: AgentProtocolHttpTlsIdentityKind,
    private val expectedIdentityDigest: String,
    private val tracker: AgentProtocolHttpExchangeTracker,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val connection = chain.connection()
            ?: throw IOException("Agent protocol connection evidence is unavailable.")
        val addressDigest = dns.approvedDigest(connection.route().socketAddress.address)
            ?: throw IOException("Agent protocol connected outside the approved address set.")
        val leaf = connection.handshake()?.peerCertificates?.firstOrNull() as? X509Certificate
            ?: throw IOException("Agent protocol TLS peer certificate is unavailable.")
        val identityDigest = tlsIdentityDigest(leaf, identityKind)
        if (identityDigest != expectedIdentityDigest) {
            throw IOException("Agent protocol TLS peer identity drifted.")
        }
        tracker.connectedAddressDigest.set(addressDigest)
        tracker.tlsIdentityDigest.set(identityDigest)
        return chain.proceed(chain.request())
    }
}

private class AgentProtocolHttpExchangeTracker(
    private val clock: AgentProtocolHttpClock,
) : EventListener() {
    val connectedAddressDigest = AtomicReference<String?>()
    val tlsIdentityDigest = AtomicReference<String?>()
    @Volatile var requestHeadersStartedAt: Long = -1L
    @Volatile var responseHeadersReceivedAt: Long = -1L

    override fun requestHeadersStart(call: Call) {
        requestHeadersStartedAt = clock.currentTimeMillis()
    }

    override fun responseHeadersStart(call: Call) {
        responseHeadersReceivedAt = clock.currentTimeMillis()
    }

    fun requestMayHaveReachedPeer(): Boolean = requestHeadersStartedAt >= 0L
}

private class AgentProtocolHttpExchangeFuture : CompletableFuture<AgentProtocolHttpWireResponse>() {
    private val call = AtomicReference<Call?>()
    private val tracker = AtomicReference<AgentProtocolHttpExchangeTracker?>()
    private val cancellationRequested = AtomicBoolean(false)

    fun attach(call: Call, tracker: AgentProtocolHttpExchangeTracker) {
        this.tracker.set(tracker)
        this.call.set(call)
        if (isDone) {
            call.cancel()
            release()
        }
    }

    fun release() {
        call.set(null)
        tracker.set(null)
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        if (isDone) return false
        cancellationRequested.set(true)
        call.get()?.cancel()
        val reached = tracker.get()?.requestMayHaveReachedPeer() == true
        return completeExceptionally(
            AgentProtocolHttpTransportException(
                if (reached) "http-cancel-outcome-unknown" else "http-cancelled-before-dispatch",
                if (reached) {
                    AgentProtocolHttpTransportOutcome.CANCELLED_OUTCOME_UNKNOWN
                } else {
                    AgentProtocolHttpTransportOutcome.CANCELLED_BEFORE_DISPATCH
                },
                reached,
            ),
        )
    }

    fun abortForDeadline() {
        if (isDone) return
        call.get()?.cancel()
        val reached = tracker.get()?.requestMayHaveReachedPeer() == true
        reject(
            if (reached) "http-deadline-outcome-unknown" else "http-deadline-before-dispatch",
            if (reached) AgentProtocolHttpTransportOutcome.OUTCOME_UNKNOWN
            else AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH,
            reached,
        )
    }

    fun abortForClose() {
        if (isDone) return
        call.get()?.cancel()
        val reached = tracker.get()?.requestMayHaveReachedPeer() == true
        reject(
            if (reached) "http-close-outcome-unknown" else "http-transport-closed",
            if (reached) AgentProtocolHttpTransportOutcome.OUTCOME_UNKNOWN
            else AgentProtocolHttpTransportOutcome.REJECTED_BEFORE_DISPATCH,
            reached,
        )
    }

    fun classifyNetworkFailure(tracker: AgentProtocolHttpExchangeTracker): AgentProtocolHttpNetworkFailure {
        val reached = tracker.requestMayHaveReachedPeer()
        val cancelled = cancellationRequested.get()
        return AgentProtocolHttpNetworkFailure(
            code = when {
                cancelled && reached -> "http-cancel-outcome-unknown"
                cancelled -> "http-cancelled-before-dispatch"
                reached -> "http-network-outcome-unknown"
                else -> "http-connect-failed"
            },
            outcome = when {
                cancelled && reached -> AgentProtocolHttpTransportOutcome.CANCELLED_OUTCOME_UNKNOWN
                cancelled -> AgentProtocolHttpTransportOutcome.CANCELLED_BEFORE_DISPATCH
                reached -> AgentProtocolHttpTransportOutcome.OUTCOME_UNKNOWN
                else -> AgentProtocolHttpTransportOutcome.CONNECT_FAILED
            },
            reachedPeer = reached,
        )
    }

    fun reject(code: String, outcome: AgentProtocolHttpTransportOutcome, reached: Boolean): Boolean =
        completeExceptionally(AgentProtocolHttpTransportException(code, outcome, reached))
}

private class AgentProtocolHttpNetworkFailure(
    val code: String,
    val outcome: AgentProtocolHttpTransportOutcome,
    val reachedPeer: Boolean,
)

internal class AgentProtocolHttpCapturedHeaders(
    val values: Map<String, String>,
    val digest: String,
)

internal fun captureBoundedHeaders(
    headers: Headers,
    limits: AgentProtocolHttpCodecLimits,
): AgentProtocolHttpCapturedHeaders {
    if (headers.size > limits.maximumHeaderCount) throw AgentProtocolHttpResponseLimitException()
    val values = LinkedHashMap<String, String>()
    val evidenceFields = ArrayList<String>()
    for (index in 0 until headers.size) {
        val name = headers.name(index).lowercase(Locale.ROOT)
        val value = headers.value(index)
        if (value.codePointCount(0, value.length) > limits.maximumHeaderValueCodePoints ||
            value.any { character -> character.code !in 0x20..0x7e }
        ) throw AgentProtocolHttpResponseLimitException()
        evidenceFields.add(name)
        evidenceFields.add(sha256Bytes(value.toByteArray(Charsets.UTF_8)))
        if (name !in OMITTED_RESPONSE_HEADERS) {
            val combined = values[name]?.let { previous -> "$previous, $value" } ?: value
            if (combined.codePointCount(0, combined.length) > limits.maximumHeaderValueCodePoints) {
                throw AgentProtocolHttpResponseLimitException()
            }
            values[name] = combined
        }
    }
    val digest = digestFields(
        "flowweft.agent.http.response-headers.v1",
        *evidenceFields.chunked(2)
            .sortedWith(compareBy<List<String>> { fields -> fields[0] }.thenBy { fields -> fields[1] })
            .flatten()
            .toTypedArray(),
    )
    return AgentProtocolHttpCapturedHeaders(Collections.unmodifiableMap(values), digest)
}

internal class AgentProtocolHttpBoundedBody(
    val bytes: ByteArray,
    val complete: Boolean,
)

internal fun readBoundedResponse(
    input: InputStream?,
    declaredLength: Long?,
    maximumBytes: Int,
): AgentProtocolHttpBoundedBody {
    if (input == null) return AgentProtocolHttpBoundedBody(ByteArray(0), true)
    if (declaredLength != null && declaredLength > maximumBytes.toLong()) {
        return AgentProtocolHttpBoundedBody(ByteArray(0), false)
    }
    val output = ByteArrayOutputStream(min(maximumBytes, 8_192))
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (total > maximumBytes - read) {
            val remaining = maximumBytes - total
            if (remaining > 0) output.write(buffer, 0, remaining)
            return AgentProtocolHttpBoundedBody(output.toByteArray(), false)
        }
        output.write(buffer, 0, read)
        total += read
    }
    return AgentProtocolHttpBoundedBody(output.toByteArray(), true)
}

private fun closeClient(client: OkHttpClient) {
    client.connectionPool.evictAll()
    client.dispatcher.executorService.shutdown()
}

internal class AgentProtocolHttpResponseLimitException : RuntimeException()

private object AgentProtocolHttpDaemonThreadFactory : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread = Thread(runnable, "flowweft-agent-http-deadline").apply {
        isDaemon = true
    }
}

private val OMITTED_RESPONSE_HEADERS = setOf("set-cookie", "proxy-authenticate", "www-authenticate")
private val FORBIDDEN_CODEC_HEADERS = setOf(
    "authorization",
    "proxy-authorization",
    "cookie",
    "host",
    "content-length",
    "transfer-encoding",
    "connection",
    "proxy-connection",
)
private val EMPTY_DIGEST = sha256Bytes(ByteArray(0))
