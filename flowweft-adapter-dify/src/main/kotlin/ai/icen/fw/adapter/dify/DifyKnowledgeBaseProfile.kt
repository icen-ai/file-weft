package ai.icen.fw.adapter.dify

import ai.icen.fw.core.id.Identifier
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Arrays
import java.util.Collections
import java.util.UUID

/**
 * Explicit Dify Service API matrix. FlowWeft 1.0 intentionally supports only
 * the currently documented 1.14 API family; older deprecated update routes
 * and unverified future major versions are never probed or guessed at runtime.
 */
enum class DifyApiCompatibility(
    val supportedVersionRange: String,
) {
    DIFY_1_14_X("1.14.x"),
}

enum class DifyIndexingTechnique(val wireValue: String) {
    HIGH_QUALITY("high_quality"),
    ECONOMY("economy"),
}

enum class DifyDocumentForm(val wireValue: String) {
    TEXT("text_model"),
    HIERARCHICAL("hierarchical_model"),
    QUESTION_ANSWER("qa_model"),
}

/** Fixed, server-side indexing choices; connector request attributes cannot override them. */
class DifyDocumentIndexingOptions @JvmOverloads constructor(
    val indexingTechnique: DifyIndexingTechnique = DifyIndexingTechnique.HIGH_QUALITY,
    val documentForm: DifyDocumentForm = DifyDocumentForm.TEXT,
    documentLanguage: String = "English",
) {
    val documentLanguage: String = safeText(documentLanguage, "Dify document language", 64)
}

/**
 * Administrator-owned connector profile. Endpoint, dataset, source origins,
 * compatibility and limits are immutable and cannot be supplied by a document
 * request, browser, model, or connector attributes. The configured dataset is
 * exclusively owned by [dedicatedTenantId]; sharing it between tenants is not
 * a supported deployment topology.
 */
class DifyKnowledgeBaseProfile @JvmOverloads constructor(
    profileId: String,
    val dedicatedTenantId: Identifier,
    apiBaseUri: URI,
    datasetId: String,
    val sourceTrustPolicy: DifySourceTrustPolicy,
    /** Allows an explicitly administrator-configured private Dify API endpoint. */
    val allowPrivateApiAddresses: Boolean = false,
    val compatibility: DifyApiCompatibility = DifyApiCompatibility.DIFY_1_14_X,
    val indexing: DifyDocumentIndexingOptions = DifyDocumentIndexingOptions(),
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(30),
    val maximumApiResponseBytes: Long = 1024L * 1024L,
    val maximumSourceBytes: Long = 50L * 1024L * 1024L,
    val maximumReadAttempts: Int = 3,
    val initialReadRetryDelay: Duration = Duration.ofMillis(100),
    val projectionLeaseDuration: Duration = Duration.ofMinutes(2),
) {
    val profileId: String = safeText(profileId, "Dify profile id", 128)
    val apiBaseUri: URI = normalizeApiBaseUri(apiBaseUri)
    val datasetId: String = canonicalUuid(datasetId, "Dify dataset id")
    /** Credential-free identity of the exact API authority, dataset and compatibility contract. */
    val targetBindingDigest: String = computeTargetBindingDigest(this.apiBaseUri, this.datasetId, compatibility)

    init {
        safeText(dedicatedTenantId.value, "Dify dedicated tenant id", 512)
        requireBoundedDuration(connectTimeout, Duration.ofMinutes(1), "Dify connect timeout")
        requireBoundedDuration(readTimeout, Duration.ofMinutes(10), "Dify read timeout")
        require(maximumApiResponseBytes in 1..MAX_API_RESPONSE_BYTES) {
            "Dify maximum API response bytes must be between 1 and $MAX_API_RESPONSE_BYTES."
        }
        require(maximumSourceBytes in 1..MAX_SOURCE_BYTES) {
            "Dify maximum source bytes must be between 1 and $MAX_SOURCE_BYTES."
        }
        require(maximumReadAttempts in 1..MAX_READ_ATTEMPTS) {
            "Dify maximum read attempts must be between 1 and $MAX_READ_ATTEMPTS."
        }
        requireBoundedDuration(initialReadRetryDelay, Duration.ofSeconds(2), "Dify initial read retry delay")
        requireBoundedDuration(projectionLeaseDuration, Duration.ofMinutes(10), "Dify projection lease duration")
    }

    private companion object {
        const val MAX_API_RESPONSE_BYTES = 4L * 1024L * 1024L
        const val MAX_SOURCE_BYTES = 1024L * 1024L * 1024L
        const val MAX_READ_ATTEMPTS = 5
    }
}

/** Loads a fresh credential for one request. Implementations must never log it. */
interface DifyApiKeyProvider {
    /** Returns a fresh caller-owned array. The adapter clears it immediately after building the request. */
    fun loadApiKey(): CharArray
}

/** Small convenience provider for hosts that already resolved a secret reference. */
class StaticDifyApiKeyProvider(apiKey: CharArray) : DifyApiKeyProvider, AutoCloseable {
    private val monitor = Any()
    private var secret: CharArray? = copyValidatedApiKey(apiKey)

    constructor(apiKey: String) : this(apiKey.toCharArray())

    override fun loadApiKey(): CharArray = synchronized(monitor) {
        checkNotNull(secret) { "Dify API key provider is closed." }.copyOf()
    }

    override fun close() = synchronized(monitor) {
        secret?.let { Arrays.fill(it, '\u0000') }
        secret = null
    }
}

internal fun validatedApiKey(value: CharArray): CharArray {
    require(value.isNotEmpty() && value.any { !it.isWhitespace() }) { "Dify API key must not be blank." }
    require(value.size <= 4096) { "Dify API key is unreasonably long." }
    require(value.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) {
        "Dify API key contains unsafe characters."
    }
    require(isWellFormedUtf16(value)) { "Dify API key contains malformed Unicode." }
    return value
}

private fun copyValidatedApiKey(value: CharArray): CharArray {
    val copy = value.copyOf()
    return try {
        validatedApiKey(copy)
    } catch (failure: RuntimeException) {
        Arrays.fill(copy, '\u0000')
        throw failure
    }
}

internal fun canonicalUuid(value: String, label: String): String {
    val canonical = try {
        UUID.fromString(value).toString()
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("$label must be a canonical UUID.", failure)
    }
    require(canonical == value.lowercase()) { "$label must be a canonical UUID." }
    return canonical
}

internal fun safeText(value: String, label: String, maximumLength: Int): String {
    require(value.isNotBlank()) { "$label must not be blank." }
    require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters." }
    require(value.none { character ->
        Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
    }) { "$label must not contain unsafe characters." }
    require(isWellFormedUtf16(value)) { "$label must contain well-formed Unicode." }
    return value
}

internal fun safeOptionalText(value: String?, label: String, maximumLength: Int): String? =
    value?.let { safeText(it, label, maximumLength) }

internal fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun normalizeApiBaseUri(value: URI): URI {
    require(value.isAbsolute) { "Dify API base URI must be absolute." }
    require(value.scheme.equals("https", ignoreCase = true)) { "Dify API base URI must use HTTPS." }
    require(!value.host.isNullOrBlank()) { "Dify API base URI must contain a host." }
    require(value.port == -1 || value.port in 1..65535) { "Dify API base URI port is invalid." }
    require(value.rawUserInfo == null) { "Dify API base URI must not contain user information." }
    require(value.rawQuery == null && value.rawFragment == null) {
        "Dify API base URI must not contain a query or fragment."
    }
    require(value.normalize().rawPath == value.rawPath) { "Dify API base URI must not contain dot segments." }
    val rawPath = value.rawPath.orEmpty()
    require(!hasEncodedPathConfusion(rawPath)) {
        "Dify API base URI must not contain encoded traversal or separators."
    }
    val path = rawPath.trimEnd('/').ifEmpty { "/v1" }
    require("//" !in path) { "Dify API base URI must not contain empty path segments." }
    require(path.endsWith("/v1")) { "Dify API base URI must identify the Service API /v1 root." }
    return URI("https", null, value.host.lowercase(), effectivePort(value), path, null, null)
}

private fun effectivePort(uri: URI): Int = if (uri.port == -1) 443 else uri.port

private fun computeTargetBindingDigest(
    apiBaseUri: URI,
    datasetId: String,
    compatibility: DifyApiCompatibility,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf("flowweft.dify.target.v1", apiBaseUri.toASCIIString(), datasetId, compatibility.name).forEach { value ->
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return "sha256:" + digest.digest().joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun requireBoundedDuration(value: Duration, maximum: Duration, label: String) {
    val atLeastOneMillisecond = !value.isNegative && !value.isZero &&
        (value.seconds > 0L || value.nano >= 1_000_000)
    require(atLeastOneMillisecond) { "$label must be at least one millisecond." }
    require(value <= maximum) { "$label must not exceed ${maximum.seconds} seconds." }
}

private fun isWellFormedUtf16(value: CharSequence): Boolean {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                index += 2
            }
            Character.isLowSurrogate(character) -> return false
            else -> index++
        }
    }
    return true
}

private fun isWellFormedUtf16(value: CharArray): Boolean {
    var index = 0
    while (index < value.size) {
        val character = value[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 >= value.size || !Character.isLowSurrogate(value[index + 1])) return false
                index += 2
            }
            Character.isLowSurrogate(character) -> return false
            else -> index++
        }
    }
    return true
}
