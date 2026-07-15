package ai.icen.fw.adapter.dify

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.connector.ConnectorFileSource
import ai.icen.fw.spi.connector.ConnectorInvocation
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorStatusRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

internal const val TEST_DATASET_ID = "11111111-1111-1111-1111-111111111111"
internal const val TEST_DOCUMENT_ID = "22222222-2222-2222-2222-222222222222"
internal const val TEST_BATCH_ID = "batch_1"
internal const val TEST_SOURCE_HASH =
    "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

internal fun testProfile(
    projectionLeaseDuration: Duration = Duration.ofMinutes(2),
    allowPrivateApiAddresses: Boolean = false,
): DifyKnowledgeBaseProfile = DifyKnowledgeBaseProfile(
    profileId = "dify-main",
    dedicatedTenantId = Identifier("tenant-a"),
    apiBaseUri = URI("https://dify.example.test/v1"),
    datasetId = TEST_DATASET_ID,
    sourceTrustPolicy = DifySourceTrustPolicy(listOf(URI("https://files.example.test"))),
    allowPrivateApiAddresses = allowPrivateApiAddresses,
    maximumReadAttempts = 3,
    initialReadRetryDelay = Duration.ofMillis(1),
    projectionLeaseDuration = projectionLeaseDuration,
)

internal fun syncRequest(
    tenant: String = "tenant-a",
    business: String = "document-a",
    idempotencyKey: String = "sync-1",
    contentHash: String = TEST_SOURCE_HASH,
    fileName: String = "hello.txt",
    contentType: String? = "text/plain",
    timeout: Duration = Duration.ofSeconds(2),
): ConnectorSyncRequest = ConnectorSyncRequest(
    tenantId = Identifier(tenant),
    businessId = Identifier(business),
    source = ConnectorFileSource(
        URI("https://files.example.test/hello.txt"),
        fileName,
        contentType,
        contentHash,
    ),
    invocation = ConnectorInvocation(idempotencyKey, timeout),
)

internal fun removalRequest(
    externalId: String,
    tenant: String = "tenant-a",
    business: String = "document-a",
    idempotencyKey: String = "remove-1",
    timeout: Duration = Duration.ofSeconds(2),
): ConnectorRemoveRequest = ConnectorRemoveRequest(
    Identifier(tenant),
    Identifier(business),
    externalId,
    ConnectorInvocation(idempotencyKey, timeout),
)

internal fun statusRequest(
    externalId: String,
    tenant: String = "tenant-a",
    business: String = "document-a",
): ConnectorStatusRequest = ConnectorStatusRequest(
    Identifier(tenant),
    Identifier(business),
    externalId,
    ConnectorInvocation("status-1", Duration.ofSeconds(2)),
)

internal class TestDifySourceDownloader : DifySourceDownloader {
    var calls: Int = 0

    override fun download(source: ConnectorFileSource, deadline: DifyDeadline): DifySourceDownloadResult {
        calls++
        val path = Files.createTempFile("flowweft-dify-test-", ".bin")
        Files.write(path, "hello".toByteArray(Charsets.UTF_8))
        return DifySourceDownloadResult.success(DifyDownloadedSource(path, 5L, TEST_SOURCE_HASH))
    }
}

internal class TestDifyRemoteApi : DifyRemoteApi {
    var createCalls: Int = 0
    var updateCalls: Int = 0
    var deleteCalls: Int = 0
    var indexingCalls: Int = 0
    var documentCalls: Int = 0
    var healthCalls: Int = 0

    var createResult = DifyWriteResult(DifyWriteDisposition.ACCEPTED, TEST_DOCUMENT_ID, TEST_BATCH_ID)
    var updateResult = DifyWriteResult(DifyWriteDisposition.ACCEPTED, TEST_DOCUMENT_ID, "batch_2")
    var deleteResult = DifyDeleteDisposition.ACCEPTED
    var indexingResult = DifyStatusReadResult(DifyReadDisposition.SUCCESS, DifyProjectionIndexState.AVAILABLE)
    var documentResult = DifyStatusReadResult(DifyReadDisposition.SUCCESS, DifyProjectionIndexState.AVAILABLE)
    var healthResult = DifyHealthReadResult(
        DifyReadDisposition.SUCCESS,
        datasetMatches = true,
        apiEnabled = true,
        managedDataset = true,
        embeddingAvailable = true,
    )

    override fun create(
        source: DifyDownloadedSource,
        fileName: String,
        contentType: String?,
        deadline: DifyDeadline,
    ): DifyWriteResult {
        createCalls++
        return createResult
    }

    override fun update(
        documentId: String,
        source: DifyDownloadedSource,
        fileName: String,
        contentType: String?,
        deadline: DifyDeadline,
    ): DifyWriteResult {
        updateCalls++
        return updateResult
    }

    override fun delete(documentId: String, deadline: DifyDeadline): DifyDeleteDisposition {
        deleteCalls++
        return deleteResult
    }

    override fun indexingStatus(
        batchId: String,
        documentId: String,
        deadline: DifyDeadline,
    ): DifyStatusReadResult {
        indexingCalls++
        return indexingResult
    }

    override fun documentStatus(documentId: String, deadline: DifyDeadline): DifyStatusReadResult {
        documentCalls++
        return documentResult
    }

    override fun health(deadline: DifyDeadline): DifyHealthReadResult {
        healthCalls++
        return healthResult
    }
}

/** Test-only reference semantics for the mandatory durable store contract. */
internal class InMemoryDifyProjectionStore : DifyProjectionStore {
    private data class ExternalKey(val key: DifyProjectionKey, val externalId: String)
    private data class LeaseRecord(
        val lease: DifyProjectionLease,
        val previous: DifyProjectionSnapshot?,
    )

    private val current = linkedMapOf<DifyProjectionKey, DifyProjectionSnapshot>()
    private val byExternal = linkedMapOf<ExternalKey, DifyProjectionSnapshot>()
    private val leases = linkedMapOf<String, LeaseRecord>()
    var healthResult = DifyProjectionStoreHealth(DifyProjectionStoreHealthStatus.HEALTHY)

    @Synchronized
    override fun claimSync(request: DifySyncClaimRequest): DifyProjectionClaim {
        val existing = current[request.key]
        if (existing != null) {
            val same = existing.sourceHash == request.sourceHash &&
                existing.idempotencyKeyDigest == request.idempotencyKeyDigest &&
                existing.operationFingerprint == request.operationFingerprint
            if (same) {
                return when (existing.indexState) {
                    DifyProjectionIndexState.CLAIMED -> claim(DifyProjectionClaimDisposition.IN_PROGRESS, existing)
                    DifyProjectionIndexState.UNKNOWN -> claim(DifyProjectionClaimDisposition.OUTCOME_UNKNOWN, existing)
                    else -> claim(DifyProjectionClaimDisposition.REPLAY, existing)
                }
            }
            if (existing.idempotencyKeyDigest == request.idempotencyKeyDigest) {
                return claim(DifyProjectionClaimDisposition.CONFLICT, existing)
            }
            if (existing.indexState == DifyProjectionIndexState.CLAIMED) {
                return claim(DifyProjectionClaimDisposition.CONFLICT, existing)
            }
            if (existing.indexState == DifyProjectionIndexState.UNKNOWN) {
                return claim(DifyProjectionClaimDisposition.OUTCOME_UNKNOWN, existing)
            }
        }

        val operation = if (existing?.documentId == null || existing.operation == DifyProjectionOperation.REMOVE) {
            DifyProjectionOperation.CREATE
        } else {
            DifyProjectionOperation.UPDATE
        }
        val projectionId = existing?.projectionId ?: UUID.randomUUID().toString()
        val generation = (existing?.generation ?: 0L) + 1L
        val revision = (existing?.revision ?: 0L) + 1L
        val claimed = snapshot(
            key = request.key,
            projectionId = projectionId,
            generation = generation,
            revision = revision,
            operation = operation,
            state = DifyProjectionIndexState.CLAIMED,
            documentId = if (operation == DifyProjectionOperation.UPDATE) existing?.documentId else null,
            batchId = null,
            sourceHash = request.sourceHash,
            idempotencyDigest = request.idempotencyKeyDigest,
            fingerprint = request.operationFingerprint,
        )
        return acquire(claimed, existing, request.leaseDuration)
    }

    @Synchronized
    override fun claimRemoval(request: DifyRemovalClaimRequest): DifyProjectionClaim {
        val target = byExternal[ExternalKey(request.key, request.externalId)]
            ?: return DifyProjectionClaim(DifyProjectionClaimDisposition.NOT_FOUND, null, null)
        val active = current[request.key]
        if (active == null || active.externalId != request.externalId) {
            val superseded = snapshot(
                key = target.key,
                projectionId = target.projectionId,
                generation = target.generation,
                revision = target.revision + 1L,
                operation = DifyProjectionOperation.REMOVE,
                state = DifyProjectionIndexState.API_ABSENT,
                documentId = requireNotNull(target.documentId),
                batchId = target.batchId,
                sourceHash = target.sourceHash,
                idempotencyDigest = request.idempotencyKeyDigest,
                fingerprint = request.operationFingerprint,
            )
            return claim(DifyProjectionClaimDisposition.SUPERSEDED, superseded)
        }
        if (active.operation == DifyProjectionOperation.REMOVE) {
            val same = active.idempotencyKeyDigest == request.idempotencyKeyDigest &&
                active.operationFingerprint == request.operationFingerprint
            if (!same) {
                return claim(DifyProjectionClaimDisposition.CONFLICT, removalBinding(active, request))
            }
            return when (active.indexState) {
                DifyProjectionIndexState.CLAIMED -> claim(DifyProjectionClaimDisposition.IN_PROGRESS, active)
                DifyProjectionIndexState.UNKNOWN -> claim(DifyProjectionClaimDisposition.OUTCOME_UNKNOWN, active)
                DifyProjectionIndexState.REMOVAL_ACCEPTED,
                DifyProjectionIndexState.API_ABSENT,
                -> claim(DifyProjectionClaimDisposition.REPLAY, active)
                else -> claim(DifyProjectionClaimDisposition.CONFLICT, active)
            }
        }
        if (active.indexState == DifyProjectionIndexState.CLAIMED || active.indexState == DifyProjectionIndexState.UNKNOWN) {
            return claim(DifyProjectionClaimDisposition.CONFLICT, removalBinding(active, request))
        }
        val claimed = snapshot(
            key = active.key,
            projectionId = active.projectionId,
            generation = active.generation,
            revision = active.revision + 1L,
            operation = DifyProjectionOperation.REMOVE,
            state = DifyProjectionIndexState.CLAIMED,
            documentId = requireNotNull(active.documentId),
            batchId = active.batchId,
            sourceHash = active.sourceHash,
            idempotencyDigest = request.idempotencyKeyDigest,
            fingerprint = request.operationFingerprint,
        )
        return acquire(claimed, active, request.leaseDuration)
    }

    @Synchronized
    override fun abortBeforeRemote(lease: DifyProjectionLease): Boolean {
        val record = consumeExactLease(lease) ?: return false
        val externalKey = ExternalKey(lease.snapshot.key, lease.snapshot.externalId)
        if (record.previous == null) {
            current.remove(lease.snapshot.key)
            byExternal.remove(externalKey)
        } else {
            current[record.previous.key] = record.previous
            byExternal[ExternalKey(record.previous.key, record.previous.externalId)] = record.previous
            if (record.previous.externalId != lease.snapshot.externalId) byExternal.remove(externalKey)
        }
        return true
    }

    @Synchronized
    override fun completeSync(
        lease: DifyProjectionLease,
        documentId: String,
        batchId: String,
    ): DifyProjectionSnapshot? {
        if (consumeExactLease(lease) == null) return null
        val completed = copy(
            lease.snapshot,
            revision = lease.snapshot.revision + 1L,
            state = DifyProjectionIndexState.INDEXING,
            documentId = documentId,
            batchId = batchId,
        )
        save(completed)
        return completed
    }

    @Synchronized
    override fun completeRemoval(
        lease: DifyProjectionLease,
        state: DifyProjectionIndexState,
    ): DifyProjectionSnapshot? {
        require(state == DifyProjectionIndexState.REMOVAL_ACCEPTED || state == DifyProjectionIndexState.API_ABSENT)
        if (consumeExactLease(lease) == null) return null
        val completed = copy(lease.snapshot, revision = lease.snapshot.revision + 1L, state = state)
        save(completed)
        return completed
    }

    @Synchronized
    override fun markOutcomeUnknown(lease: DifyProjectionLease): Boolean {
        if (consumeExactLease(lease) == null) return false
        save(copy(lease.snapshot, revision = lease.snapshot.revision + 1L, state = DifyProjectionIndexState.UNKNOWN))
        return true
    }

    @Synchronized
    override fun reconcileUnknown(evidence: DifyProjectionReconciliationEvidence): DifyProjectionSnapshot? {
        val existing = byExternal[ExternalKey(evidence.key, evidence.externalId)] ?: return null
        if (
            existing.indexState != DifyProjectionIndexState.UNKNOWN ||
            existing.revision != evidence.expectedRevision ||
            existing.bindingDigest != evidence.expectedBindingDigest
        ) return null
        val state = when (evidence.resolution) {
            DifyProjectionReconciliationResolution.SYNC_ACCEPTED -> {
                if (existing.operation == DifyProjectionOperation.REMOVE) return null
                DifyProjectionIndexState.INDEXING
            }
            DifyProjectionReconciliationResolution.REMOVAL_ACCEPTED -> {
                if (existing.operation != DifyProjectionOperation.REMOVE) return null
                DifyProjectionIndexState.REMOVAL_ACCEPTED
            }
            DifyProjectionReconciliationResolution.API_ABSENT -> {
                if (existing.operation != DifyProjectionOperation.REMOVE) return null
                DifyProjectionIndexState.API_ABSENT
            }
        }
        if (existing.documentId != null && existing.documentId != evidence.documentId) return null
        val recovered = copy(
            existing,
            revision = existing.revision + 1L,
            state = state,
            documentId = evidence.documentId,
            batchId = if (state == DifyProjectionIndexState.INDEXING) evidence.batchId else existing.batchId,
        )
        save(recovered)
        return recovered
    }

    @Synchronized
    override fun findByExternalId(key: DifyProjectionKey, externalId: String): DifyProjectionSnapshot? =
        byExternal[ExternalKey(key, externalId)]

    @Synchronized
    override fun findCurrent(key: DifyProjectionKey): DifyProjectionSnapshot? = current[key]

    @Synchronized
    override fun recordObservedState(
        key: DifyProjectionKey,
        externalId: String,
        expectedRevision: Long,
        state: DifyProjectionIndexState,
    ): DifyProjectionSnapshot? {
        val existing = byExternal[ExternalKey(key, externalId)] ?: return null
        if (existing.revision != expectedRevision) return null
        val observed = copy(existing, revision = existing.revision + 1L, state = state)
        byExternal[ExternalKey(key, externalId)] = observed
        if (current[key]?.externalId == externalId) current[key] = observed
        return observed
    }

    @Synchronized
    override fun health(): DifyProjectionStoreHealth = healthResult

    @Synchronized
    fun currentSnapshot(key: DifyProjectionKey): DifyProjectionSnapshot? = findCurrent(key)

    private fun acquire(
        snapshot: DifyProjectionSnapshot,
        previous: DifyProjectionSnapshot?,
        leaseDuration: Duration,
    ): DifyProjectionClaim {
        val token = UUID.randomUUID().toString()
        val lease = DifyProjectionLease(snapshot, token, System.currentTimeMillis() + leaseDuration.toMillis())
        leases[token] = LeaseRecord(lease, previous)
        save(snapshot)
        return DifyProjectionClaim(DifyProjectionClaimDisposition.ACQUIRED, snapshot, lease)
    }

    private fun consumeExactLease(lease: DifyProjectionLease): LeaseRecord? {
        val record = leases[lease.leaseToken] ?: return null
        val active = current[lease.snapshot.key] ?: return null
        if (record.lease.snapshot.bindingDigest != lease.snapshot.bindingDigest ||
            active.bindingDigest != lease.snapshot.bindingDigest
        ) return null
        leases.remove(lease.leaseToken)
        return record
    }

    private fun save(snapshot: DifyProjectionSnapshot) {
        current[snapshot.key] = snapshot
        byExternal[ExternalKey(snapshot.key, snapshot.externalId)] = snapshot
    }

    private fun claim(disposition: DifyProjectionClaimDisposition, snapshot: DifyProjectionSnapshot) =
        DifyProjectionClaim(disposition, snapshot, null)

    private fun removalBinding(active: DifyProjectionSnapshot, request: DifyRemovalClaimRequest): DifyProjectionSnapshot =
        snapshot(
            key = active.key,
            projectionId = active.projectionId,
            generation = active.generation,
            revision = active.revision,
            operation = DifyProjectionOperation.REMOVE,
            state = active.indexState,
            documentId = requireNotNull(active.documentId),
            batchId = active.batchId,
            sourceHash = active.sourceHash,
            idempotencyDigest = request.idempotencyKeyDigest,
            fingerprint = request.operationFingerprint,
        )

    private fun copy(
        source: DifyProjectionSnapshot,
        revision: Long,
        state: DifyProjectionIndexState,
        documentId: String? = source.documentId,
        batchId: String? = source.batchId,
    ): DifyProjectionSnapshot = snapshot(
        key = source.key,
        projectionId = source.projectionId,
        generation = source.generation,
        revision = revision,
        operation = source.operation,
        state = state,
        documentId = documentId,
        batchId = batchId,
        sourceHash = source.sourceHash,
        idempotencyDigest = source.idempotencyKeyDigest,
        fingerprint = source.operationFingerprint,
    )

    private fun snapshot(
        key: DifyProjectionKey,
        projectionId: String,
        generation: Long,
        revision: Long,
        operation: DifyProjectionOperation,
        state: DifyProjectionIndexState,
        documentId: String?,
        batchId: String?,
        sourceHash: String,
        idempotencyDigest: String,
        fingerprint: String,
    ): DifyProjectionSnapshot = DifyProjectionSnapshot(
        key,
        projectionId,
        DifyProjectionExternalIds.create(projectionId, generation),
        generation,
        revision,
        operation,
        state,
        documentId,
        batchId,
        sourceHash,
        idempotencyDigest,
        fingerprint,
    )
}
