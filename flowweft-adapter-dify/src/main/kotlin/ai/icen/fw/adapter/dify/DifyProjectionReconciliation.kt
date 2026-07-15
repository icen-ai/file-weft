package ai.icen.fw.adapter.dify

/** Host-observed resolution for one durably fenced UNKNOWN projection. */
enum class DifyProjectionReconciliationResolution {
    SYNC_ACCEPTED,
    REMOVAL_ACCEPTED,
    API_ABSENT,
}

/**
 * Credential-free, audit-addressable evidence supplied by an administrator or
 * host recovery workflow. The evidence binds the exact UNKNOWN CAS snapshot;
 * raw provider responses, credentials and operator tokens do not belong here.
 */
class DifyProjectionReconciliationEvidence(
    val key: DifyProjectionKey,
    externalId: String,
    val expectedRevision: Long,
    expectedBindingDigest: String,
    val resolution: DifyProjectionReconciliationResolution,
    documentId: String,
    batchId: String?,
    evidenceDigest: String,
    val observedAtEpochMilli: Long,
) {
    val externalId: String = safeText(externalId, "Dify reconciliation external id", 512)
    val expectedBindingDigest: String = canonicalSha256(expectedBindingDigest, "Dify reconciliation binding digest")
    val documentId: String = canonicalUuid(documentId, "Dify reconciliation document id")
    val batchId: String? = safeOptionalText(batchId, "Dify reconciliation batch id", 128)?.also { value ->
        require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Dify reconciliation batch id contains unsafe path characters."
        }
    }
    val evidenceDigest: String = canonicalSha256(evidenceDigest, "Dify reconciliation evidence digest")

    init {
        require(expectedRevision > 0L) { "Dify reconciliation expected revision must be positive." }
        require(observedAtEpochMilli > 0L) { "Dify reconciliation observation time must be positive." }
        when (resolution) {
            DifyProjectionReconciliationResolution.SYNC_ACCEPTED -> require(this.batchId != null) {
                "Accepted Dify synchronization evidence requires an exact batch id."
            }
            DifyProjectionReconciliationResolution.REMOVAL_ACCEPTED,
            DifyProjectionReconciliationResolution.API_ABSENT,
            -> require(this.batchId == null) {
                "Dify removal reconciliation evidence must not carry a batch id."
            }
        }
    }
}

/**
 * Host authority boundary. Implementations must perform fresh administrator
 * authorization and audit the supplied evidence digest before returning true.
 */
interface DifyProjectionReconciliationAuthority {
    fun authorize(
        evidence: DifyProjectionReconciliationEvidence,
        snapshot: DifyProjectionSnapshot,
    ): Boolean
}

enum class DifyProjectionReconciliationStatus {
    RECOVERED,
    NOT_FOUND,
    STALE_EVIDENCE,
    INVALID_EVIDENCE,
    UNAUTHORIZED,
    STORE_REJECTED,
}

class DifyProjectionReconciliationResult internal constructor(
    val status: DifyProjectionReconciliationStatus,
    val snapshot: DifyProjectionSnapshot?,
)

/**
 * Explicit recovery service. It never lists provider documents, guesses an
 * identity, performs a remote call, or turns UNKNOWN back into a replayable
 * write. Only exact, authorized evidence can advance the durable projection.
 */
class DifyProjectionReconciliationService(
    private val projections: DifyProjectionStore,
    private val authority: DifyProjectionReconciliationAuthority,
) {
    fun reconcile(evidence: DifyProjectionReconciliationEvidence): DifyProjectionReconciliationResult {
        val current = try {
            projections.findByExternalId(evidence.key, evidence.externalId)
        } catch (_: Exception) {
            return result(DifyProjectionReconciliationStatus.STORE_REJECTED)
        } ?: return result(DifyProjectionReconciliationStatus.NOT_FOUND)

        if (
            current.indexState != DifyProjectionIndexState.UNKNOWN ||
            current.revision != evidence.expectedRevision ||
            current.bindingDigest != evidence.expectedBindingDigest ||
            current.key != evidence.key ||
            current.externalId != evidence.externalId
        ) {
            return result(DifyProjectionReconciliationStatus.STALE_EVIDENCE)
        }
        if (current.documentId != null && current.documentId != evidence.documentId) {
            return result(DifyProjectionReconciliationStatus.INVALID_EVIDENCE)
        }
        if (!resolutionMatchesOperation(current.operation, evidence.resolution)) {
            return result(DifyProjectionReconciliationStatus.INVALID_EVIDENCE)
        }
        val authorized = try {
            authority.authorize(evidence, current)
        } catch (_: Exception) {
            false
        }
        if (!authorized) return result(DifyProjectionReconciliationStatus.UNAUTHORIZED)

        val recovered = try {
            projections.reconcileUnknown(evidence)
        } catch (_: Exception) {
            null
        } ?: return result(DifyProjectionReconciliationStatus.STORE_REJECTED)

        if (!validRecovery(current, recovered, evidence)) {
            return result(DifyProjectionReconciliationStatus.STORE_REJECTED)
        }
        return result(DifyProjectionReconciliationStatus.RECOVERED, recovered)
    }

    private fun validRecovery(
        previous: DifyProjectionSnapshot,
        recovered: DifyProjectionSnapshot,
        evidence: DifyProjectionReconciliationEvidence,
    ): Boolean {
        val expectedState = when (evidence.resolution) {
            DifyProjectionReconciliationResolution.SYNC_ACCEPTED -> DifyProjectionIndexState.INDEXING
            DifyProjectionReconciliationResolution.REMOVAL_ACCEPTED -> DifyProjectionIndexState.REMOVAL_ACCEPTED
            DifyProjectionReconciliationResolution.API_ABSENT -> DifyProjectionIndexState.API_ABSENT
        }
        return recovered.key == previous.key &&
            recovered.projectionId == previous.projectionId &&
            recovered.externalId == previous.externalId &&
            recovered.generation == previous.generation &&
            recovered.revision > previous.revision &&
            recovered.operation == previous.operation &&
            recovered.indexState == expectedState &&
            recovered.documentId == evidence.documentId &&
            (if (evidence.resolution == DifyProjectionReconciliationResolution.SYNC_ACCEPTED) {
                recovered.batchId == evidence.batchId
            } else {
                recovered.batchId == previous.batchId
            }) &&
            recovered.sourceHash == previous.sourceHash &&
            recovered.idempotencyKeyDigest == previous.idempotencyKeyDigest &&
            recovered.operationFingerprint == previous.operationFingerprint
    }

    private fun resolutionMatchesOperation(
        operation: DifyProjectionOperation,
        resolution: DifyProjectionReconciliationResolution,
    ): Boolean = when (resolution) {
        DifyProjectionReconciliationResolution.SYNC_ACCEPTED ->
            operation == DifyProjectionOperation.CREATE || operation == DifyProjectionOperation.UPDATE
        DifyProjectionReconciliationResolution.REMOVAL_ACCEPTED,
        DifyProjectionReconciliationResolution.API_ABSENT,
        -> operation == DifyProjectionOperation.REMOVE
    }

    private fun result(
        status: DifyProjectionReconciliationStatus,
        snapshot: DifyProjectionSnapshot? = null,
    ): DifyProjectionReconciliationResult = DifyProjectionReconciliationResult(status, snapshot)
}
