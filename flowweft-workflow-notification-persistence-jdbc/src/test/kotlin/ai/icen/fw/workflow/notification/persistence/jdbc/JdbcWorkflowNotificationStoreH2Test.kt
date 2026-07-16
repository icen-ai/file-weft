package ai.icen.fw.workflow.notification.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.runtime.WorkflowNotificationClaim
import ai.icen.fw.workflow.runtime.WorkflowNotificationCompletion
import ai.icen.fw.workflow.runtime.WorkflowNotificationDeliveryReport
import ai.icen.fw.workflow.runtime.WorkflowNotificationEnqueueBatch
import ai.icen.fw.workflow.runtime.WorkflowNotificationEnvelope
import ai.icen.fw.workflow.runtime.WorkflowNotificationLease
import ai.icen.fw.workflow.runtime.WorkflowNotificationProviderCheckpoint
import ai.icen.fw.workflow.runtime.WorkflowNotificationQueueStatus
import ai.icen.fw.workflow.runtime.WorkflowNotificationReconciliation
import ai.icen.fw.workflow.runtime.WorkflowNotificationReconciliationResolution
import ai.icen.fw.workflow.runtime.WorkflowNotificationReportMutation
import ai.icen.fw.workflow.runtime.WorkflowNotificationStoreCode
import ai.icen.fw.workflow.spi.WorkflowNotificationChannel
import ai.icen.fw.workflow.spi.WorkflowNotificationDelivery
import ai.icen.fw.workflow.spi.WorkflowNotificationIntent
import ai.icen.fw.workflow.spi.WorkflowNotificationTemplateRef
import ai.icen.fw.workflow.spi.WorkflowPayloadValidationReceipt
import ai.icen.fw.workflow.spi.WorkflowProviderCallContext
import ai.icen.fw.workflow.spi.WorkflowProviderReceipt
import ai.icen.fw.workflow.spi.WorkflowSchemaRef
import ai.icen.fw.workflow.spi.WorkflowStructuredPayload
import java.io.InputStreamReader
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.h2.jdbcx.JdbcDataSource
import org.h2.tools.RunScript
import org.junit.jupiter.api.Test

class JdbcWorkflowNotificationStoreH2Test {
    @Test
    fun `batch enqueue is atomic tenant scoped and exact replay only`() {
        val dataSource = dataSource()
        val store = JdbcWorkflowNotificationStore(dataSource, WorkflowNotificationJdbcDialect.MYSQL)
        val fixture = batch("origin-idem-1", "subject-a")

        val inserted = store.enqueue(fixture)
        assertSame(WorkflowNotificationStoreCode.APPLIED, inserted.code)
        assertSame(WorkflowNotificationQueueStatus.QUEUED, inserted.record!!.status)
        assertSame(WorkflowNotificationStoreCode.REPLAYED, store.enqueue(fixture).code)
        assertEquals(2, count(dataSource, "fw_wf_notification_envelope"))
        assertEquals(1, count(dataSource, "fw_wf_notification_batch"))
        assertNull(store.load("tenant-b", fixture.envelopes.first().envelopeId, 1_100L))

        val conflict = batch("origin-idem-1", "subject-b")
        assertSame(WorkflowNotificationStoreCode.CONFLICT, store.enqueue(conflict).code)
        assertEquals(2, count(dataSource, "fw_wf_notification_envelope"))
        assertEquals(1, count(dataSource, "fw_wf_notification_batch"))

        val otherTenant = WorkflowNotificationEnqueueBatch.of(
            "tenant-b",
            fixture.originIdempotencyKey,
            fixture.originIntentDigest,
            fixture.envelopes,
            fixture.authorizationEvidenceDigest,
            fixture.enqueuedAt,
        )
        assertSame(WorkflowNotificationStoreCode.APPLIED, store.enqueue(otherTenant).code)
        assertNotNull(store.load("tenant-b", fixture.envelopes.first().envelopeId, 1_100L))
        assertEquals(4, count(dataSource, "fw_wf_notification_envelope"))
        assertEquals(2, count(dataSource, "fw_wf_notification_batch"))

        val envelopeId = fixture.envelopes.last().envelopeId
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE fw_wf_notification_envelope SET envelope_digest = ? WHERE tenant_id = ? AND id = ?",
            ).use { statement ->
                statement.setString(1, DIGEST_F)
                statement.setString(2, "tenant-a")
                statement.setString(3, envelopeId)
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertFailsWith<IllegalArgumentException> { store.load("tenant-a", envelopeId, 1_100L) }
    }

    @Test
    fun `claim checkpoint accepted completion and delivery report are fenced and replayable`() {
        val dataSource = dataSource()
        val store = JdbcWorkflowNotificationStore(dataSource, WorkflowNotificationJdbcDialect.MYSQL)
        val batch = batch("origin-idem-2", "subject-a")
        store.enqueue(batch)
        val envelope = batch.envelopes.first()
        val lease = WorkflowNotificationLease.of("lease-1", "worker-1", 1L, 1_010L, 1_100L)
        val claim = WorkflowNotificationClaim.of("tenant-a", envelope.envelopeId, 0L, lease, DIGEST_A)

        assertSame(WorkflowNotificationStoreCode.APPLIED, store.claim(claim).code)
        assertSame(WorkflowNotificationStoreCode.REPLAYED, store.claim(claim).code)
        val staleLease = WorkflowNotificationLease.of("lease-stale", "worker-2", 1L, 1_011L, 1_101L)
        assertSame(
            WorkflowNotificationStoreCode.CONFLICT,
            store.claim(WorkflowNotificationClaim.of("tenant-a", envelope.envelopeId, 0L, staleLease, DIGEST_A)).code,
        )

        val checkpoint = WorkflowNotificationProviderCheckpoint.of(
            "tenant-a",
            envelope.envelopeId,
            1L,
            lease.leaseId,
            lease.fencingToken,
            DIGEST_D,
            DIGEST_B,
            1_020L,
        )
        assertSame(WorkflowNotificationStoreCode.APPLIED, store.checkpointProviderCall(checkpoint).code)
        assertSame(WorkflowNotificationStoreCode.REPLAYED, store.checkpointProviderCall(checkpoint).code)

        val delivery = WorkflowNotificationDelivery.accepted("message-1", DIGEST_C)
        val receipt = providerReceipt(
            envelope,
            "provider-request-1",
            DIGEST_D,
            delivery,
            1_021L,
            1_090L,
            1_030L,
            1_080L,
        )
        val completion = WorkflowNotificationCompletion.of(
            "tenant-a",
            envelope.envelopeId,
            2L,
            lease.leaseId,
            lease.fencingToken,
            WorkflowNotificationQueueStatus.ACCEPTED,
            receipt,
            delivery,
            DIGEST_E,
            null,
            1_040L,
        )
        val completed = store.complete(completion)
        assertSame(WorkflowNotificationStoreCode.APPLIED, completed.code)
        assertEquals(receipt.receiptDigest, completed.record!!.providerReceipt!!.receiptDigest)
        assertEquals(delivery.deliveryDigest, completed.record!!.delivery!!.deliveryDigest)
        assertSame(WorkflowNotificationStoreCode.REPLAYED, store.complete(completion).code)

        val staleCompletion = WorkflowNotificationCompletion.of(
            "tenant-a",
            envelope.envelopeId,
            2L,
            "other-lease",
            2L,
            WorkflowNotificationQueueStatus.OUTCOME_UNKNOWN,
            null,
            null,
            DIGEST_F,
            null,
            1_041L,
        )
        assertSame(WorkflowNotificationStoreCode.CONFLICT, store.complete(staleCompletion).code)

        val report = WorkflowNotificationDeliveryReport.of(
            "report-1",
            "tenant-a",
            envelope.envelopeId,
            receipt.providerId,
            receipt.providerRevision,
            "message-1",
            WorkflowNotificationQueueStatus.DELIVERED,
            DIGEST_F,
            1_060L,
        )
        val reportMutation = WorkflowNotificationReportMutation.of(report, 3L, DIGEST_A)
        val reported = store.recordDeliveryReport(reportMutation)
        assertSame(WorkflowNotificationStoreCode.APPLIED, reported.code)
        assertSame(WorkflowNotificationQueueStatus.DELIVERED, reported.record!!.status)
        assertSame(WorkflowNotificationStoreCode.REPLAYED, store.recordDeliveryReport(reportMutation).code)
        assertEquals(1, count(dataSource, "fw_wf_notification_delivery_report"))
    }

    @Test
    fun `started provider call cannot be reclaimed and reconciles only with exact evidence`() {
        val dataSource = dataSource()
        val store = JdbcWorkflowNotificationStore(dataSource, WorkflowNotificationJdbcDialect.MYSQL)
        val batch = batch("origin-idem-3", "subject-a")
        store.enqueue(batch)
        val envelope = batch.envelopes.first()
        val lease = WorkflowNotificationLease.of("lease-expiring", "worker-1", 7L, 2_010L, 2_050L)
        store.claim(WorkflowNotificationClaim.of("tenant-a", envelope.envelopeId, 0L, lease, DIGEST_A))
        store.checkpointProviderCall(WorkflowNotificationProviderCheckpoint.of(
            "tenant-a",
            envelope.envelopeId,
            1L,
            lease.leaseId,
            lease.fencingToken,
            DIGEST_D,
            DIGEST_B,
            2_020L,
        ))

        val attemptedReclaim = store.claim(WorkflowNotificationClaim.of(
            "tenant-a",
            envelope.envelopeId,
            2L,
            WorkflowNotificationLease.of("lease-new", "worker-2", 8L, 2_051L, 2_150L),
            DIGEST_A,
        ))
        assertSame(WorkflowNotificationStoreCode.NOT_ELIGIBLE, attemptedReclaim.code)

        val notSent = WorkflowNotificationReconciliation.of(
            "tenant-a",
            envelope.envelopeId,
            2L,
            WorkflowNotificationReconciliationResolution.NOT_SENT,
            null,
            null,
            DIGEST_E,
            DIGEST_F,
            2_080L,
            2_060L,
        )
        val reconciled = store.reconcile(notSent)
        assertSame(WorkflowNotificationStoreCode.APPLIED, reconciled.code)
        assertSame(WorkflowNotificationQueueStatus.RETRY_WAIT, reconciled.record!!.status)
        assertEquals(2_080L, reconciled.record!!.nextAttemptAt)
        assertSame(WorkflowNotificationStoreCode.REPLAYED, store.reconcile(notSent).code)

        val nextLease = WorkflowNotificationLease.of("lease-after-reconcile", "worker-3", 8L, 2_080L, 2_180L)
        val nextClaim = store.claim(WorkflowNotificationClaim.of(
            "tenant-a",
            envelope.envelopeId,
            3L,
            nextLease,
            DIGEST_A,
        ))
        assertSame(WorkflowNotificationStoreCode.APPLIED, nextClaim.code)
        assertEquals(2, nextClaim.record!!.attempt)
        assertEquals(8L, nextClaim.record!!.lease!!.fencingToken)

        val acceptedEnvelope = batch.envelopes.last()
        val acceptedLease = WorkflowNotificationLease.of("lease-accepted", "worker-4", 9L, 2_010L, 2_050L)
        store.claim(WorkflowNotificationClaim.of(
            "tenant-a",
            acceptedEnvelope.envelopeId,
            0L,
            acceptedLease,
            DIGEST_A,
        ))
        store.checkpointProviderCall(WorkflowNotificationProviderCheckpoint.of(
            "tenant-a",
            acceptedEnvelope.envelopeId,
            1L,
            acceptedLease.leaseId,
            acceptedLease.fencingToken,
            DIGEST_D,
            DIGEST_B,
            2_020L,
        ))
        val acceptedDelivery = WorkflowNotificationDelivery.accepted("message-reconciled", DIGEST_C)
        val acceptedReceipt = providerReceipt(
            acceptedEnvelope,
            "provider-request-reconciled",
            DIGEST_D,
            acceptedDelivery,
            2_021L,
            2_090L,
            2_030L,
            2_080L,
        )
        val acceptedReconciliation = WorkflowNotificationReconciliation.of(
            "tenant-a",
            acceptedEnvelope.envelopeId,
            2L,
            WorkflowNotificationReconciliationResolution.ACCEPTED,
            acceptedReceipt,
            acceptedDelivery,
            DIGEST_E,
            DIGEST_F,
            null,
            2_060L,
        )
        val accepted = store.reconcile(acceptedReconciliation)
        assertSame(WorkflowNotificationStoreCode.APPLIED, accepted.code)
        assertSame(WorkflowNotificationQueueStatus.ACCEPTED, accepted.record!!.status)
        assertEquals(acceptedReceipt.receiptDigest, accepted.record!!.providerReceipt!!.receiptDigest)
        assertEquals(acceptedDelivery.deliveryDigest, accepted.record!!.delivery!!.deliveryDigest)
    }

    @Test
    fun `outcome unknown retry and bounce transitions remain durable and fenced`() {
        val dataSource = dataSource()
        val store = JdbcWorkflowNotificationStore(dataSource, WorkflowNotificationJdbcDialect.MYSQL)
        val batch = batch("origin-idem-4", "subject-a")
        store.enqueue(batch)

        val uncertain = batch.envelopes.first()
        val uncertainLease = WorkflowNotificationLease.of("lease-unknown", "worker-1", 11L, 3_010L, 3_100L)
        store.claim(WorkflowNotificationClaim.of(
            "tenant-a", uncertain.envelopeId, 0L, uncertainLease, DIGEST_A,
        ))
        store.checkpointProviderCall(WorkflowNotificationProviderCheckpoint.of(
            "tenant-a", uncertain.envelopeId, 1L, uncertainLease.leaseId,
            uncertainLease.fencingToken, DIGEST_D, DIGEST_B, 3_020L,
        ))
        val outcomeUnknown = WorkflowNotificationCompletion.of(
            "tenant-a",
            uncertain.envelopeId,
            2L,
            uncertainLease.leaseId,
            uncertainLease.fencingToken,
            WorkflowNotificationQueueStatus.OUTCOME_UNKNOWN,
            null,
            null,
            DIGEST_E,
            null,
            3_040L,
        )
        assertSame(WorkflowNotificationQueueStatus.OUTCOME_UNKNOWN, store.complete(outcomeUnknown).record!!.status)
        assertSame(WorkflowNotificationStoreCode.REPLAYED, store.complete(outcomeUnknown).code)
        val retry = WorkflowNotificationReconciliation.of(
            "tenant-a",
            uncertain.envelopeId,
            3L,
            WorkflowNotificationReconciliationResolution.NOT_SENT,
            null,
            null,
            DIGEST_F,
            DIGEST_A,
            3_080L,
            3_060L,
        )
        assertSame(WorkflowNotificationQueueStatus.RETRY_WAIT, store.reconcile(retry).record!!.status)
        val retried = store.claim(WorkflowNotificationClaim.of(
            "tenant-a",
            uncertain.envelopeId,
            4L,
            WorkflowNotificationLease.of("lease-retry", "worker-2", 12L, 3_080L, 3_180L),
            DIGEST_B,
        ))
        assertSame(WorkflowNotificationStoreCode.APPLIED, retried.code)
        assertEquals(2, retried.record!!.attempt)

        val bounced = batch.envelopes.last()
        val bounceLease = WorkflowNotificationLease.of("lease-bounce", "worker-3", 20L, 4_010L, 4_100L)
        store.claim(WorkflowNotificationClaim.of("tenant-a", bounced.envelopeId, 0L, bounceLease, DIGEST_A))
        store.checkpointProviderCall(WorkflowNotificationProviderCheckpoint.of(
            "tenant-a", bounced.envelopeId, 1L, bounceLease.leaseId,
            bounceLease.fencingToken, DIGEST_D, DIGEST_B, 4_020L,
        ))
        val acceptedDelivery = WorkflowNotificationDelivery.accepted("message-bounce", DIGEST_C)
        val acceptedReceipt = providerReceipt(
            bounced, "provider-request-bounce", DIGEST_D, acceptedDelivery,
            4_021L, 4_090L, 4_030L, 4_080L,
        )
        store.complete(WorkflowNotificationCompletion.of(
            "tenant-a", bounced.envelopeId, 2L, bounceLease.leaseId, bounceLease.fencingToken,
            WorkflowNotificationQueueStatus.ACCEPTED, acceptedReceipt, acceptedDelivery,
            DIGEST_E, null, 4_040L,
        ))
        val transientBounce = WorkflowNotificationDeliveryReport.of(
            "report-transient", "tenant-a", bounced.envelopeId,
            acceptedReceipt.providerId, acceptedReceipt.providerRevision, "message-bounce",
            WorkflowNotificationQueueStatus.TRANSIENT_BOUNCE, DIGEST_F, 4_050L,
        )
        val transientResult = store.recordDeliveryReport(
            WorkflowNotificationReportMutation.of(transientBounce, 3L, DIGEST_A),
        )
        assertSame(WorkflowNotificationQueueStatus.TRANSIENT_BOUNCE, transientResult.record!!.status)
        val permanentBounce = WorkflowNotificationDeliveryReport.of(
            "report-permanent", "tenant-a", bounced.envelopeId,
            acceptedReceipt.providerId, acceptedReceipt.providerRevision, "message-bounce",
            WorkflowNotificationQueueStatus.PERMANENT_BOUNCE, DIGEST_B, 4_060L,
        )
        val permanentResult = store.recordDeliveryReport(
            WorkflowNotificationReportMutation.of(permanentBounce, 4L, DIGEST_C),
        )
        assertSame(WorkflowNotificationQueueStatus.PERMANENT_BOUNCE, permanentResult.record!!.status)
        assertEquals(2, count(dataSource, "fw_wf_notification_delivery_report"))
    }

    private fun batch(originIdempotencyKey: String, safeValue: String): WorkflowNotificationEnqueueBatch {
        val schema = WorkflowSchemaRef.of("schema-provider", "notification-fields", "1", DIGEST_A)
        val raw = WorkflowStructuredPayload.of(
            schema,
            "{\"subject\":\"$safeValue\"}".toByteArray(Charsets.UTF_8),
        )
        val validation = WorkflowPayloadValidationReceipt.of(
            "schema-validator",
            "validator-r1",
            schema,
            raw.canonicalPayloadDigest,
            1,
            DIGEST_B,
        )
        val intent = WorkflowNotificationIntent.of(
            "origin-intent-1",
            originIdempotencyKey,
            WorkflowNotificationTemplateRef.of("notification-provider", "approval", "3", DIGEST_C),
            WorkflowNotificationChannel.EMAIL,
            listOf(
                WorkflowPrincipalRef.of("user", "user-1"),
                WorkflowPrincipalRef.of("user", "user-2"),
            ),
            null,
            WorkflowStructuredPayload.validated(raw, validation),
            1_000L,
        )
        val envelopes = WorkflowNotificationEnvelope.fanOut(
            intent,
            WorkflowPrincipalRef.of("user", "issuer-1"),
            1_001L,
        )
        return WorkflowNotificationEnqueueBatch.of(
            "tenant-a",
            originIdempotencyKey,
            intent.intentDigest,
            envelopes,
            DIGEST_D,
            1_001L,
        )
    }

    private fun providerReceipt(
        envelope: WorkflowNotificationEnvelope,
        requestId: String,
        requestDigest: String,
        delivery: WorkflowNotificationDelivery,
        requestedAt: Long,
        deadline: Long,
        completedAt: Long,
        expiresAt: Long,
    ): WorkflowProviderReceipt = WorkflowProviderReceipt.success(
        WorkflowProviderCallContext.of(
            requestId,
            "tenant-a",
            envelope.intent.template.providerId,
            "provider-r1",
            "notification-send",
            requestedAt,
            deadline,
            4_096,
            4_096,
            1,
        ),
        requestDigest,
        delivery.deliveryDigest,
        completedAt,
        expiresAt,
    )

    private fun dataSource(): JdbcDataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:workflow-notification-${System.nanoTime()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
        user = "sa"
        password = ""
        connection.use { connection ->
            val path = "/ai/icen/fw/workflow/notification/db/migration/mysql/V035__persist_workflow_notification_lifecycle.sql"
            val resource = requireNotNull(JdbcWorkflowNotificationStoreH2Test::class.java.getResourceAsStream(path))
            InputStreamReader(resource, Charsets.UTF_8).use { reader -> RunScript.execute(connection, reader) }
        }
    }

    private fun count(dataSource: JdbcDataSource, table: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private companion object {
        val DIGEST_A = "a".repeat(64)
        val DIGEST_B = "b".repeat(64)
        val DIGEST_C = "c".repeat(64)
        val DIGEST_D = "d".repeat(64)
        val DIGEST_E = "e".repeat(64)
        val DIGEST_F = "f".repeat(64)
    }
}
