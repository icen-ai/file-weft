package ai.icen.fw.adapter.dify

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DifyProjectionReconciliationTest {
    @Test
    fun `authorized exact evidence recovers unknown sync while unauthorized and stale evidence fail closed`() {
        val store = InMemoryDifyProjectionStore()
        val remote = TestDifyRemoteApi().also {
            it.createResult = DifyWriteResult(DifyWriteDisposition.OUTCOME_UNKNOWN)
        }
        val connector = DifyKnowledgeBaseConnector(testProfile(), store, TestDifySourceDownloader(), remote)

        assertEquals(ConnectorSyncStatus.PERMANENT_FAILURE, connector.sync(syncRequest()).status)
        val unknown = requireNotNull(store.currentSnapshot(projectionKey()))
        val evidence = syncEvidence(unknown)

        val denied = DifyProjectionReconciliationService(store, authority(false)).reconcile(evidence)
        assertEquals(DifyProjectionReconciliationStatus.UNAUTHORIZED, denied.status)
        assertEquals(DifyProjectionIndexState.UNKNOWN, store.currentSnapshot(projectionKey())?.indexState)

        val recovered = DifyProjectionReconciliationService(store, authority(true)).reconcile(evidence)
        assertEquals(DifyProjectionReconciliationStatus.RECOVERED, recovered.status)
        assertEquals(DifyProjectionIndexState.INDEXING, recovered.snapshot?.indexState)
        assertEquals(TEST_DOCUMENT_ID, recovered.snapshot?.documentId)
        assertEquals(TEST_BATCH_ID, recovered.snapshot?.batchId)
        assertNotEquals(unknown.bindingDigest, recovered.snapshot?.bindingDigest)

        val staleReplay = DifyProjectionReconciliationService(store, authority(true)).reconcile(evidence)
        assertEquals(DifyProjectionReconciliationStatus.STALE_EVIDENCE, staleReplay.status)
    }

    @Test
    fun `authoritative API absence can recover only an exact unknown removal`() {
        val store = InMemoryDifyProjectionStore()
        val remote = TestDifyRemoteApi()
        val connector = DifyKnowledgeBaseConnector(testProfile(), store, TestDifySourceDownloader(), remote)
        val externalId = requireNotNull(connector.sync(syncRequest()).externalId)
        remote.deleteResult = DifyDeleteDisposition.AMBIGUOUS
        remote.documentResult = DifyStatusReadResult(
            DifyReadDisposition.SUCCESS,
            DifyProjectionIndexState.AVAILABLE,
        )

        assertEquals(ConnectorSyncStatus.PERMANENT_FAILURE, connector.remove(removalRequest(externalId)).status)
        val unknown = requireNotNull(store.currentSnapshot(projectionKey()))
        val evidence = DifyProjectionReconciliationEvidence(
            unknown.key,
            unknown.externalId,
            unknown.revision,
            unknown.bindingDigest,
            DifyProjectionReconciliationResolution.API_ABSENT,
            requireNotNull(unknown.documentId),
            null,
            EVIDENCE_DIGEST,
            1L,
        )

        val recovered = DifyProjectionReconciliationService(store, authority(true)).reconcile(evidence)

        assertEquals(DifyProjectionReconciliationStatus.RECOVERED, recovered.status)
        assertEquals(DifyProjectionIndexState.API_ABSENT, recovered.snapshot?.indexState)
        assertEquals(1, remote.deleteCalls)
    }

    @Test
    fun `claim rejects lease snapshot with any different binding field`() {
        val projectionId = "44444444-4444-4444-4444-444444444444"
        val first = claimedSnapshot(projectionKey(), projectionId)
        val otherTenant = claimedSnapshot(
            DifyProjectionKey(
                Identifier("tenant-b"),
                Identifier("document-a"),
                "dify-main",
                TEST_DATASET_ID,
                testProfile().targetBindingDigest,
            ),
            projectionId,
        )
        assertNotEquals(first.bindingDigest, otherTenant.bindingDigest)

        val lease = DifyProjectionLease(otherTenant, "lease-token", Long.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) {
            DifyProjectionClaim(DifyProjectionClaimDisposition.ACQUIRED, first, lease)
        }
    }

    @Test
    fun `projection snapshots reject observed states without identities and impossible removal indexing`() {
        assertThrows(IllegalArgumentException::class.java) {
            projectionSnapshot(DifyProjectionOperation.CREATE, DifyProjectionIndexState.AVAILABLE, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            projectionSnapshot(
                DifyProjectionOperation.REMOVE,
                DifyProjectionIndexState.INDEXING,
                TEST_DOCUMENT_ID,
                TEST_BATCH_ID,
            )
        }
    }

    private fun syncEvidence(snapshot: DifyProjectionSnapshot): DifyProjectionReconciliationEvidence =
        DifyProjectionReconciliationEvidence(
            snapshot.key,
            snapshot.externalId,
            snapshot.revision,
            snapshot.bindingDigest,
            DifyProjectionReconciliationResolution.SYNC_ACCEPTED,
            TEST_DOCUMENT_ID,
            TEST_BATCH_ID,
            EVIDENCE_DIGEST,
            1L,
        )

    private fun authority(authorized: Boolean): DifyProjectionReconciliationAuthority =
        object : DifyProjectionReconciliationAuthority {
            override fun authorize(
                evidence: DifyProjectionReconciliationEvidence,
                snapshot: DifyProjectionSnapshot,
            ): Boolean = authorized && evidence.expectedBindingDigest == snapshot.bindingDigest
        }

    private fun projectionKey(): DifyProjectionKey = DifyProjectionKey(
        Identifier("tenant-a"),
        Identifier("document-a"),
        "dify-main",
        TEST_DATASET_ID,
        testProfile().targetBindingDigest,
    )

    private fun claimedSnapshot(
        key: DifyProjectionKey,
        projectionId: String,
    ): DifyProjectionSnapshot = DifyProjectionSnapshot(
        key,
        projectionId,
        DifyProjectionExternalIds.create(projectionId, 1L),
        1L,
        1L,
        DifyProjectionOperation.CREATE,
        DifyProjectionIndexState.CLAIMED,
        null,
        null,
        TEST_SOURCE_HASH,
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    )

    private fun projectionSnapshot(
        operation: DifyProjectionOperation,
        state: DifyProjectionIndexState,
        documentId: String?,
        batchId: String?,
    ): DifyProjectionSnapshot {
        val projectionId = "55555555-5555-5555-5555-555555555555"
        return DifyProjectionSnapshot(
            projectionKey(),
            projectionId,
            DifyProjectionExternalIds.create(projectionId, 1L),
            1L,
            1L,
            operation,
            state,
            documentId,
            batchId,
            TEST_SOURCE_HASH,
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        )
    }

    private companion object {
        const val EVIDENCE_DIGEST =
            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
