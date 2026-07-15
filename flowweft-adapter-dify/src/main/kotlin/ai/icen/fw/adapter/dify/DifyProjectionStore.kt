package ai.icen.fw.adapter.dify

import ai.icen.fw.core.id.Identifier
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * Durable tenant, resource and configured-target identity for one Dify projection.
 * [targetBindingDigest] prevents a reused profile id from carrying document ids
 * across a different API authority, dataset, or compatibility contract.
 */
data class DifyProjectionKey(
    val tenantId: Identifier,
    val businessId: Identifier,
    val connectorProfileId: String,
    val datasetId: String,
    val targetBindingDigest: String,
) {
    init {
        safeText(tenantId.value, "Dify projection tenant id", 512)
        safeText(businessId.value, "Dify projection business id", 512)
        safeText(connectorProfileId, "Dify connector profile id", 128)
        canonicalUuid(datasetId, "Dify projection dataset id")
        canonicalSha256(targetBindingDigest, "Dify target binding digest")
    }
}

enum class DifyProjectionOperation {
    CREATE,
    UPDATE,
    REMOVE,
}

enum class DifyProjectionIndexState {
    CLAIMED,
    INDEXING,
    AVAILABLE,
    FAILED,
    REMOVAL_ACCEPTED,
    API_ABSENT,
    UNKNOWN,
}

/** Immutable CAS snapshot; revision changes on every durable transition. */
class DifyProjectionSnapshot(
    val key: DifyProjectionKey,
    projectionId: String,
    externalId: String,
    val generation: Long,
    val revision: Long,
    val operation: DifyProjectionOperation,
    val indexState: DifyProjectionIndexState,
    documentId: String?,
    batchId: String?,
    sourceHash: String,
    idempotencyKeyDigest: String,
    operationFingerprint: String,
) {
    val projectionId: String = canonicalUuid(projectionId, "Dify projection id")
    val externalId: String = safeText(externalId, "Dify projection external id", 512)
    val documentId: String? = documentId?.let { canonicalUuid(it, "Dify document id") }
    val batchId: String? = safeOptionalText(batchId, "Dify indexing batch id", 128)
    val sourceHash: String = canonicalSha256(sourceHash, "Dify projection source hash")
    val idempotencyKeyDigest: String = canonicalSha256(idempotencyKeyDigest, "Dify idempotency key digest")
    val operationFingerprint: String = canonicalSha256(operationFingerprint, "Dify operation fingerprint")
    /** Digest of every field that may influence a remote operation or exact CAS transition. */
    val bindingDigest: String = projectionBindingDigest(
        key,
        this.projectionId,
        this.externalId,
        generation,
        revision,
        operation,
        indexState,
        this.documentId,
        this.batchId,
        this.sourceHash,
        this.idempotencyKeyDigest,
        this.operationFingerprint,
    )

    init {
        require(generation > 0L) { "Dify projection generation must be positive." }
        require(revision > 0L) { "Dify projection revision must be positive." }
        require(externalId == DifyProjectionExternalIds.create(this.projectionId, generation)) {
            "Dify projection external id must bind its projection and generation."
        }
        if (operation == DifyProjectionOperation.UPDATE || operation == DifyProjectionOperation.REMOVE) {
            require(this.documentId != null) { "Dify update and removal projections require a document id." }
        }
        if (
            indexState == DifyProjectionIndexState.INDEXING ||
            indexState == DifyProjectionIndexState.AVAILABLE ||
            indexState == DifyProjectionIndexState.FAILED ||
            indexState == DifyProjectionIndexState.REMOVAL_ACCEPTED ||
            indexState == DifyProjectionIndexState.API_ABSENT
        ) {
            require(this.documentId != null) {
                "An observed Dify projection state requires an exact document id."
            }
        }
        if (indexState == DifyProjectionIndexState.INDEXING) {
            require(this.documentId != null && this.batchId != null) {
                "An indexing Dify projection requires document and batch ids."
            }
            require(operation != DifyProjectionOperation.REMOVE) {
                "A Dify removal projection cannot enter indexing state."
            }
        }
        if (indexState == DifyProjectionIndexState.REMOVAL_ACCEPTED) {
            require(operation == DifyProjectionOperation.REMOVE) {
                "Dify removal acceptance requires a removal operation."
            }
        }
        if (operation == DifyProjectionOperation.REMOVE) {
            require(
                indexState == DifyProjectionIndexState.CLAIMED ||
                    indexState == DifyProjectionIndexState.REMOVAL_ACCEPTED ||
                    indexState == DifyProjectionIndexState.API_ABSENT ||
                    indexState == DifyProjectionIndexState.UNKNOWN
            ) {
                "A Dify removal projection entered an invalid state."
            }
        }
    }
}

object DifyProjectionExternalIds {
    @JvmStatic
    fun create(projectionId: String, generation: Long): String {
        val canonicalProjectionId = canonicalUuid(projectionId, "Dify projection id")
        require(generation > 0L) { "Dify projection generation must be positive." }
        return "dify:v1:$canonicalProjectionId:$generation"
    }
}

class DifyProjectionLease(
    val snapshot: DifyProjectionSnapshot,
    leaseToken: String,
    val expiresAtEpochMilli: Long,
) {
    val leaseToken: String = safeText(leaseToken, "Dify projection lease token", 256)

    init {
        require(expiresAtEpochMilli > 0L) { "Dify projection lease expiry must be positive." }
        require(snapshot.indexState == DifyProjectionIndexState.CLAIMED) {
            "A Dify projection lease requires a claimed snapshot."
        }
    }
}

enum class DifyProjectionClaimDisposition {
    ACQUIRED,
    REPLAY,
    IN_PROGRESS,
    CONFLICT,
    OUTCOME_UNKNOWN,
    SUPERSEDED,
    NOT_FOUND,
}

class DifyProjectionClaim(
    val disposition: DifyProjectionClaimDisposition,
    val snapshot: DifyProjectionSnapshot?,
    val lease: DifyProjectionLease?,
) {
    init {
        require((disposition == DifyProjectionClaimDisposition.ACQUIRED) == (lease != null)) {
            "Only an acquired Dify projection claim may carry a lease."
        }
        require(
            lease == null ||
                snapshot?.bindingDigest == lease.snapshot.bindingDigest
        ) {
            "Dify projection claim snapshot must exactly bind every leased field."
        }
        require(disposition == DifyProjectionClaimDisposition.NOT_FOUND || snapshot != null) {
            "A Dify projection claim must carry its bound snapshot unless it was not found."
        }
        require(disposition != DifyProjectionClaimDisposition.NOT_FOUND || snapshot == null) {
            "A missing Dify projection claim must not carry an unrelated snapshot."
        }
    }
}

class DifySyncClaimRequest(
    val key: DifyProjectionKey,
    sourceHash: String,
    idempotencyKeyDigest: String,
    operationFingerprint: String,
    val leaseDuration: Duration,
) {
    val sourceHash: String = canonicalSha256(sourceHash, "Dify projection source hash")
    val idempotencyKeyDigest: String = canonicalSha256(idempotencyKeyDigest, "Dify idempotency key digest")
    val operationFingerprint: String = canonicalSha256(operationFingerprint, "Dify operation fingerprint")

    init {
        require(!leaseDuration.isNegative && !leaseDuration.isZero && leaseDuration.toMillis() > 0L) {
            "Dify projection lease duration must be at least one millisecond."
        }
    }
}

class DifyRemovalClaimRequest(
    val key: DifyProjectionKey,
    externalId: String,
    idempotencyKeyDigest: String,
    operationFingerprint: String,
    val leaseDuration: Duration,
) {
    val externalId: String = safeText(externalId, "Dify projection external id", 512)
    val idempotencyKeyDigest: String = canonicalSha256(idempotencyKeyDigest, "Dify idempotency key digest")
    val operationFingerprint: String = canonicalSha256(operationFingerprint, "Dify operation fingerprint")

    init {
        require(!leaseDuration.isNegative && !leaseDuration.isZero && leaseDuration.toMillis() > 0L) {
            "Dify projection lease duration must be at least one millisecond."
        }
    }
}

enum class DifyProjectionStoreHealthStatus {
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
}

class DifyProjectionStoreHealth @JvmOverloads constructor(
    val status: DifyProjectionStoreHealthStatus,
    message: String? = null,
) {
    val message: String? = safeOptionalText(message, "Dify projection store diagnostic", 512)
}

/**
 * Mandatory durable mapping and idempotency boundary for the Dify adapter.
 *
 * Every method is one short, callback-free atomic store operation. Implementors
 * must never keep a database transaction open after returning a claim: source
 * download and all Dify network calls happen later. Claim expiry transitions
 * an unresolved write to UNKNOWN, never back to freely acquirable CREATE or
 * UPDATE. Implementations must retain old external-id generations so a stale
 * delivery removal can return SUPERSEDED instead of deleting the active Dify
 * document.
 */
interface DifyProjectionStore {
    fun claimSync(request: DifySyncClaimRequest): DifyProjectionClaim

    fun claimRemoval(request: DifyRemovalClaimRequest): DifyProjectionClaim

    /** Releases a lease only when no remote request was attempted. */
    fun abortBeforeRemote(lease: DifyProjectionLease): Boolean

    /** Completes an accepted create/update using exact lease CAS. */
    fun completeSync(
        lease: DifyProjectionLease,
        documentId: String,
        batchId: String,
    ): DifyProjectionSnapshot?

    /** Completes a 204 delete acceptance or an authoritative API 404. */
    fun completeRemoval(
        lease: DifyProjectionLease,
        state: DifyProjectionIndexState,
    ): DifyProjectionSnapshot?

    /** Permanently fences an ambiguous create/update/remove outcome. */
    fun markOutcomeUnknown(lease: DifyProjectionLease): Boolean

    /**
     * Applies administrator-authorized evidence to UNKNOWN using exact revision
     * and binding-digest CAS. Implementations must not perform network access,
     * guess a remote identity, or select an item from a provider-side list.
     */
    fun reconcileUnknown(evidence: DifyProjectionReconciliationEvidence): DifyProjectionSnapshot?

    /** Exact tenant/business/profile/dataset/external-id lookup; never guess by document id. */
    fun findByExternalId(key: DifyProjectionKey, externalId: String): DifyProjectionSnapshot?

    /** Exact active mapping lookup used by an authorized host recovery workflow. */
    fun findCurrent(key: DifyProjectionKey): DifyProjectionSnapshot?

    /** Records current remote evidence with external-id plus revision CAS. */
    fun recordObservedState(
        key: DifyProjectionKey,
        externalId: String,
        expectedRevision: Long,
        state: DifyProjectionIndexState,
    ): DifyProjectionSnapshot?

    fun health(): DifyProjectionStoreHealth
}

internal fun canonicalSha256(value: String, label: String): String {
    require(SHA256_PATTERN.matches(value)) { "$label must use sha256 followed by 64 lowercase hexadecimal characters." }
    return value
}

private val SHA256_PATTERN = Regex("sha256:[0-9a-f]{64}")

private fun projectionBindingDigest(
    key: DifyProjectionKey,
    projectionId: String,
    externalId: String,
    generation: Long,
    revision: Long,
    operation: DifyProjectionOperation,
    indexState: DifyProjectionIndexState,
    documentId: String?,
    batchId: String?,
    sourceHash: String,
    idempotencyKeyDigest: String,
    operationFingerprint: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(
        key.tenantId.value,
        key.businessId.value,
        key.connectorProfileId,
        key.datasetId,
        key.targetBindingDigest,
        projectionId,
        externalId,
        generation.toString(),
        revision.toString(),
        operation.name,
        indexState.name,
        documentId,
        batchId,
        sourceHash,
        idempotencyKeyDigest,
        operationFingerprint,
    ).forEach { value -> appendBindingComponent(digest, value) }
    return "sha256:" + digest.digest().joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun appendBindingComponent(digest: MessageDigest, value: String?) {
    if (value == null) {
        digest.update(0.toByte())
        return
    }
    digest.update(1.toByte())
    digest.update(ByteBuffer.allocate(4).putInt(value.length).array())
    value.forEach { character ->
        val code = character.code
        digest.update((code ushr 8).toByte())
        digest.update(code.toByte())
    }
}
