package ai.icen.fw.adapter.dify

import ai.icen.fw.spi.connector.ConnectorExternalState
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorStatusQueryStatus
import ai.icen.fw.spi.connector.ConnectorStatusRequest
import ai.icen.fw.spi.connector.ConnectorStatusResult
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.connector.FileConnectorStatusProvider
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Dify 1.14 knowledge-base projection connector.
 *
 * The mandatory [DifyProjectionStore] is the durable idempotency and mapping
 * boundary; this adapter never silently substitutes an in-process map. Dify
 * does not document a write idempotency key, so ambiguous create/update
 * outcomes are fenced as UNKNOWN and never blindly replayed.
 */
class DifyKnowledgeBaseConnector internal constructor(
    private val profile: DifyKnowledgeBaseProfile,
    private val projections: DifyProjectionStore,
    private val sourceDownloader: DifySourceDownloader,
    private val remote: DifyRemoteApi,
    private val sleeper: DifySleeper = ThreadDifySleeper,
) : FileConnector, FileConnectorStatusProvider {
    constructor(
        profile: DifyKnowledgeBaseProfile,
        apiKeyProvider: DifyApiKeyProvider,
        projections: DifyProjectionStore,
    ) : this(
        profile,
        projections,
        StrictDifySourceDownloader(profile),
        OkHttpDifyRemoteApi(profile, apiKeyProvider),
        ThreadDifySleeper,
    )

    override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
        if (request.tenantId != profile.dedicatedTenantId) {
            return permanent("Connector profile is not dedicated to this tenant.")
        }
        val deadline = try {
            DifyDeadline.start(request.invocation.timeout)
        } catch (_: IllegalArgumentException) {
            return permanent("Connector invocation timeout is invalid.")
        }
        if (profile.projectionLeaseDuration <= request.invocation.timeout) {
            return permanent("Projection lease must exceed the connector invocation timeout.")
        }
        val key = try {
            projectionKey(request.tenantId, request.businessId)
        } catch (_: IllegalArgumentException) {
            return permanent("Connector tenant or business identity is invalid.")
        }
        val sourceHash = try {
            canonicalSha256(requireNotNull(request.source.contentHash), "Dify source content hash")
        } catch (_: Exception) {
            return permanent("Source content hash is missing or invalid.")
        }
        if (!validFileName(request.source.fileName)) return permanent("Source file name is invalid.")
        val contentType = try {
            validatedDifyContentType(request.source.contentType)
        } catch (_: IllegalArgumentException) {
            return permanent("Source content type is invalid.")
        }
        val idempotencyDigest = try {
            digest("flowweft.dify.idempotency.v1", boundedIdempotencyKey(request.invocation.idempotencyKey))
        } catch (_: IllegalArgumentException) {
            return permanent("Connector idempotency key is invalid.")
        }
        val fingerprint = try {
            digest(
                "flowweft.dify.sync.v1",
                request.tenantId.value,
                request.businessId.value,
                profile.profileId,
                profile.datasetId,
                profile.targetBindingDigest,
                sourceHash,
                request.source.fileName,
                contentType,
                profile.compatibility.name,
                profile.indexing.indexingTechnique.wireValue,
                profile.indexing.documentForm.wireValue,
                profile.indexing.documentLanguage,
            )
        } catch (_: IllegalArgumentException) {
            return permanent("Connector synchronization input is invalid.")
        }
        val claim = try {
            projections.claimSync(
                DifySyncClaimRequest(
                    key,
                    sourceHash,
                    idempotencyDigest,
                    fingerprint,
                    profile.projectionLeaseDuration,
                ),
            )
        } catch (_: Exception) {
            return retryable("Projection store could not claim synchronization.")
        }
        val prepared = prepareSyncClaim(claim, key, sourceHash, idempotencyDigest, fingerprint, deadline)
        if (prepared.result != null) return prepared.result
        val lease = prepared.lease ?: return permanent("Projection store returned an invalid synchronization claim.")
        val downloaded = try {
            sourceDownloader.download(request.source, deadline)
        } catch (_: Exception) {
            val released = abortQuietly(lease)
            if (!released) return permanent(UNKNOWN_OUTCOME_MESSAGE)
            return retryable("Source download could not complete.")
        }
        val localSource = downloaded.source
        if (localSource == null) {
            val released = abortQuietly(lease)
            if (!released) return permanent(UNKNOWN_OUTCOME_MESSAGE)
            return if (downloaded.failureKind == DifyFailureKind.PERMANENT) {
                permanent(downloaded.diagnostic ?: "Source download was rejected.")
            } else {
                retryable(downloaded.diagnostic ?: "Source download could not complete.")
            }
        }

        localSource.use { source ->
            val write = try {
                when (lease.snapshot.operation) {
                    DifyProjectionOperation.CREATE -> remote.create(
                        source,
                        request.source.fileName,
                        contentType,
                        deadline,
                    )
                    DifyProjectionOperation.UPDATE -> remote.update(
                        requireNotNull(lease.snapshot.documentId),
                        source,
                        request.source.fileName,
                        contentType,
                        deadline,
                    )
                    DifyProjectionOperation.REMOVE -> {
                        markUnknownQuietly(lease)
                        return permanent("Projection store returned a removal claim for synchronization.")
                    }
                }
            } catch (_: Exception) {
                markUnknownQuietly(lease)
                return permanent(UNKNOWN_OUTCOME_MESSAGE)
            }
            return completeWrite(lease, write)
        }
    }

    override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult {
        if (request.tenantId != profile.dedicatedTenantId) {
            return permanent("Connector profile is not dedicated to this tenant.")
        }
        val deadline = try {
            DifyDeadline.start(request.invocation.timeout)
        } catch (_: IllegalArgumentException) {
            return permanent("Connector invocation timeout is invalid.")
        }
        if (profile.projectionLeaseDuration <= request.invocation.timeout) {
            return permanent("Projection lease must exceed the connector invocation timeout.")
        }
        val key = try {
            projectionKey(request.tenantId, request.businessId)
        } catch (_: IllegalArgumentException) {
            return permanent("Connector tenant or business identity is invalid.")
        }
        val idempotencyDigest = try {
            digest("flowweft.dify.idempotency.v1", boundedIdempotencyKey(request.invocation.idempotencyKey))
        } catch (_: IllegalArgumentException) {
            return permanent("Connector idempotency key is invalid.")
        }
        val fingerprint = try {
            digest(
                "flowweft.dify.remove.v1",
                request.tenantId.value,
                request.businessId.value,
                profile.profileId,
                profile.datasetId,
                profile.targetBindingDigest,
                request.externalId,
            )
        } catch (_: IllegalArgumentException) {
            return permanent("Connector removal input is invalid.")
        }
        val claim = try {
            projections.claimRemoval(
                DifyRemovalClaimRequest(
                    key,
                    request.externalId,
                    idempotencyDigest,
                    fingerprint,
                    profile.projectionLeaseDuration,
                ),
            )
        } catch (_: Exception) {
            return retryable("Projection store could not claim removal.")
        }
        val prepared = prepareRemovalClaim(claim, key, request.externalId, idempotencyDigest, fingerprint, deadline)
        if (prepared.result != null) return prepared.result
        val lease = prepared.lease ?: return permanent("Projection store returned an invalid removal claim.")
        val deletion = try {
            remote.delete(requireNotNull(lease.snapshot.documentId), deadline)
        } catch (_: Exception) {
            DifyDeleteDisposition.AMBIGUOUS
        }
        return when (deletion) {
            DifyDeleteDisposition.ACCEPTED -> completeRemoval(lease, DifyProjectionIndexState.REMOVAL_ACCEPTED)
            DifyDeleteDisposition.API_ABSENT -> completeRemoval(lease, DifyProjectionIndexState.API_ABSENT)
            DifyDeleteDisposition.RETRYABLE_REJECTED -> {
                if (abortQuietly(lease)) retryable("Dify removal was rate limited.") else permanent(UNKNOWN_OUTCOME_MESSAGE)
            }
            DifyDeleteDisposition.PERMANENT_REJECTED -> {
                if (abortQuietly(lease)) permanent("Dify removal was rejected.") else permanent(UNKNOWN_OUTCOME_MESSAGE)
            }
            DifyDeleteDisposition.AMBIGUOUS -> reconcileAmbiguousRemoval(lease, deadline)
        }
    }

    override fun status(request: ConnectorStatusRequest): ConnectorStatusResult {
        if (request.tenantId != profile.dedicatedTenantId) {
            return statusFailure(
                ConnectorStatusQueryStatus.PERMANENT_FAILURE,
                "Connector profile is not dedicated to this tenant.",
            )
        }
        val key = try {
            projectionKey(request.tenantId, request.businessId)
        } catch (_: IllegalArgumentException) {
            return statusFailure(
                ConnectorStatusQueryStatus.PERMANENT_FAILURE,
                "Connector tenant or business identity is invalid.",
            )
        }
        val snapshot = try {
            projections.findByExternalId(key, request.externalId)
        } catch (_: Exception) {
            return statusFailure(ConnectorStatusQueryStatus.RETRYABLE_FAILURE, "Projection store status lookup failed.")
        } ?: return statusFailure(
            ConnectorStatusQueryStatus.PERMANENT_FAILURE,
            "Projection mapping was not found for this external identity.",
        )
        if (!snapshotMatches(snapshot, key, request.externalId)) {
            return statusFailure(ConnectorStatusQueryStatus.PERMANENT_FAILURE, "Projection mapping binding is invalid.")
        }
        if (snapshot.indexState == DifyProjectionIndexState.UNKNOWN) {
            return ConnectorStatusResult(
                ConnectorStatusQueryStatus.SUCCESS,
                ConnectorExternalState.UNKNOWN,
                UNKNOWN_OUTCOME_MESSAGE,
            )
        }
        if (snapshot.indexState == DifyProjectionIndexState.CLAIMED) {
            return ConnectorStatusResult(
                ConnectorStatusQueryStatus.SUCCESS,
                ConnectorExternalState.PROCESSING,
                "Projection operation is in progress.",
            )
        }
        val documentId = snapshot.documentId ?: return statusFailure(
            ConnectorStatusQueryStatus.PERMANENT_FAILURE,
            "Projection mapping does not contain a remote document identity.",
        )
        val deadline = try {
            DifyDeadline.start(request.invocation.timeout)
        } catch (_: IllegalArgumentException) {
            return statusFailure(ConnectorStatusQueryStatus.PERMANENT_FAILURE, "Connector invocation timeout is invalid.")
        }
        val evidence = try {
            if (snapshot.operation == DifyProjectionOperation.REMOVE && snapshot.indexState in REMOVAL_STATES) {
                remote.documentStatus(documentId, deadline)
            } else {
                readActiveStatus(snapshot, documentId, deadline)
            }
        } catch (_: Exception) {
            DifyStatusReadResult(DifyReadDisposition.RETRYABLE_FAILURE)
        }
        return applyStatusEvidence(snapshot, evidence)
    }

    override fun health(): ConnectorHealth {
        val storeHealth = try {
            projections.health()
        } catch (_: Exception) {
            return ConnectorHealth(ConnectorHealthStatus.UNHEALTHY, "Dify projection store health check failed.")
        }
        if (storeHealth.status == DifyProjectionStoreHealthStatus.UNHEALTHY) {
            return ConnectorHealth(ConnectorHealthStatus.UNHEALTHY, "Dify projection store is unhealthy.")
        }
        val remoteHealth = try {
            remote.health(DifyDeadline.start(profile.readTimeout))
        } catch (_: Exception) {
            return ConnectorHealth(ConnectorHealthStatus.UNHEALTHY, "Dify health check could not complete.")
        }
        if (remoteHealth.disposition == DifyReadDisposition.RETRYABLE_FAILURE) {
            return ConnectorHealth(ConnectorHealthStatus.DEGRADED, "Dify Service API is temporarily unavailable.")
        }
        if (remoteHealth.disposition != DifyReadDisposition.SUCCESS) {
            return ConnectorHealth(ConnectorHealthStatus.UNHEALTHY, "Dify Service API profile was rejected.")
        }
        if (!remoteHealth.datasetMatches || !remoteHealth.apiEnabled || !remoteHealth.managedDataset) {
            return ConnectorHealth(ConnectorHealthStatus.UNHEALTHY, "Dify dataset profile does not match the required managed Service API capability.")
        }
        if (!remoteHealth.embeddingAvailable || storeHealth.status == DifyProjectionStoreHealthStatus.DEGRADED) {
            return ConnectorHealth(ConnectorHealthStatus.DEGRADED, "Dify projection dependencies are degraded.")
        }
        return ConnectorHealth(ConnectorHealthStatus.HEALTHY, "Dify 1.14 knowledge-base projection is healthy.")
    }

    private fun prepareSyncClaim(
        claim: DifyProjectionClaim,
        key: DifyProjectionKey,
        sourceHash: String,
        idempotencyDigest: String,
        fingerprint: String,
        deadline: DifyDeadline,
    ): PreparedClaim {
        val snapshot = claim.snapshot
        if (snapshot != null && !syncSnapshotMatches(snapshot, key, sourceHash, idempotencyDigest, fingerprint)) {
            claim.lease?.let(::abortQuietly)
            return PreparedClaim(result = permanent("Projection store returned a mismatched synchronization binding."))
        }
        return when (claim.disposition) {
            DifyProjectionClaimDisposition.ACQUIRED -> {
                val lease = claim.lease
                if (
                    lease == null || snapshot == null || lease.snapshot.bindingDigest != snapshot.bindingDigest ||
                    lease.snapshot.operation !in setOf(DifyProjectionOperation.CREATE, DifyProjectionOperation.UPDATE)
                ) {
                    lease?.let(::abortQuietly)
                    PreparedClaim(result = permanent("Projection store returned an invalid synchronization lease."))
                } else {
                    PreparedClaim(lease = lease)
                }
            }
            DifyProjectionClaimDisposition.REPLAY -> if (
                snapshot != null && snapshot.operation in SYNC_OPERATIONS && snapshot.indexState in ACCEPTED_SYNC_STATES
            ) {
                PreparedClaim(result = ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, snapshot.externalId))
            } else {
                PreparedClaim(result = permanent("Projection store returned an invalid synchronization replay."))
            }
            DifyProjectionClaimDisposition.IN_PROGRESS -> PreparedClaim(
                result = awaitInProgress(claim, key, sourceHash, idempotencyDigest, fingerprint, deadline, removal = false),
            )
            DifyProjectionClaimDisposition.CONFLICT -> PreparedClaim(result = permanent("Connector idempotency key conflicts with another synchronization."))
            DifyProjectionClaimDisposition.OUTCOME_UNKNOWN -> PreparedClaim(result = permanent(UNKNOWN_OUTCOME_MESSAGE))
            DifyProjectionClaimDisposition.SUPERSEDED,
            DifyProjectionClaimDisposition.NOT_FOUND,
            -> PreparedClaim(result = permanent("Projection store returned an invalid synchronization disposition."))
        }
    }

    private fun prepareRemovalClaim(
        claim: DifyProjectionClaim,
        key: DifyProjectionKey,
        externalId: String,
        idempotencyDigest: String,
        fingerprint: String,
        deadline: DifyDeadline,
    ): PreparedClaim {
        val snapshot = claim.snapshot
        if (snapshot != null && !removalSnapshotMatches(snapshot, key, externalId, idempotencyDigest, fingerprint)) {
            claim.lease?.let(::abortQuietly)
            return PreparedClaim(result = permanent("Projection store returned a mismatched removal binding."))
        }
        return when (claim.disposition) {
            DifyProjectionClaimDisposition.ACQUIRED -> {
                val lease = claim.lease
                if (
                    lease == null || snapshot == null || lease.snapshot.bindingDigest != snapshot.bindingDigest ||
                    lease.snapshot.operation != DifyProjectionOperation.REMOVE
                ) {
                    lease?.let(::abortQuietly)
                    PreparedClaim(result = permanent("Projection store returned an invalid removal lease."))
                } else {
                    PreparedClaim(lease = lease)
                }
            }
            DifyProjectionClaimDisposition.REPLAY -> if (
                snapshot != null && snapshot.operation == DifyProjectionOperation.REMOVE &&
                snapshot.indexState in REMOVAL_STATES
            ) {
                PreparedClaim(result = ConnectorSyncResult(ConnectorSyncStatus.SUCCESS))
            } else {
                PreparedClaim(result = permanent("Projection store returned an invalid removal replay."))
            }
            DifyProjectionClaimDisposition.SUPERSEDED -> PreparedClaim(
                result = ConnectorSyncResult(
                    ConnectorSyncStatus.SUCCESS,
                    message = "Superseded projection generation was preserved without deleting the active document.",
                ),
            )
            DifyProjectionClaimDisposition.IN_PROGRESS -> PreparedClaim(
                result = awaitInProgress(claim, key, null, idempotencyDigest, fingerprint, deadline, removal = true),
            )
            DifyProjectionClaimDisposition.CONFLICT -> PreparedClaim(result = permanent("Connector idempotency key conflicts with another removal."))
            DifyProjectionClaimDisposition.OUTCOME_UNKNOWN -> PreparedClaim(result = permanent(UNKNOWN_OUTCOME_MESSAGE))
            DifyProjectionClaimDisposition.NOT_FOUND -> PreparedClaim(
                result = permanent("Projection mapping was not found for this external identity."),
            )
        }
    }

    private fun completeWrite(lease: DifyProjectionLease, write: DifyWriteResult): ConnectorSyncResult = when (write.disposition) {
        DifyWriteDisposition.ACCEPTED -> {
            val documentId = write.documentId
            val batchId = write.batchId
            if (documentId == null || batchId == null) {
                markUnknownQuietly(lease)
                permanent(UNKNOWN_OUTCOME_MESSAGE)
            } else {
                val completed = try {
                    projections.completeSync(lease, documentId, batchId)
                } catch (_: Exception) {
                    null
                }
                if (completed == null || !completedSyncMatches(completed, lease.snapshot, documentId, batchId)) {
                    markUnknownQuietly(lease)
                    permanent(UNKNOWN_OUTCOME_MESSAGE)
                } else {
                    ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, completed.externalId)
                }
            }
        }
        DifyWriteDisposition.NOT_SENT -> {
            if (abortQuietly(lease)) retryable("Dify synchronization was not sent.") else permanent(UNKNOWN_OUTCOME_MESSAGE)
        }
        DifyWriteDisposition.OUTCOME_UNKNOWN -> {
            markUnknownQuietly(lease)
            permanent(UNKNOWN_OUTCOME_MESSAGE)
        }
    }

    private fun completeRemoval(lease: DifyProjectionLease, state: DifyProjectionIndexState): ConnectorSyncResult {
        val completed = try {
            projections.completeRemoval(lease, state)
        } catch (_: Exception) {
            null
        }
        return if (completed != null && completedRemovalMatches(completed, lease.snapshot, state)
        ) {
            ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)
        } else {
            markUnknownQuietly(lease)
            permanent(UNKNOWN_OUTCOME_MESSAGE)
        }
    }

    private fun reconcileAmbiguousRemoval(lease: DifyProjectionLease, deadline: DifyDeadline): ConnectorSyncResult {
        val evidence = try {
            remote.documentStatus(requireNotNull(lease.snapshot.documentId), deadline)
        } catch (_: Exception) {
            DifyStatusReadResult(DifyReadDisposition.RETRYABLE_FAILURE)
        }
        return if (evidence.disposition == DifyReadDisposition.API_ABSENT) {
            completeRemoval(lease, DifyProjectionIndexState.API_ABSENT)
        } else {
            markUnknownQuietly(lease)
            permanent(UNKNOWN_OUTCOME_MESSAGE)
        }
    }

    private fun readActiveStatus(
        snapshot: DifyProjectionSnapshot,
        documentId: String,
        deadline: DifyDeadline,
    ): DifyStatusReadResult {
        val batch = snapshot.batchId
            ?: return remote.documentStatus(documentId, deadline)
        val indexing = remote.indexingStatus(batch, documentId, deadline)
        return when {
            indexing.disposition == DifyReadDisposition.API_ABSENT ->
                remote.documentStatus(documentId, deadline)
            indexing.disposition == DifyReadDisposition.SUCCESS &&
                indexing.state == DifyProjectionIndexState.AVAILABLE ->
                remote.documentStatus(documentId, deadline)
            else -> indexing
        }
    }

    private fun applyStatusEvidence(
        snapshot: DifyProjectionSnapshot,
        evidence: DifyStatusReadResult,
    ): ConnectorStatusResult {
        if (evidence.disposition == DifyReadDisposition.RETRYABLE_FAILURE) {
            return statusFailure(ConnectorStatusQueryStatus.RETRYABLE_FAILURE, "Dify status is temporarily unavailable.")
        }
        if (evidence.disposition == DifyReadDisposition.PERMANENT_FAILURE) {
            return statusFailure(ConnectorStatusQueryStatus.PERMANENT_FAILURE, "Dify status response did not match the configured contract.")
        }
        val observed = when {
            evidence.disposition == DifyReadDisposition.API_ABSENT -> DifyProjectionIndexState.API_ABSENT
            snapshot.operation == DifyProjectionOperation.REMOVE && snapshot.indexState in REMOVAL_STATES ->
                DifyProjectionIndexState.REMOVAL_ACCEPTED
            else -> evidence.state
        }
        val persisted = if (observed == snapshot.indexState) {
            snapshot
        } else {
            try {
                projections.recordObservedState(
                    snapshot.key,
                    snapshot.externalId,
                    snapshot.revision,
                    observed,
                )
            } catch (_: Exception) {
                null
            }
        }
        if (
            persisted == null ||
            (persisted !== snapshot && !observedStateMatches(persisted, snapshot, observed)) ||
            (persisted === snapshot && observed != snapshot.indexState)
        ) {
            return statusFailure(ConnectorStatusQueryStatus.RETRYABLE_FAILURE, "Projection status could not be recorded safely.")
        }
        return statusSuccess(observed)
    }

    private fun statusSuccess(state: DifyProjectionIndexState): ConnectorStatusResult = when (state) {
        DifyProjectionIndexState.CLAIMED,
        DifyProjectionIndexState.INDEXING,
        -> ConnectorStatusResult(ConnectorStatusQueryStatus.SUCCESS, ConnectorExternalState.PROCESSING)
        DifyProjectionIndexState.AVAILABLE -> ConnectorStatusResult(
            ConnectorStatusQueryStatus.SUCCESS,
            ConnectorExternalState.AVAILABLE,
        )
        DifyProjectionIndexState.FAILED -> ConnectorStatusResult(
            ConnectorStatusQueryStatus.SUCCESS,
            ConnectorExternalState.FAILED,
            "Dify indexing is unavailable for this projection.",
        )
        DifyProjectionIndexState.REMOVAL_ACCEPTED -> ConnectorStatusResult(
            ConnectorStatusQueryStatus.SUCCESS,
            ConnectorExternalState.REMOVAL_ACCEPTED,
            "Dify accepted removal; physical purge is not verifiable.",
        )
        DifyProjectionIndexState.API_ABSENT -> ConnectorStatusResult(
            ConnectorStatusQueryStatus.SUCCESS,
            ConnectorExternalState.API_ABSENT,
            "Dify API no longer exposes the document; physical purge is not verifiable.",
        )
        DifyProjectionIndexState.UNKNOWN -> ConnectorStatusResult(
            ConnectorStatusQueryStatus.SUCCESS,
            ConnectorExternalState.UNKNOWN,
            UNKNOWN_OUTCOME_MESSAGE,
        )
    }

    private fun statusFailure(queryStatus: ConnectorStatusQueryStatus, diagnostic: String): ConnectorStatusResult =
        ConnectorStatusResult(queryStatus, ConnectorExternalState.UNKNOWN, diagnostic)

    private fun projectionKey(tenantId: ai.icen.fw.core.id.Identifier, businessId: ai.icen.fw.core.id.Identifier) =
        DifyProjectionKey(
            tenantId,
            businessId,
            profile.profileId,
            profile.datasetId,
            profile.targetBindingDigest,
        )

    private fun syncSnapshotMatches(
        snapshot: DifyProjectionSnapshot,
        key: DifyProjectionKey,
        sourceHash: String,
        idempotencyDigest: String,
        fingerprint: String,
    ): Boolean = snapshot.key == key && snapshot.sourceHash == sourceHash &&
        snapshot.idempotencyKeyDigest == idempotencyDigest && snapshot.operationFingerprint == fingerprint

    private fun removalSnapshotMatches(
        snapshot: DifyProjectionSnapshot,
        key: DifyProjectionKey,
        externalId: String,
        idempotencyDigest: String,
        fingerprint: String,
    ): Boolean = snapshotMatches(snapshot, key, externalId) && snapshot.idempotencyKeyDigest == idempotencyDigest &&
        snapshot.operationFingerprint == fingerprint

    private fun snapshotMatches(snapshot: DifyProjectionSnapshot, key: DifyProjectionKey, externalId: String): Boolean =
        snapshot.key == key && snapshot.externalId == externalId &&
            snapshot.externalId == DifyProjectionExternalIds.create(snapshot.projectionId, snapshot.generation)

    private fun completedSyncMatches(
        completed: DifyProjectionSnapshot,
        claimed: DifyProjectionSnapshot,
        documentId: String,
        batchId: String,
    ): Boolean = snapshotMatches(completed, claimed.key, claimed.externalId) &&
        completed.projectionId == claimed.projectionId && completed.generation == claimed.generation &&
        completed.operation == claimed.operation &&
        completed.revision > claimed.revision && completed.documentId == documentId && completed.batchId == batchId &&
        completed.sourceHash == claimed.sourceHash && completed.idempotencyKeyDigest == claimed.idempotencyKeyDigest &&
        completed.operationFingerprint == claimed.operationFingerprint && completed.indexState == DifyProjectionIndexState.INDEXING

    private fun completedRemovalMatches(
        completed: DifyProjectionSnapshot,
        claimed: DifyProjectionSnapshot,
        state: DifyProjectionIndexState,
    ): Boolean = snapshotMatches(completed, claimed.key, claimed.externalId) &&
        completed.projectionId == claimed.projectionId && completed.generation == claimed.generation &&
        completed.revision > claimed.revision && completed.operation == DifyProjectionOperation.REMOVE &&
        completed.indexState == state && completed.documentId == claimed.documentId &&
        completed.batchId == claimed.batchId && completed.sourceHash == claimed.sourceHash &&
        completed.idempotencyKeyDigest == claimed.idempotencyKeyDigest &&
        completed.operationFingerprint == claimed.operationFingerprint

    private fun observedStateMatches(
        observed: DifyProjectionSnapshot,
        previous: DifyProjectionSnapshot,
        state: DifyProjectionIndexState,
    ): Boolean = snapshotMatches(observed, previous.key, previous.externalId) &&
        observed.projectionId == previous.projectionId && observed.generation == previous.generation &&
        observed.revision > previous.revision && observed.operation == previous.operation &&
        observed.indexState == state && observed.documentId == previous.documentId && observed.batchId == previous.batchId &&
        observed.sourceHash == previous.sourceHash && observed.idempotencyKeyDigest == previous.idempotencyKeyDigest &&
        observed.operationFingerprint == previous.operationFingerprint

    private fun awaitInProgress(
        claim: DifyProjectionClaim,
        key: DifyProjectionKey,
        sourceHash: String?,
        idempotencyDigest: String,
        fingerprint: String,
        deadline: DifyDeadline,
        removal: Boolean,
    ): ConnectorSyncResult {
        val externalId = claim.snapshot?.externalId
            ?: return retryable("Projection operation is already in progress.")
        var delayMillis = 10L
        while (deadline.canWait(delayMillis)) {
            try {
                sleeper.sleep(delayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return retryable("Projection replay wait was interrupted.")
            }
            val current = try {
                projections.findByExternalId(key, externalId)
            } catch (_: Exception) {
                return retryable("Projection replay could not be read safely.")
            } ?: return retryable("Projection replay is not yet durable.")
            val bound = snapshotMatches(current, key, externalId) &&
                current.idempotencyKeyDigest == idempotencyDigest &&
                current.operationFingerprint == fingerprint &&
                (sourceHash == null || current.sourceHash == sourceHash)
            if (!bound) return permanent("Projection replay binding changed while waiting.")
            if (current.indexState == DifyProjectionIndexState.CLAIMED) {
                delayMillis = (delayMillis * 2L).coerceAtMost(100L)
                continue
            }
            if (current.indexState == DifyProjectionIndexState.UNKNOWN) return permanent(UNKNOWN_OUTCOME_MESSAGE)
            return if (removal) {
                if (current.operation == DifyProjectionOperation.REMOVE && current.indexState in REMOVAL_STATES) {
                    ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)
                } else {
                    permanent("Projection removal replay reached an invalid state.")
                }
            } else {
                if (current.operation in setOf(DifyProjectionOperation.CREATE, DifyProjectionOperation.UPDATE)) {
                    ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, current.externalId)
                } else {
                    permanent("Projection synchronization replay reached an invalid state.")
                }
            }
        }
        return retryable("Projection operation is still in progress at the invocation deadline.")
    }

    private fun abortQuietly(lease: DifyProjectionLease): Boolean = try {
        projections.abortBeforeRemote(lease)
    } catch (_: Exception) {
        false
    }

    private fun markUnknownQuietly(lease: DifyProjectionLease): Boolean = try {
        projections.markOutcomeUnknown(lease)
    } catch (_: Exception) {
        false
    }

    private fun permanent(message: String) = ConnectorSyncResult(ConnectorSyncStatus.PERMANENT_FAILURE, message = message)

    private fun retryable(message: String) = ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE, message = message)

    private fun validFileName(value: String): Boolean = value.isNotBlank() && value.length <= 255 &&
        value != "." && value != ".." && '/' !in value && '\\' !in value &&
        value.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() } &&
        isWellFormedUtf16(value)

    private fun boundedIdempotencyKey(value: String): String {
        require(value.isNotBlank() && value.length <= 512) { "Connector idempotency key must be bounded." }
        require(value.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) {
            "Connector idempotency key contains unsafe characters."
        }
        require(isWellFormedUtf16(value)) { "Connector idempotency key contains malformed Unicode." }
        return value
    }

    private fun digest(domain: String, vararg values: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digestComponent(digest, domain)
        values.forEach { value ->
            if (value == null) {
                digest.update(0.toByte())
            } else {
                digest.update(1.toByte())
                digestComponent(digest, value)
            }
        }
        return "sha256:" + digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun digestComponent(digest: MessageDigest, value: String) {
        val bytes = strictUtf8(value)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun strictUtf8(value: String): ByteArray = try {
        val encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also { bytes -> encoded.get(bytes) }
    } catch (failure: CharacterCodingException) {
        throw IllegalArgumentException("Text contains malformed Unicode.", failure)
    }

    private fun isWellFormedUtf16(value: String): Boolean = try {
        strictUtf8(value)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private class PreparedClaim(
        val lease: DifyProjectionLease? = null,
        val result: ConnectorSyncResult? = null,
    )

    companion object {
        const val UNKNOWN_OUTCOME_MESSAGE: String =
            "Dify operation outcome is unknown; reconcile the persisted projection before retrying."

        private val REMOVAL_STATES = setOf(
            DifyProjectionIndexState.REMOVAL_ACCEPTED,
            DifyProjectionIndexState.API_ABSENT,
        )
        private val SYNC_OPERATIONS = setOf(
            DifyProjectionOperation.CREATE,
            DifyProjectionOperation.UPDATE,
        )
        private val ACCEPTED_SYNC_STATES = setOf(
            DifyProjectionIndexState.INDEXING,
            DifyProjectionIndexState.AVAILABLE,
            DifyProjectionIndexState.FAILED,
            DifyProjectionIndexState.API_ABSENT,
        )
    }
}
