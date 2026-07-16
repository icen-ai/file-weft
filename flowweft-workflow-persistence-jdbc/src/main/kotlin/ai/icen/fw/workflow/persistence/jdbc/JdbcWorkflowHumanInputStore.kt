package ai.icen.fw.workflow.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowCommentDocument
import ai.icen.fw.workflow.api.WorkflowCommentSnapshot
import ai.icen.fw.workflow.api.WorkflowCommentToken
import ai.icen.fw.workflow.api.WorkflowCommentTokenKind
import ai.icen.fw.workflow.api.WorkflowFormSubmissionRef
import ai.icen.fw.workflow.api.WorkflowFormVersionRef
import ai.icen.fw.workflow.api.WorkflowInstanceRef
import ai.icen.fw.workflow.api.WorkflowJsonSchemaDialect
import ai.icen.fw.workflow.api.WorkflowJsonSchemaRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowWorkItemRef
import ai.icen.fw.workflow.runtime.WorkflowHumanInputIdempotencyPort
import ai.icen.fw.workflow.runtime.WorkflowHumanInputIdempotencyRecord
import ai.icen.fw.workflow.runtime.WorkflowHumanInputIdempotencyWriteCode
import ai.icen.fw.workflow.runtime.WorkflowHumanInputIdempotencyWriteResult
import ai.icen.fw.workflow.runtime.WorkflowHumanInputOperation
import ai.icen.fw.workflow.runtime.WorkflowHumanInputReservation
import ai.icen.fw.workflow.runtime.WorkflowHumanInputReservationCode
import ai.icen.fw.workflow.runtime.WorkflowHumanInputReservationRequest
import ai.icen.fw.workflow.runtime.WorkflowHumanInputReservationResult
import ai.icen.fw.workflow.spi.WorkflowNotificationDelivery
import ai.icen.fw.workflow.spi.WorkflowNotificationDeliveryStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

/**
 * Durable human-input reservation and evidence store. Every read key includes tenant_id; provider
 * calls are impossible because this adapter depends only on JDBC and immutable contracts.
 */
class JdbcWorkflowHumanInputStore @JvmOverloads constructor(
    dataSource: DataSource,
    dialect: WorkflowJdbcDialect? = null,
) : WorkflowHumanInputIdempotencyPort {
    private val transactions = WorkflowJdbcTransactions(dataSource, dialect)

    override fun reserve(request: WorkflowHumanInputReservationRequest): WorkflowHumanInputReservationResult =
        transactions.transaction { connection, dialect ->
            var existing = selectIdempotency(connection, request.tenantId, request.idempotencyKey, forUpdate = true)
            if (existing == null) {
                val reservation = newReservation(request, 1L)
                insertReservationIfAbsent(connection, dialect, request, reservation)
                val inserted = checkNotNull(
                    selectIdempotency(connection, request.tenantId, request.idempotencyKey, forUpdate = true),
                ) { "Workflow human-input reservation disappeared after insert." }
                existing = inserted
                if (inserted.operation == request.operation.code &&
                    inserted.requestDigest == request.requestDigest &&
                    inserted.status == STATUS_RESERVED &&
                    inserted.leaseId == reservation.leaseId &&
                    inserted.fencingToken == reservation.fencingToken &&
                    inserted.leaseExpiresAt == reservation.expiresAtEpochMilli
                ) {
                    return@transaction WorkflowHumanInputReservationResult.reserved(reservation)
                }
            }
            val current = checkNotNull(existing)
            if (current.operation != request.operation.code || current.requestDigest != request.requestDigest) {
                return@transaction WorkflowHumanInputReservationResult.failed(
                    WorkflowHumanInputReservationCode.CONFLICT,
                )
            }
            if (current.status == STATUS_COMPLETED) {
                val record = decode(current)
                return@transaction WorkflowHumanInputReservationResult.replayed(record)
            }
            if (current.status != STATUS_RESERVED) {
                return@transaction WorkflowHumanInputReservationResult.failed(
                    WorkflowHumanInputReservationCode.OUTCOME_UNKNOWN,
                )
            }
            if (current.leaseExpiresAt > request.requestedAtEpochMilli) {
                return@transaction WorkflowHumanInputReservationResult.failed(
                    WorkflowHumanInputReservationCode.IN_PROGRESS,
                )
            }
            val nextFence = current.fencingToken + 1L
            val nextVersion = current.recordVersion + 1L
            if (nextFence <= 0L || nextVersion <= 0L) {
                return@transaction WorkflowHumanInputReservationResult.failed(
                    WorkflowHumanInputReservationCode.OUTCOME_UNKNOWN,
                )
            }
            val reservation = newReservation(request, nextFence)
            connection.prepareStatement(REPLACE_RESERVATION_SQL).use { statement ->
                statement.setString(1, reservation.leaseId)
                statement.setLong(2, reservation.fencingToken)
                statement.setLong(3, reservation.expiresAtEpochMilli)
                statement.setLong(4, nextVersion)
                statement.setLong(5, request.requestedAtEpochMilli)
                statement.setString(6, request.tenantId)
                statement.setString(7, current.id)
                statement.setLong(8, current.recordVersion)
                statement.setString(9, STATUS_RESERVED)
                check(statement.executeUpdate() == 1) { "Workflow human-input reservation CAS changed while locked." }
            }
            WorkflowHumanInputReservationResult.reserved(reservation)
        }

    override fun complete(
        reservation: WorkflowHumanInputReservation,
        record: WorkflowHumanInputIdempotencyRecord,
    ): WorkflowHumanInputIdempotencyWriteResult {
        val encoded = WorkflowHumanInputJdbcCodec.encode(record)
        return transactions.transaction { connection, _ ->
            val existing = selectIdempotency(
                connection,
                reservation.tenantId,
                reservation.idempotencyKey,
                forUpdate = true,
            ) ?: return@transaction WorkflowHumanInputIdempotencyWriteResult.failed(
                WorkflowHumanInputIdempotencyWriteCode.OUTCOME_UNKNOWN,
            )
            if (existing.operation != reservation.operation.code ||
                existing.requestDigest != reservation.requestDigest ||
                record.tenantId != reservation.tenantId ||
                record.idempotencyKey != reservation.idempotencyKey ||
                !record.matches(reservation.operation, reservation.requestDigest)
            ) {
                return@transaction WorkflowHumanInputIdempotencyWriteResult.failed(
                    WorkflowHumanInputIdempotencyWriteCode.CONFLICT,
                )
            }
            if (existing.status == STATUS_COMPLETED) {
                if (existing.resultDigest != record.resultDigest) {
                    return@transaction WorkflowHumanInputIdempotencyWriteResult.failed(
                        WorkflowHumanInputIdempotencyWriteCode.CONFLICT,
                    )
                }
                return@transaction WorkflowHumanInputIdempotencyWriteResult.replayed(decode(existing))
            }
            val providerReceipt = record.validatedForm?.providerReceipt ?: record.notificationReceipt
            if (existing.status != STATUS_RESERVED || existing.leaseId != reservation.leaseId ||
                existing.fencingToken != reservation.fencingToken ||
                existing.leaseExpiresAt != reservation.expiresAtEpochMilli ||
                record.completedAtEpochMilli < existing.updatedAt ||
                record.completedAtEpochMilli > reservation.expiresAtEpochMilli ||
                providerReceipt != null &&
                (providerReceipt.completedAtEpochMilli < existing.updatedAt ||
                    record.completedAtEpochMilli > providerReceipt.expiresAtEpochMilli)
            ) {
                return@transaction WorkflowHumanInputIdempotencyWriteResult.failed(
                    WorkflowHumanInputIdempotencyWriteCode.CONFLICT,
                )
            }
            val nextVersion = existing.recordVersion + 1L
            if (nextVersion <= 0L) {
                return@transaction WorkflowHumanInputIdempotencyWriteResult.failed(
                    WorkflowHumanInputIdempotencyWriteCode.OUTCOME_UNKNOWN,
                )
            }
            connection.prepareStatement(COMPLETE_RESERVATION_SQL).use { statement ->
                statement.setString(1, STATUS_COMPLETED)
                statement.setString(2, encoded.kind)
                statement.setString(3, record.resultDigest)
                statement.setBytes(4, encoded.payload)
                setNullableString(statement, 5, encoded.providerReceiptDigest)
                setNullableLong(statement, 6, encoded.receiptExpiresAt)
                statement.setLong(7, nextVersion)
                statement.setLong(8, record.completedAtEpochMilli)
                statement.setString(9, reservation.tenantId)
                statement.setString(10, existing.id)
                statement.setLong(11, existing.recordVersion)
                statement.setString(12, STATUS_RESERVED)
                statement.setString(13, reservation.leaseId)
                statement.setLong(14, reservation.fencingToken)
                check(statement.executeUpdate() == 1) { "Workflow human-input completion CAS changed while locked." }
            }
            persistProjection(connection, record, encoded)
            WorkflowHumanInputIdempotencyWriteResult.stored(record)
        }
    }

    fun loadFormSubmission(
        tenantId: String,
        submissionId: String,
        submissionVersion: Long,
    ): WorkflowFormSubmissionRef? = transactions.read { connection, _ ->
        connection.prepareStatement(SELECT_FORM_SUBMISSION_SQL).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, submissionId)
            statement.setLong(3, submissionVersion)
            statement.executeQuery().use { result -> if (result.next()) mapFormSubmission(result) else null }
        }
    }

    fun loadComment(
        tenantId: String,
        commentId: String,
        commentVersion: Long,
    ): WorkflowCommentSnapshot? = transactions.read { connection, _ ->
        val header = connection.prepareStatement(SELECT_COMMENT_SQL).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, commentId)
            statement.setLong(3, commentVersion)
            statement.executeQuery().use { result -> if (result.next()) mapCommentHeader(result) else null }
        } ?: return@read null
        val tokens = connection.prepareStatement(SELECT_COMMENT_TOKENS_SQL).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, commentId)
            statement.setLong(3, commentVersion)
            statement.executeQuery().use { result ->
                ArrayList<WorkflowCommentToken>().also { values ->
                    while (result.next()) values.add(mapCommentToken(result))
                }
            }
        }
        header.toSnapshot(tokens)
    }

    fun loadNotificationDelivery(tenantId: String, idempotencyKey: String): WorkflowNotificationDelivery? =
        transactions.read { connection, _ ->
            connection.prepareStatement(SELECT_NOTIFICATION_SQL).use { statement ->
                statement.setString(1, tenantId)
                statement.setString(2, idempotencyKey)
                statement.executeQuery().use { result -> if (result.next()) mapDelivery(result) else null }
            }
        }

    private fun newReservation(
        request: WorkflowHumanInputReservationRequest,
        fencingToken: Long,
    ): WorkflowHumanInputReservation {
        val leaseId = "wf-human-${UUID.randomUUID()}"
        return WorkflowHumanInputReservation.of(
            request.tenantId,
            request.idempotencyKey,
            request.operation,
            request.requestDigest,
            leaseId,
            fencingToken,
            request.leaseUntilEpochMilli,
        )
    }

    private fun insertReservationIfAbsent(
        connection: Connection,
        dialect: WorkflowJdbcDialect,
        request: WorkflowHumanInputReservationRequest,
        reservation: WorkflowHumanInputReservation,
    ) {
        connection.prepareStatement(insertReservationSql(dialect)).use { statement ->
            statement.setString(1, stableId("workflow-human-input", request.tenantId, request.idempotencyKey))
            statement.setString(2, request.tenantId)
            statement.setString(3, request.idempotencyKey)
            statement.setString(4, request.operation.code)
            statement.setString(5, request.requestDigest)
            statement.setString(6, STATUS_RESERVED)
            statement.setString(7, reservation.leaseId)
            statement.setLong(8, reservation.fencingToken)
            statement.setLong(9, reservation.expiresAtEpochMilli)
            statement.setLong(10, 1L)
            statement.setLong(11, request.requestedAtEpochMilli)
            statement.setLong(12, request.requestedAtEpochMilli)
            statement.executeUpdate()
        }
    }

    private fun selectIdempotency(
        connection: Connection,
        tenantId: String,
        idempotencyKey: String,
        forUpdate: Boolean,
    ): IdempotencyRow? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(SELECT_IDEMPOTENCY_SQL + suffix).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, idempotencyKey)
            statement.executeQuery().use { result -> if (result.next()) mapIdempotency(result) else null }
        }
    }

    private fun decode(row: IdempotencyRow): WorkflowHumanInputIdempotencyRecord {
        require(row.status == STATUS_COMPLETED && row.resultDigest != null && row.resultPayload != null &&
            row.fencingToken > 0L && row.recordVersion > 0L && row.createdAt >= 0L &&
            row.updatedAt >= row.createdAt && row.leaseExpiresAt >= row.createdAt
        ) {
            "Workflow human-input replay row is incomplete."
        }
        val operation = operation(row.operation)
        require(row.resultKind == resultKind(operation)) {
            "Workflow human-input replay row kind is inconsistent."
        }
        val record = WorkflowHumanInputJdbcCodec.decode(
            row.tenantId,
            row.idempotencyKey,
            operation,
            row.requestDigest,
            row.resultDigest,
            row.resultPayload,
            row.updatedAt,
        )
        val receipt = record.validatedForm?.providerReceipt ?: record.notificationReceipt
        require(row.providerReceiptDigest == receipt?.receiptDigest &&
            row.receiptExpiresAt == receipt?.expiresAtEpochMilli
        ) { "Workflow human-input replay receipt projection is inconsistent." }
        return record
    }

    private fun persistProjection(
        connection: Connection,
        record: WorkflowHumanInputIdempotencyRecord,
        encoded: WorkflowHumanInputJdbcEncodedResult,
    ) {
        record.validatedForm?.submission?.let { submission ->
            connection.prepareStatement(INSERT_FORM_SUBMISSION_SQL).use { statement ->
                val form = submission.form
                statement.setString(1, submission.submissionId)
                statement.setString(2, record.tenantId)
                statement.setLong(3, submission.version)
                statement.setString(4, form.formKey)
                statement.setString(5, form.version)
                statement.setString(6, form.bindingDigest)
                statement.setString(7, form.formDigest)
                statement.setString(8, form.dataSchema.registryId)
                statement.setString(9, form.dataSchema.schemaId)
                statement.setString(10, form.dataSchema.version)
                statement.setString(11, form.dataSchema.dialect.code)
                statement.setString(12, form.dataSchema.digest)
                setNullableString(statement, 13, form.uiSchemaVersion)
                setNullableString(statement, 14, form.uiSchemaDigest)
                statement.setString(15, submission.submittedBy.type)
                statement.setString(16, submission.submittedBy.id)
                statement.setString(17, submission.canonicalPayloadDigest)
                statement.setInt(18, submission.payloadSizeBytes)
                statement.setString(19, submission.validationReceiptDigest)
                statement.setString(20, submission.fieldAccessReceiptDigest)
                statement.setString(21, submission.submissionDigest)
                statement.setString(22, requireNotNull(encoded.providerReceiptDigest))
                statement.setLong(23, requireNotNull(encoded.receiptExpiresAt))
                statement.setLong(24, submission.submittedAtEpochMilli)
                statement.setLong(25, record.completedAtEpochMilli)
                statement.setLong(26, record.completedAtEpochMilli)
                check(statement.executeUpdate() == 1) { "Workflow form submission projection insert failed." }
            }
        }
        record.comment?.let { comment -> insertComment(connection, record.tenantId, comment, record.completedAtEpochMilli) }
        record.delivery?.let { delivery ->
            connection.prepareStatement(INSERT_NOTIFICATION_SQL).use { statement ->
                statement.setString(1, stableId("workflow-mention-notification", record.tenantId, record.idempotencyKey))
                statement.setString(2, record.tenantId)
                statement.setString(3, record.idempotencyKey)
                statement.setString(4, delivery.status.code)
                setNullableString(statement, 5, delivery.providerMessageRef)
                statement.setString(6, delivery.evidenceDigest)
                statement.setString(7, delivery.deliveryDigest)
                statement.setString(8, requireNotNull(encoded.providerReceiptDigest))
                statement.setLong(9, requireNotNull(encoded.receiptExpiresAt))
                statement.setLong(10, record.completedAtEpochMilli)
                statement.setLong(11, record.completedAtEpochMilli)
                check(statement.executeUpdate() == 1) { "Workflow mention notification projection insert failed." }
            }
        }
    }

    private fun insertComment(
        connection: Connection,
        tenantId: String,
        comment: WorkflowCommentSnapshot,
        persistedAt: Long,
    ) {
        connection.prepareStatement(INSERT_COMMENT_SQL).use { statement ->
            statement.setString(1, comment.commentId)
            statement.setString(2, tenantId)
            statement.setLong(3, comment.version)
            statement.setString(4, comment.instance.id)
            statement.setLong(5, comment.instance.expectedVersion)
            setNullableString(statement, 6, comment.workItem?.id)
            setNullableLong(statement, 7, comment.workItem?.expectedVersion)
            statement.setString(8, comment.author.type)
            statement.setString(9, comment.author.id)
            statement.setInt(10, comment.document.schemaVersion)
            statement.setString(11, comment.document.documentDigest)
            statement.setString(12, comment.snapshotDigest)
            statement.setString(13, comment.authorAuthorizationReceiptDigest)
            setNullableString(statement, 14, comment.mentionAttestationReceiptDigest)
            statement.setLong(15, comment.createdAtEpochMilli)
            statement.setLong(16, persistedAt)
            statement.setLong(17, persistedAt)
            check(statement.executeUpdate() == 1) { "Workflow structured comment insert failed." }
        }
        comment.document.tokens.forEachIndexed { ordinal, token ->
            connection.prepareStatement(INSERT_COMMENT_TOKEN_SQL).use { statement ->
                statement.setString(
                    1,
                    stableId("workflow-comment-token", tenantId, comment.commentId, comment.version.toString(), ordinal.toString()),
                )
                statement.setString(2, tenantId)
                statement.setString(3, comment.commentId)
                statement.setLong(4, comment.version)
                statement.setInt(5, ordinal)
                statement.setString(6, token.kind.code)
                setNullableString(statement, 7, token.text)
                setNullableString(statement, 8, token.principal?.type)
                setNullableString(statement, 9, token.principal?.id)
                setNullableString(statement, 10, token.displayNameSnapshot)
                statement.setString(11, token.tokenDigest)
                statement.setLong(12, persistedAt)
                statement.setLong(13, persistedAt)
                check(statement.executeUpdate() == 1) { "Workflow structured comment token insert failed." }
            }
        }
    }

    private fun mapIdempotency(result: ResultSet): IdempotencyRow = IdempotencyRow(
        result.getString("id"),
        result.getString("tenant_id"),
        result.getString("idempotency_key"),
        result.getString("operation_code"),
        result.getString("request_digest"),
        result.getString("reservation_status"),
        result.getString("lease_id"),
        result.getLong("fencing_token"),
        result.getLong("lease_expires_time"),
        result.getString("result_kind"),
        result.getString("result_digest"),
        result.getBytes("result_payload"),
        result.getString("provider_receipt_digest"),
        nullableLong(result, "receipt_expires_time"),
        result.getLong("record_version"),
        result.getLong("created_time"),
        result.getLong("updated_time"),
    )

    private fun mapFormSubmission(result: ResultSet): WorkflowFormSubmissionRef {
        val form = WorkflowFormVersionRef.of(
            result.getString("form_key"),
            result.getString("form_version"),
            WorkflowJsonSchemaRef.of(
                result.getString("schema_registry_id"),
                result.getString("schema_id"),
                result.getString("schema_version"),
                WorkflowJsonSchemaDialect.of(result.getString("schema_dialect")),
                result.getString("schema_digest"),
            ),
            result.getString("ui_schema_version"),
            result.getString("ui_schema_digest"),
            result.getString("form_digest"),
        )
        require(form.bindingDigest == result.getString("form_binding_digest")) {
            "Persisted workflow form binding digest is inconsistent."
        }
        val value = WorkflowFormSubmissionRef.of(
            result.getString("id"),
            result.getLong("submission_version"),
            form,
            WorkflowPrincipalRef.of(result.getString("submitted_by_type"), result.getString("submitted_by_id")),
            result.getString("canonical_payload_digest"),
            result.getInt("payload_size_bytes"),
            result.getString("validation_receipt_digest"),
            result.getString("field_access_receipt_digest"),
            result.getLong("occurred_time"),
        )
        require(value.submissionDigest == result.getString("submission_digest")) {
            "Persisted workflow form submission digest is inconsistent."
        }
        return value
    }

    private fun mapCommentHeader(result: ResultSet): CommentHeader = CommentHeader(
        result.getString("id"),
        result.getLong("comment_version"),
        result.getString("instance_id"),
        result.getLong("instance_version"),
        result.getString("work_item_id"),
        nullableLong(result, "work_item_version"),
        result.getString("author_type"),
        result.getString("author_id"),
        result.getInt("token_schema_version"),
        result.getString("document_digest"),
        result.getString("snapshot_digest"),
        result.getString("authorization_receipt_digest"),
        result.getString("mention_attestation_digest"),
        result.getLong("occurred_time"),
    )

    private fun mapCommentToken(result: ResultSet): WorkflowCommentToken {
        val token = when (result.getString("token_kind")) {
            WorkflowCommentTokenKind.TEXT.code -> WorkflowCommentToken.text(result.getString("text_content"))
            WorkflowCommentTokenKind.MENTION.code -> WorkflowCommentToken.mention(
                WorkflowPrincipalRef.of(result.getString("principal_type"), result.getString("principal_id")),
                result.getString("display_name_snapshot"),
            )
            else -> throw IllegalArgumentException("Persisted workflow comment token kind is unsupported.")
        }
        require(token.tokenDigest == result.getString("token_digest")) {
            "Persisted workflow comment token digest is inconsistent."
        }
        return token
    }

    private fun mapDelivery(result: ResultSet): WorkflowNotificationDelivery {
        val status = WorkflowNotificationDeliveryStatus.of(result.getString("delivery_status"))
        val delivery = when (status) {
            WorkflowNotificationDeliveryStatus.ACCEPTED -> WorkflowNotificationDelivery.accepted(
                result.getString("provider_message_ref"),
                result.getString("evidence_digest"),
            )
            WorkflowNotificationDeliveryStatus.SUPPRESSED ->
                WorkflowNotificationDelivery.suppressed(result.getString("evidence_digest"))
            else -> throw IllegalArgumentException("Persisted workflow notification status is unsupported.")
        }
        require(delivery.deliveryDigest == result.getString("delivery_digest")) {
            "Persisted workflow notification delivery digest is inconsistent."
        }
        return delivery
    }

    private class CommentHeader(
        val id: String,
        val version: Long,
        val instanceId: String,
        val instanceVersion: Long,
        val workItemId: String?,
        val workItemVersion: Long?,
        val authorType: String,
        val authorId: String,
        val schemaVersion: Int,
        val documentDigest: String,
        val snapshotDigest: String,
        val authorizationDigest: String,
        val mentionDigest: String?,
        val occurredAt: Long,
    ) {
        fun toSnapshot(tokens: Collection<WorkflowCommentToken>): WorkflowCommentSnapshot {
            val document = WorkflowCommentDocument.restore(schemaVersion, tokens)
            require(document.documentDigest == documentDigest) {
                "Persisted workflow comment document digest is inconsistent."
            }
            val value = WorkflowCommentSnapshot.of(
                id,
                version,
                WorkflowInstanceRef.of(instanceId, instanceVersion),
                workItemId?.let { WorkflowWorkItemRef.of(it, requireNotNull(workItemVersion)) },
                WorkflowPrincipalRef.of(authorType, authorId),
                document,
                authorizationDigest,
                mentionDigest,
                occurredAt,
            )
            require(value.snapshotDigest == snapshotDigest) {
                "Persisted workflow comment snapshot digest is inconsistent."
            }
            return value
        }
    }

    private class IdempotencyRow(
        val id: String,
        val tenantId: String,
        val idempotencyKey: String,
        val operation: String,
        val requestDigest: String,
        val status: String,
        val leaseId: String,
        val fencingToken: Long,
        val leaseExpiresAt: Long,
        val resultKind: String?,
        val resultDigest: String?,
        val resultPayload: ByteArray?,
        val providerReceiptDigest: String?,
        val receiptExpiresAt: Long?,
        val recordVersion: Long,
        val createdAt: Long,
        val updatedAt: Long,
    )

    companion object {
        private const val STATUS_RESERVED = "reserved"
        private const val STATUS_COMPLETED = "completed"

        private const val SELECT_IDEMPOTENCY_SQL = """
            SELECT id, tenant_id, idempotency_key, operation_code, request_digest,
                reservation_status, lease_id, fencing_token, lease_expires_time,
                result_kind, result_digest, result_payload, provider_receipt_digest,
                receipt_expires_time, record_version, created_time, updated_time
            FROM fw_wf_human_input_idem
            WHERE tenant_id = ? AND idempotency_key = ?
        """
        private const val INSERT_RESERVATION_SQL = """
            INSERT INTO fw_wf_human_input_idem(
                id, tenant_id, idempotency_key, operation_code, request_digest,
                reservation_status, lease_id, fencing_token, lease_expires_time,
                record_version, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        private fun insertReservationSql(dialect: WorkflowJdbcDialect): String = when (dialect) {
            WorkflowJdbcDialect.MYSQL -> INSERT_RESERVATION_SQL + " ON DUPLICATE KEY UPDATE id = id"
            WorkflowJdbcDialect.POSTGRESQL,
            WorkflowJdbcDialect.KINGBASE -> INSERT_RESERVATION_SQL + " ON CONFLICT DO NOTHING"
        }
        private const val REPLACE_RESERVATION_SQL = """
            UPDATE fw_wf_human_input_idem
            SET lease_id = ?, fencing_token = ?, lease_expires_time = ?, record_version = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ? AND reservation_status = ?
        """
        private const val COMPLETE_RESERVATION_SQL = """
            UPDATE fw_wf_human_input_idem
            SET reservation_status = ?, result_kind = ?, result_digest = ?, result_payload = ?,
                provider_receipt_digest = ?, receipt_expires_time = ?, record_version = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ? AND reservation_status = ?
                AND lease_id = ? AND fencing_token = ?
        """
        private const val INSERT_FORM_SUBMISSION_SQL = """
            INSERT INTO fw_wf_form_submission_ref(
                id, tenant_id, submission_version, form_key, form_version,
                form_binding_digest, form_digest, schema_registry_id, schema_id, schema_version,
                schema_dialect, schema_digest, ui_schema_version, ui_schema_digest,
                submitted_by_type, submitted_by_id, canonical_payload_digest, payload_size_bytes,
                validation_receipt_digest, field_access_receipt_digest, submission_digest, provider_receipt_digest,
                receipt_expires_time, occurred_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        private const val INSERT_COMMENT_SQL = """
            INSERT INTO fw_wf_structured_comment(
                id, tenant_id, comment_version, instance_id, instance_version,
                work_item_id, work_item_version, author_type, author_id, token_schema_version,
                document_digest, snapshot_digest, authorization_receipt_digest,
                mention_attestation_digest, occurred_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        private const val INSERT_COMMENT_TOKEN_SQL = """
            INSERT INTO fw_wf_structured_comment_token(
                id, tenant_id, comment_id, comment_version, token_ordinal, token_kind,
                text_content, principal_type, principal_id, display_name_snapshot,
                token_digest, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        private const val INSERT_NOTIFICATION_SQL = """
            INSERT INTO fw_wf_mention_notification_result(
                id, tenant_id, idempotency_key, delivery_status, provider_message_ref,
                evidence_digest, delivery_digest, provider_receipt_digest, receipt_expires_time,
                created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        private const val SELECT_FORM_SUBMISSION_SQL = """
            SELECT id, submission_version, form_key, form_version, form_binding_digest, form_digest,
                schema_registry_id, schema_id, schema_version, schema_dialect, schema_digest,
                ui_schema_version, ui_schema_digest, submitted_by_type, submitted_by_id,
                canonical_payload_digest, payload_size_bytes, validation_receipt_digest,
                field_access_receipt_digest, submission_digest, occurred_time
            FROM fw_wf_form_submission_ref
            WHERE tenant_id = ? AND id = ? AND submission_version = ?
        """
        private const val SELECT_COMMENT_SQL = """
            SELECT id, comment_version, instance_id, instance_version, work_item_id, work_item_version,
                author_type, author_id, token_schema_version, document_digest, snapshot_digest,
                authorization_receipt_digest, mention_attestation_digest, occurred_time
            FROM fw_wf_structured_comment
            WHERE tenant_id = ? AND id = ? AND comment_version = ?
        """
        private const val SELECT_COMMENT_TOKENS_SQL = """
            SELECT token_kind, text_content, principal_type, principal_id,
                display_name_snapshot, token_digest
            FROM fw_wf_structured_comment_token
            WHERE tenant_id = ? AND comment_id = ? AND comment_version = ?
            ORDER BY token_ordinal
        """
        private const val SELECT_NOTIFICATION_SQL = """
            SELECT delivery_status, provider_message_ref, evidence_digest, delivery_digest
            FROM fw_wf_mention_notification_result
            WHERE tenant_id = ? AND idempotency_key = ?
        """

        private fun operation(code: String): WorkflowHumanInputOperation = when (code) {
            WorkflowHumanInputOperation.FORM_VALIDATE.code -> WorkflowHumanInputOperation.FORM_VALIDATE
            WorkflowHumanInputOperation.COMMENT_CREATE.code -> WorkflowHumanInputOperation.COMMENT_CREATE
            WorkflowHumanInputOperation.MENTION_NOTIFY.code -> WorkflowHumanInputOperation.MENTION_NOTIFY
            else -> throw IllegalArgumentException("Persisted workflow human-input operation is unsupported.")
        }

        private fun resultKind(operation: WorkflowHumanInputOperation): String = when (operation) {
            WorkflowHumanInputOperation.FORM_VALIDATE -> "form"
            WorkflowHumanInputOperation.COMMENT_CREATE -> "comment"
            WorkflowHumanInputOperation.MENTION_NOTIFY -> "notification"
            else -> throw IllegalArgumentException("Persisted workflow human-input result kind is unsupported.")
        }

        private fun stableId(vararg values: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            values.forEach { value ->
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                digest.update(byteArrayOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte(),
                ))
                digest.update(bytes)
            }
            val encoded = digest.digest()
            val alphabet = "0123456789abcdef"
            return buildString(encoded.size * 2) {
                encoded.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(alphabet[value ushr 4])
                    append(alphabet[value and 0x0f])
                }
            }
        }

        private fun setNullableString(statement: PreparedStatement, index: Int, value: String?) {
            if (value == null) statement.setNull(index, Types.VARCHAR) else statement.setString(index, value)
        }

        private fun setNullableLong(statement: PreparedStatement, index: Int, value: Long?) {
            if (value == null) statement.setNull(index, Types.BIGINT) else statement.setLong(index, value)
        }

        private fun nullableLong(result: ResultSet, column: String): Long? {
            val value = result.getLong(column)
            return if (result.wasNull()) null else value
        }
    }
}
