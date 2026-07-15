package ai.icen.fw.adapter.dify

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.connector.ConnectorExternalState
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorStatusQueryStatus
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class DifyKnowledgeBaseConnectorTest {
    @Test
    fun `accepted create replays without another download or remote write`() {
        val fixture = Fixture()
        val request = syncRequest()

        val first = fixture.connector.sync(request)
        val replay = fixture.connector.sync(request)

        assertEquals(ConnectorSyncStatus.SUCCESS, first.status)
        assertEquals(first.externalId, replay.externalId)
        assertEquals(1, fixture.downloader.calls)
        assertEquals(1, fixture.remote.createCalls)
        assertEquals(0, fixture.remote.updateCalls)
    }

    @Test
    fun `changed synchronization creates a new generation through canonical update`() {
        val fixture = Fixture()
        val first = fixture.connector.sync(syncRequest())

        val updated = fixture.connector.sync(
            syncRequest(idempotencyKey = "sync-2", fileName = "hello-v2.txt"),
        )

        assertEquals(ConnectorSyncStatus.SUCCESS, updated.status)
        assertNotEquals(first.externalId, updated.externalId)
        assertTrue(requireNotNull(updated.externalId).endsWith(":2"))
        assertEquals(1, fixture.remote.createCalls)
        assertEquals(1, fixture.remote.updateCalls)
    }

    @Test
    fun `ambiguous create is fenced and never blindly replayed`() {
        val fixture = Fixture()
        fixture.remote.createResult = DifyWriteResult(DifyWriteDisposition.OUTCOME_UNKNOWN)
        val request = syncRequest()

        val first = fixture.connector.sync(request)
        val replay = fixture.connector.sync(request)

        assertEquals(ConnectorSyncStatus.PERMANENT_FAILURE, first.status)
        assertEquals(ConnectorSyncStatus.PERMANENT_FAILURE, replay.status)
        assertEquals(DifyKnowledgeBaseConnector.UNKNOWN_OUTCOME_MESSAGE, first.message)
        assertEquals(1, fixture.remote.createCalls)
        assertEquals(1, fixture.downloader.calls)
    }

    @Test
    fun `only a write proven not sent releases the claim while issued failure remains unknown`() {
        val notSent = Fixture()
        notSent.remote.createResult = DifyWriteResult(DifyWriteDisposition.NOT_SENT)

        val first = notSent.connector.sync(syncRequest())
        val retry = notSent.connector.sync(syncRequest())

        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, first.status)
        assertEquals(ConnectorSyncStatus.RETRYABLE_FAILURE, retry.status)
        assertEquals(2, notSent.remote.createCalls)

        val ambiguous = Fixture()
        ambiguous.remote.createResult = DifyWriteResult(DifyWriteDisposition.OUTCOME_UNKNOWN)
        ambiguous.connector.sync(syncRequest())
        ambiguous.connector.sync(syncRequest())
        assertEquals(1, ambiguous.remote.createCalls)
    }

    @Test
    fun `dedicated profile rejects another tenant before source store or remote access`() {
        val fixture = Fixture()

        val result = fixture.connector.sync(syncRequest(tenant = "tenant-b"))

        assertEquals(ConnectorSyncStatus.PERMANENT_FAILURE, result.status)
        assertEquals(0, fixture.downloader.calls)
        assertEquals(0, fixture.remote.createCalls)
        assertEquals(0, fixture.remote.updateCalls)
    }

    @Test
    fun `rejects an unbounded business identity before store download or remote access`() {
        val fixture = Fixture()

        val result = fixture.connector.sync(syncRequest(business = "x".repeat(513)))

        assertEquals(ConnectorSyncStatus.PERMANENT_FAILURE, result.status)
        assertEquals(0, fixture.downloader.calls)
        assertEquals(0, fixture.remote.createCalls)
        assertEquals(null, fixture.store.currentSnapshot(projectionKey(fixture.profile)))
    }

    @Test
    fun `status binds external identity to the exact tenant and business before remote access`() {
        val fixture = Fixture()
        val externalId = requireNotNull(fixture.connector.sync(syncRequest()).externalId)

        val wrongTenant = fixture.connector.status(statusRequest(externalId, tenant = "tenant-b"))
        val wrongBusiness = fixture.connector.status(statusRequest(externalId, business = "document-b"))

        assertEquals(ConnectorStatusQueryStatus.PERMANENT_FAILURE, wrongTenant.queryStatus)
        assertEquals(ConnectorExternalState.UNKNOWN, wrongTenant.state)
        assertEquals(ConnectorStatusQueryStatus.PERMANENT_FAILURE, wrongBusiness.queryStatus)
        assertEquals(0, fixture.remote.indexingCalls)
        assertEquals(0, fixture.remote.documentCalls)
    }

    @Test
    fun `missing batch status falls back to the exact document instead of claiming absence`() {
        val fixture = Fixture()
        val externalId = requireNotNull(fixture.connector.sync(syncRequest()).externalId)
        fixture.remote.indexingResult = DifyStatusReadResult(DifyReadDisposition.API_ABSENT)
        fixture.remote.documentResult = DifyStatusReadResult(
            DifyReadDisposition.SUCCESS,
            DifyProjectionIndexState.AVAILABLE,
        )

        val status = fixture.connector.status(statusRequest(externalId))

        assertEquals(ConnectorStatusQueryStatus.SUCCESS, status.queryStatus)
        assertEquals(ConnectorExternalState.AVAILABLE, status.state)
        assertEquals(1, fixture.remote.indexingCalls)
        assertEquals(1, fixture.remote.documentCalls)
    }

    @Test
    fun `rejects unsafe media types and leases that cannot cover the invocation before egress`() {
        val unsafeMedia = Fixture()
        val invalidType = unsafeMedia.connector.sync(syncRequest(contentType = "text/plain\r\nx-secret: value"))

        assertEquals(ConnectorSyncStatus.PERMANENT_FAILURE, invalidType.status)
        assertEquals(0, unsafeMedia.downloader.calls)
        assertEquals(0, unsafeMedia.remote.createCalls)

        val shortLeaseProfile = testProfile(projectionLeaseDuration = Duration.ofSeconds(1))
        val shortLeaseDownloader = TestDifySourceDownloader()
        val shortLeaseRemote = TestDifyRemoteApi()
        val shortLeaseConnector = DifyKnowledgeBaseConnector(
            shortLeaseProfile,
            InMemoryDifyProjectionStore(),
            shortLeaseDownloader,
            shortLeaseRemote,
        )

        val invalidLease = shortLeaseConnector.sync(syncRequest(timeout = Duration.ofSeconds(2)))

        assertEquals(ConnectorSyncStatus.PERMANENT_FAILURE, invalidLease.status)
        assertEquals(0, shortLeaseDownloader.calls)
        assertEquals(0, shortLeaseRemote.createCalls)

        val enormousTimeout = shortLeaseConnector.sync(syncRequest(timeout = Duration.ofSeconds(Long.MAX_VALUE)))
        assertEquals(ConnectorSyncStatus.PERMANENT_FAILURE, enormousTimeout.status)
        assertEquals(0, shortLeaseDownloader.calls)
        assertEquals(0, shortLeaseRemote.createCalls)
    }

    @Test
    fun `delete acceptance and API absence never claim physical purge`() {
        val fixture = Fixture()
        val externalId = requireNotNull(fixture.connector.sync(syncRequest()).externalId)

        val removed = fixture.connector.remove(removalRequest(externalId))
        fixture.remote.documentResult = DifyStatusReadResult(DifyReadDisposition.API_ABSENT)
        val status = fixture.connector.status(statusRequest(externalId))

        assertEquals(ConnectorSyncStatus.SUCCESS, removed.status)
        assertEquals(1, fixture.remote.deleteCalls)
        assertEquals(ConnectorStatusQueryStatus.SUCCESS, status.queryStatus)
        assertEquals(ConnectorExternalState.API_ABSENT, status.state)
        assertTrue(requireNotNull(status.message).contains("physical purge is not verifiable"))
    }

    @Test
    fun `removing a stale generation preserves the active document`() {
        val fixture = Fixture()
        val first = fixture.connector.sync(syncRequest())
        val second = fixture.connector.sync(syncRequest(idempotencyKey = "sync-2", fileName = "hello-v2.txt"))
        assertNotEquals(first.externalId, second.externalId)

        val staleRemoval = fixture.connector.remove(removalRequest(requireNotNull(first.externalId)))

        assertEquals(ConnectorSyncStatus.SUCCESS, staleRemoval.status)
        assertEquals(0, fixture.remote.deleteCalls)
        val key = DifyProjectionKey(
            Identifier("tenant-a"),
            Identifier("document-a"),
            "dify-main",
            TEST_DATASET_ID,
            fixture.profile.targetBindingDigest,
        )
        assertEquals(second.externalId, fixture.store.currentSnapshot(key)?.externalId)
    }

    @Test
    fun `health distinguishes temporary remote failure and redacts configuration`() {
        val fixture = Fixture()
        fixture.remote.healthResult = DifyHealthReadResult(DifyReadDisposition.RETRYABLE_FAILURE)

        val health = fixture.connector.health()

        assertEquals(ConnectorHealthStatus.DEGRADED, health.status)
        val message = requireNotNull(health.message)
        assertTrue("dify.example.test" !in message)
        assertTrue(TEST_DATASET_ID !in message)
        assertTrue("secret" !in message.lowercase())
    }

    private class Fixture {
        val profile = testProfile()
        val store = InMemoryDifyProjectionStore()
        val downloader = TestDifySourceDownloader()
        val remote = TestDifyRemoteApi()
        val connector = DifyKnowledgeBaseConnector(profile, store, downloader, remote)
    }

    private fun projectionKey(profile: DifyKnowledgeBaseProfile): DifyProjectionKey = DifyProjectionKey(
        Identifier("tenant-a"),
        Identifier("document-a"),
        profile.profileId,
        profile.datasetId,
        profile.targetBindingDigest,
    )
}
