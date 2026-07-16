package ai.icen.fw.spi.retention

import ai.icen.fw.core.id.Identifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Collections
import java.util.LinkedHashMap

/** Provider-neutral external surfaces that must be purged after tombstoning. */
enum class SecureDeletionTarget {
    INDEX,
    OBJECT_STORAGE,
}

/**
 * Stable, tenant-bound request for an idempotent external deletion.
 *
 * Providers must delete every projection/generation represented by the
 * resource revision and must never reconstruct tenant identity from an
 * untrusted path or provider receipt.
 */
class SecureDeletionProviderRequest(
    val tenantId: Identifier,
    val planId: Identifier,
    val tombstoneId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val resourceRevision: Long,
    val target: SecureDeletionTarget,
    val idempotencyKey: String,
    val timeout: Duration,
) {
    val bindingDigest: String

    init {
        require(resourceType.isNotBlank()) { "Secure-deletion provider resource type must not be blank." }
        require(resourceRevision >= 0) { "Secure-deletion provider resource revision must not be negative." }
        require(idempotencyKey.isNotBlank()) { "Secure-deletion provider idempotency key must not be blank." }
        require(!timeout.isNegative && !timeout.isZero) { "Secure-deletion provider timeout must be positive." }
        bindingDigest = bindingDigest(
            tenantId,
            planId,
            tombstoneId,
            resourceType,
            resourceId,
            resourceRevision,
            target,
            idempotencyKey,
        )
    }

    companion object {
        /** Stable resource/request identity; timeout is operational policy and deliberately excluded. */
        @JvmStatic
        fun bindingDigest(
            tenantId: Identifier,
            planId: Identifier,
            tombstoneId: Identifier,
            resourceType: String,
            resourceId: Identifier,
            resourceRevision: Long,
            target: SecureDeletionTarget,
            idempotencyKey: String,
        ): String {
            require(resourceType.isNotBlank()) { "Secure-deletion provider resource type must not be blank." }
            require(resourceRevision >= 0) { "Secure-deletion provider resource revision must not be negative." }
            require(idempotencyKey.isNotBlank()) { "Secure-deletion provider idempotency key must not be blank." }
            return SecureDeletionDigest("flowweft.secure-deletion.provider-request.v1")
                .add(tenantId.value)
                .add(planId.value)
                .add(tombstoneId.value)
                .add(resourceType)
                .add(resourceId.value)
                .add(resourceRevision)
                .add(target.name)
                .add(idempotencyKey)
                .finish()
        }
    }
}

enum class SecureDeletionProviderStatus {
    /** The provider supplied positive evidence that the target is absent. */
    VERIFIED_ABSENT,

    /** The provider accepted deletion, but absence still needs reconciliation. */
    ACCEPTED_UNVERIFIED,

    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
}

/**
 * Redacted provider receipt persisted by FlowWeft for replay and diagnosis.
 * Receipt references and evidence must never contain credentials, signed URLs,
 * raw content, request headers, or vendor access tokens.
 */
class SecureDeletionProviderReceipt @JvmOverloads constructor(
    val providerId: String,
    val target: SecureDeletionTarget,
    val status: SecureDeletionProviderStatus,
    val requestBindingDigest: String,
    val receiptReference: String? = null,
    val message: String? = null,
    evidence: Map<String, String> = emptyMap(),
) {
    val evidence: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(evidence))

    init {
        require(providerId.isNotBlank()) { "Secure-deletion provider id must not be blank." }
        require(requestBindingDigest.length == 64 && requestBindingDigest.all { character ->
            character in '0'..'9' || character in 'a'..'f'
        }) { "Secure-deletion receipt request binding digest is invalid." }
        require(receiptReference == null || receiptReference.isNotBlank()) {
            "Secure-deletion receipt reference must not be blank when provided."
        }
        require(message == null || message.isNotBlank()) {
            "Secure-deletion provider message must not be blank when provided."
        }
        require(this.evidence.keys.none { it.isBlank() } && this.evidence.values.none { it.isBlank() }) {
            "Secure-deletion receipt evidence keys and values must not be blank."
        }
        require(
            status == SecureDeletionProviderStatus.RETRYABLE_FAILURE ||
                status == SecureDeletionProviderStatus.PERMANENT_FAILURE ||
                receiptReference != null
        ) {
            "Successful or accepted deletion evidence requires an opaque receipt reference."
        }
    }

    fun isVerifiedAbsent(): Boolean = status == SecureDeletionProviderStatus.VERIFIED_ABSENT
}

/**
 * Vendor-neutral deletion boundary. Implementations are idempotent for
 * [SecureDeletionProviderRequest.idempotencyKey] and must enforce the supplied
 * timeout for every remote call. A provider may represent a
 * composite backend, but FlowWeft requires exactly one configured provider for
 * each [SecureDeletionTarget] so missing deletion coverage never succeeds.
 */
interface SecureDeletionProvider {
    fun providerId(): String

    fun target(): SecureDeletionTarget

    fun requestDeletion(request: SecureDeletionProviderRequest): SecureDeletionProviderReceipt

    /**
     * Reconciles an accepted but unverified request. Returning
     * ACCEPTED_UNVERIFIED keeps the FlowWeft deletion open and retryable.
     */
    fun reconcileDeletion(
        request: SecureDeletionProviderRequest,
        previousReceipt: SecureDeletionProviderReceipt,
    ): SecureDeletionProviderReceipt
}

private class SecureDeletionDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(domain)
    }

    fun add(value: String): SecureDeletionDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): SecureDeletionDigest = add(value.toString())

    fun finish(): String {
        val alphabet = "0123456789abcdef"
        val bytes = digest.digest()
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }
}
