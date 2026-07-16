package ai.icen.fw.workflow.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowCommentDocument
import ai.icen.fw.workflow.api.WorkflowCommentSnapshot
import ai.icen.fw.workflow.api.WorkflowCommentToken
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessDecision
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessMode
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessReport
import ai.icen.fw.workflow.api.WorkflowFormFieldPath
import ai.icen.fw.workflow.api.WorkflowFormSubmissionRef
import ai.icen.fw.workflow.api.WorkflowFormVersionRef
import ai.icen.fw.workflow.api.WorkflowInstanceRef
import ai.icen.fw.workflow.api.WorkflowJsonSchemaDialect
import ai.icen.fw.workflow.api.WorkflowJsonSchemaRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowWorkItemRef
import ai.icen.fw.workflow.runtime.WorkflowHumanInputIdempotencyRecord
import ai.icen.fw.workflow.runtime.WorkflowHumanInputIdempotencyWriteCode
import ai.icen.fw.workflow.runtime.WorkflowHumanInputOperation
import ai.icen.fw.workflow.runtime.WorkflowHumanInputReservationCode
import ai.icen.fw.workflow.runtime.WorkflowHumanInputReservationRequest
import ai.icen.fw.workflow.runtime.WorkflowMentionNotificationCheckpointCode
import ai.icen.fw.workflow.runtime.WorkflowMentionNotificationCheckpointStatus
import ai.icen.fw.workflow.runtime.WorkflowMentionNotificationOutcomeUnknown
import ai.icen.fw.workflow.runtime.WorkflowMentionNotificationProviderCheckpoint
import ai.icen.fw.workflow.runtime.WorkflowMentionNotificationReconciliation
import ai.icen.fw.workflow.runtime.WorkflowMentionNotificationReconciliationResolution
import ai.icen.fw.workflow.runtime.WorkflowRuntimeValidatedForm
import ai.icen.fw.workflow.spi.WorkflowFormValidationOperation
import ai.icen.fw.workflow.spi.WorkflowNotificationDelivery
import ai.icen.fw.workflow.spi.WorkflowPayloadValidationReceipt
import ai.icen.fw.workflow.spi.WorkflowProviderCallContext
import ai.icen.fw.workflow.spi.WorkflowProviderReceipt
import ai.icen.fw.workflow.spi.WorkflowSchemaRef
import ai.icen.fw.workflow.spi.WorkflowSecureFormValidationReport
import ai.icen.fw.workflow.spi.WorkflowStructuredPayload
import java.io.InputStreamReader
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.h2.jdbcx.JdbcDataSource
import org.h2.tools.RunScript
import org.junit.jupiter.api.Test

class JdbcWorkflowHumanInputStoreH2Test {
    @Test
    fun `form reservation completion and replay preserve exact provider evidence`() {
        val dataSource = dataSource()
        val store = JdbcWorkflowHumanInputStore(dataSource, WorkflowJdbcDialect.MYSQL)
        val fixture = formRecord()
        val request = reservation(
            "tenant-a",
            "form-idem-1",
            WorkflowHumanInputOperation.FORM_VALIDATE,
            DIGEST_E,
            1_000L,
            1_100L,
        )

        val reserved = store.reserve(request)
        assertSame(WorkflowHumanInputReservationCode.RESERVED, reserved.code)
        val completed = store.complete(assertNotNull(reserved.reservation), fixture)
        assertSame(WorkflowHumanInputIdempotencyWriteCode.STORED, completed.code)

        val replay = store.reserve(reservation(
            "tenant-a",
            "form-idem-1",
            WorkflowHumanInputOperation.FORM_VALIDATE,
            DIGEST_E,
            1_021L,
            1_121L,
        ))
        assertSame(WorkflowHumanInputReservationCode.REPLAYED, replay.code)
        val replayed = assertNotNull(replay.record).validatedForm!!
        assertEquals(fixture.resultDigest, replay.record!!.resultDigest)
        assertEquals(
            fixture.validatedForm!!.providerReceipt.receiptDigest,
            replayed.providerReceipt.receiptDigest,
        )
        assertEquals(
            fixture.validatedForm!!.providerReceipt.contextDigest,
            replayed.providerReceipt.restoreContext().contextDigest,
        )
        assertEquals(fixture.validatedForm!!.normalizedSubmission, replayed.normalizedSubmission)

        val submission = assertNotNull(store.loadFormSubmission("tenant-a", "submission-1", 7L))
        assertEquals(fixture.validatedForm!!.submission!!.submissionDigest, submission.submissionDigest)
        assertNull(store.loadFormSubmission("tenant-b", "submission-1", 7L))

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT provider_receipt_digest, receipt_expires_time, record_version, fencing_token
                    FROM fw_wf_human_input_idem WHERE tenant_id = ? AND idempotency_key = ?""",
            ).use { statement ->
                statement.setString(1, "tenant-a")
                statement.setString(2, "form-idem-1")
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(fixture.validatedForm!!.providerReceipt.receiptDigest, result.getString(1))
                    assertEquals(1_080L, result.getLong(2))
                    assertEquals(2L, result.getLong(3))
                    assertEquals(1L, result.getLong(4))
                }
            }
        }
    }

    @Test
    fun `fencing protects structured comments and notification replay retains its receipt`() {
        val dataSource = dataSource()
        val store = JdbcWorkflowHumanInputStore(dataSource, WorkflowJdbcDialect.MYSQL)
        val request = reservation(
            "tenant-a",
            "comment-idem-1",
            WorkflowHumanInputOperation.COMMENT_CREATE,
            DIGEST_F,
            2_000L,
            2_010L,
        )
        val first = assertNotNull(store.reserve(request).reservation)
        val secondResult = store.reserve(reservation(
            "tenant-a",
            "comment-idem-1",
            WorkflowHumanInputOperation.COMMENT_CREATE,
            DIGEST_F,
            2_010L,
            2_100L,
        ))
        assertSame(WorkflowHumanInputReservationCode.RESERVED, secondResult.code)
        val second = assertNotNull(secondResult.reservation)
        assertEquals(first.fencingToken + 1L, second.fencingToken)

        val stale = store.complete(first, commentRecord(2_009L))
        assertSame(WorkflowHumanInputIdempotencyWriteCode.CONFLICT, stale.code)
        val current = commentRecord(2_011L)
        assertSame(WorkflowHumanInputIdempotencyWriteCode.STORED, store.complete(second, current).code)

        val comment = assertNotNull(store.loadComment("tenant-a", "comment-1", 3L))
        assertEquals("literal <b>text</b>", comment.document.tokens.first().text)
        assertEquals("user-mentioned", comment.document.tokens.last().principal!!.id)
        assertNull(store.loadComment("tenant-b", "comment-1", 3L))

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT token_kind, text_content, principal_id
                    FROM fw_wf_structured_comment_token
                    WHERE tenant_id = ? AND comment_id = ? ORDER BY token_ordinal""",
            ).use { statement ->
                statement.setString(1, "tenant-a")
                statement.setString(2, "comment-1")
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("text", result.getString("token_kind"))
                    assertEquals("literal <b>text</b>", result.getString("text_content"))
                    assertNull(result.getString("principal_id"))
                    assertTrue(result.next())
                    assertEquals("mention", result.getString("token_kind"))
                    assertNull(result.getString("text_content"))
                    assertEquals("user-mentioned", result.getString("principal_id"))
                }
            }
        }

        val delivery = WorkflowNotificationDelivery.accepted("provider-message-1", DIGEST_A)
        val receiptContext = WorkflowProviderCallContext.of(
            "notify-request-1",
            "tenant-a",
            "notification-provider",
            "revision-4",
            "mention-notification",
            3_010L,
            3_090L,
            4_096,
            4_096,
            10,
        )
        val notificationReceipt = WorkflowProviderReceipt.success(
            receiptContext,
            DIGEST_B,
            delivery.deliveryDigest,
            3_020L,
            3_080L,
        )
        val notificationRecord = WorkflowHumanInputIdempotencyRecord.notification(
            "tenant-a",
            "notify-idem-1",
            DIGEST_C,
            delivery,
            notificationReceipt,
            3_030L,
        )
        val notificationReservation = assertNotNull(store.reserve(reservation(
            "tenant-a",
            "notify-idem-1",
            WorkflowHumanInputOperation.MENTION_NOTIFY,
            DIGEST_C,
            3_000L,
            3_100L,
        )).reservation)
        val providerCheckpoint = store.checkpointProviderCall(
            WorkflowMentionNotificationProviderCheckpoint.of(notificationReservation, DIGEST_B, 3_010L),
        )
        assertSame(WorkflowMentionNotificationCheckpointCode.APPLIED, providerCheckpoint.code)
        val accepted = store.reconcileProviderCall(WorkflowMentionNotificationReconciliation.of(
            assertNotNull(providerCheckpoint.checkpoint),
            WorkflowMentionNotificationReconciliationResolution.ACCEPTED,
            notificationRecord,
            notificationReceipt.receiptDigest,
            3_030L,
        ))
        assertSame(WorkflowMentionNotificationCheckpointCode.APPLIED, accepted.code)
        assertSame(WorkflowMentionNotificationCheckpointStatus.ACCEPTED, accepted.checkpoint!!.status)

        val notificationReplay = store.reserve(reservation(
            "tenant-a",
            "notify-idem-1",
            WorkflowHumanInputOperation.MENTION_NOTIFY,
            DIGEST_C,
            3_040L,
            3_120L,
        ))
        assertSame(WorkflowHumanInputReservationCode.REPLAYED, notificationReplay.code)
        assertEquals(delivery.deliveryDigest, notificationReplay.record!!.delivery!!.deliveryDigest)
        assertEquals(notificationReceipt.receiptDigest, notificationReplay.record!!.notificationReceipt!!.receiptDigest)
        assertEquals(
            receiptContext.contextDigest,
            notificationReplay.record!!.notificationReceipt!!.restoreContext().contextDigest,
        )
        assertEquals(delivery.deliveryDigest, store.loadNotificationDelivery("tenant-a", "notify-idem-1")!!.deliveryDigest)
        assertNull(store.loadNotificationDelivery("tenant-b", "notify-idem-1"))

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT provider_receipt_digest, receipt_expires_time
                    FROM fw_wf_mention_notification_result WHERE tenant_id = ? AND idempotency_key = ?""",
            ).use { statement ->
                statement.setString(1, "tenant-a")
                statement.setString(2, "notify-idem-1")
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(notificationReceipt.receiptDigest, result.getString(1))
                    assertEquals(3_080L, result.getLong(2))
                }
            }
        }
    }

    @Test
    fun `provider checkpoint prevents blind resend until exact not-sent reconciliation`() {
        val dataSource = dataSource()
        val store = JdbcWorkflowHumanInputStore(dataSource, WorkflowJdbcDialect.MYSQL)
        val firstReservation = assertNotNull(store.reserve(reservation(
            "tenant-a",
            "notify-idem-unknown",
            WorkflowHumanInputOperation.MENTION_NOTIFY,
            DIGEST_C,
            4_000L,
            4_100L,
        )).reservation)
        val started = store.checkpointProviderCall(
            WorkflowMentionNotificationProviderCheckpoint.of(firstReservation, DIGEST_D, 4_010L),
        )
        assertSame(WorkflowMentionNotificationCheckpointCode.APPLIED, started.code)
        assertSame(
            WorkflowMentionNotificationCheckpointCode.REPLAYED,
            store.checkpointProviderCall(
                WorkflowMentionNotificationProviderCheckpoint.of(firstReservation, DIGEST_D, 4_010L),
            ).code,
        )
        val unknown = store.markProviderOutcomeUnknown(WorkflowMentionNotificationOutcomeUnknown.of(
            assertNotNull(started.checkpoint),
            DIGEST_E,
            4_020L,
        ))
        assertSame(WorkflowMentionNotificationCheckpointCode.APPLIED, unknown.code)
        assertSame(WorkflowMentionNotificationCheckpointStatus.OUTCOME_UNKNOWN, unknown.checkpoint!!.status)

        val blocked = store.reserve(reservation(
            "tenant-a",
            "notify-idem-unknown",
            WorkflowHumanInputOperation.MENTION_NOTIFY,
            DIGEST_C,
            4_100L,
            4_200L,
        ))
        assertSame(WorkflowHumanInputReservationCode.OUTCOME_UNKNOWN, blocked.code)
        assertNull(blocked.reservation)

        val notSent = store.reconcileProviderCall(WorkflowMentionNotificationReconciliation.of(
            assertNotNull(unknown.checkpoint),
            WorkflowMentionNotificationReconciliationResolution.NOT_SENT,
            null,
            DIGEST_F,
            4_110L,
        ))
        assertSame(WorkflowMentionNotificationCheckpointCode.APPLIED, notSent.code)
        assertSame(WorkflowMentionNotificationCheckpointStatus.NOT_SENT, notSent.checkpoint!!.status)
        val retried = store.reserve(reservation(
            "tenant-a",
            "notify-idem-unknown",
            WorkflowHumanInputOperation.MENTION_NOTIFY,
            DIGEST_C,
            4_110L,
            4_210L,
        ))
        assertSame(WorkflowHumanInputReservationCode.RESERVED, retried.code)
        assertEquals(firstReservation.fencingToken + 1L, retried.reservation!!.fencingToken)
        val secondStarted = store.checkpointProviderCall(WorkflowMentionNotificationProviderCheckpoint.of(
            assertNotNull(retried.reservation),
            DIGEST_A,
            4_120L,
        ))
        assertSame(WorkflowMentionNotificationCheckpointCode.APPLIED, secondStarted.code)
        assertEquals(notSent.checkpoint!!.recordVersion + 1L, secondStarted.checkpoint!!.recordVersion)
        assertEquals(DIGEST_A, secondStarted.checkpoint!!.providerRequestDigest)
        assertNull(store.loadProviderCheckpoint("tenant-b", "notify-idem-unknown", 4_130L))
    }

    private fun dataSource(): JdbcDataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:workflow-human-${System.nanoTime()};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
        user = "sa"
        password = ""
        connection.use { connection ->
            listOf(
                "/ai/icen/fw/workflow/db/migration/mysql/V034__persist_workflow_human_input.sql",
                "/ai/icen/fw/workflow/db/migration/mysql/V036__fence_workflow_mention_notification_provider_calls.sql",
            ).forEach { path ->
                val resource = requireNotNull(
                    JdbcWorkflowHumanInputStoreH2Test::class.java.getResourceAsStream(path),
                )
                InputStreamReader(resource, Charsets.UTF_8).use { reader -> RunScript.execute(connection, reader) }
            }
        }
    }

    private fun formRecord(): WorkflowHumanInputIdempotencyRecord {
        val apiSchema = WorkflowJsonSchemaRef.of(
            "schema-registry",
            "leave-request",
            "1",
            WorkflowJsonSchemaDialect.JSON_SCHEMA_2020_12,
            DIGEST_A,
        )
        val form = WorkflowFormVersionRef.of("leave-form", "4", apiSchema, null, null, DIGEST_B)
        val spiSchema = WorkflowSchemaRef.of("schema-registry", "leave-request", "1", DIGEST_A)
        val raw = WorkflowStructuredPayload.of(spiSchema, "{\"days\":1}".toByteArray(Charsets.UTF_8))
        val validationReceipt = WorkflowPayloadValidationReceipt.of(
            "schema-validator",
            "revision-2",
            spiSchema,
            raw.canonicalPayloadDigest,
            1,
            DIGEST_C,
        )
        val normalized = WorkflowStructuredPayload.validated(raw, validationReceipt)
        val access = WorkflowFormFieldAccessReport.of(
            listOf(WorkflowFormFieldAccessDecision.of(
                WorkflowFormFieldPath.of("/days"),
                WorkflowFormFieldAccessMode.ALLOW,
                WorkflowFormFieldAccessMode.ALLOW,
            )),
            DIGEST_C,
        )
        val report = WorkflowSecureFormValidationReport.valid(normalized, access)
        val context = WorkflowProviderCallContext.of(
            "form-request-1",
            "tenant-a",
            "schema-validator",
            "revision-2",
            "secure-form-validation",
            1_010L,
            1_090L,
            4_096,
            4_096,
            10,
        )
        val providerReceipt = WorkflowProviderReceipt.success(
            context,
            DIGEST_D,
            report.reportDigest,
            1_020L,
            1_080L,
        )
        val submission = WorkflowFormSubmissionRef.of(
            "submission-1",
            7L,
            form,
            WorkflowPrincipalRef.of("user", "user-author"),
            normalized.canonicalPayloadDigest,
            normalized.size,
            validationReceipt.receiptDigest,
            access.authorityReceiptDigest,
            1_020L,
        )
        val result = WorkflowRuntimeValidatedForm.of(
            form,
            WorkflowFormValidationOperation.SUBMIT,
            normalized,
            access,
            providerReceipt,
            submission,
        )
        return WorkflowHumanInputIdempotencyRecord.form(
            "tenant-a",
            "form-idem-1",
            DIGEST_E,
            result,
            1_020L,
        )
    }

    private fun commentRecord(completedAt: Long): WorkflowHumanInputIdempotencyRecord {
        val document = WorkflowCommentDocument.of(listOf(
            WorkflowCommentToken.text("literal <b>text</b>"),
            WorkflowCommentToken.mention(
                WorkflowPrincipalRef.of("user", "user-mentioned"),
                "Mentioned User",
            ),
        ))
        val comment = WorkflowCommentSnapshot.of(
            "comment-1",
            3L,
            WorkflowInstanceRef.of("instance-1", 9L),
            WorkflowWorkItemRef.of("work-item-1", 4L),
            WorkflowPrincipalRef.of("user", "user-author"),
            document,
            DIGEST_A,
            DIGEST_B,
            completedAt,
        )
        return WorkflowHumanInputIdempotencyRecord.comment(
            "tenant-a",
            "comment-idem-1",
            DIGEST_F,
            comment,
            completedAt,
        )
    }

    private fun reservation(
        tenantId: String,
        idempotencyKey: String,
        operation: WorkflowHumanInputOperation,
        requestDigest: String,
        requestedAt: Long,
        leaseUntil: Long,
    ): WorkflowHumanInputReservationRequest = WorkflowHumanInputReservationRequest.of(
        tenantId,
        idempotencyKey,
        operation,
        requestDigest,
        requestedAt,
        leaseUntil,
    )

    private companion object {
        val DIGEST_A = "a".repeat(64)
        val DIGEST_B = "b".repeat(64)
        val DIGEST_C = "c".repeat(64)
        val DIGEST_D = "d".repeat(64)
        val DIGEST_E = "e".repeat(64)
        val DIGEST_F = "f".repeat(64)
    }
}
