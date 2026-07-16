package ai.icen.fw.workflow.notification.persistence.jdbc

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
import ai.icen.fw.workflow.runtime.WorkflowNotificationRecord
import ai.icen.fw.workflow.runtime.WorkflowNotificationReportMutation
import ai.icen.fw.workflow.runtime.WorkflowNotificationStore
import ai.icen.fw.workflow.runtime.WorkflowNotificationStoreCode
import ai.icen.fw.workflow.runtime.WorkflowNotificationStoreResult
import ai.icen.fw.workflow.spi.WorkflowNotificationDelivery
import ai.icen.fw.workflow.spi.WorkflowNotificationDeliveryStatus
import ai.icen.fw.workflow.spi.WorkflowProviderOutcome
import ai.icen.fw.workflow.spi.WorkflowProviderReceipt
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/**
 * Durable notification outbox. This adapter has no provider dependency: every method is a bounded
 * local read or transaction, so a network call cannot accidentally run while a database lock is held.
 * Commit failures are deliberately propagated because their result must be reconciled, never guessed.
 */
class JdbcWorkflowNotificationStore @JvmOverloads constructor(
    dataSource: DataSource,
    dialect: WorkflowNotificationJdbcDialect? = null,
) : WorkflowNotificationStore {
    private val transactions = WorkflowNotificationJdbcTransactions(dataSource, dialect)

    override fun enqueue(batch: WorkflowNotificationEnqueueBatch): WorkflowNotificationStoreResult =
        transactions.transaction { connection, dialect ->
            val existing = selectBatch(
                connection,
                batch.tenantId,
                batch.originIdempotencyKey,
                forUpdate = true,
            )
            if (existing != null) return@transaction replayBatch(connection, batch, existing)

            batch.envelopes.forEach { envelope ->
                if (selectEnvelopeCollision(connection, batch.tenantId, envelope) != null) {
                    return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
                }
            }

            val batchId = stableDigest("workflow-notification-batch", batch.tenantId, batch.originIdempotencyKey)
            val inserted = insertBatch(connection, dialect, batchId, batch)
            val locked = checkNotNull(selectBatch(
                connection,
                batch.tenantId,
                batch.originIdempotencyKey,
                forUpdate = true,
            )) { "Workflow notification batch disappeared after insert." }
            if (!inserted) return@transaction replayBatch(connection, batch, locked)
            require(batchRowMatches(locked, batch, batchId)) {
                "Inserted workflow notification batch projection is inconsistent."
            }

            batch.envelopes.forEachIndexed { ordinal, envelope ->
                insertEnvelope(connection, batch, batchId, ordinal, envelope)
            }
            val first = checkNotNull(selectEnvelope(
                connection,
                batch.tenantId,
                batch.envelopes.first().envelopeId,
                forUpdate = false,
            )) { "Workflow notification envelope disappeared after insert." }
            WorkflowNotificationStoreResult.applied(mapRecord(first))
        }

    override fun load(tenantId: String, envelopeId: String, readAt: Long): WorkflowNotificationRecord? {
        require(readAt >= 0L) { "Workflow notification read time is invalid." }
        return transactions.read { connection, _ ->
            selectEnvelope(connection, tenantId, envelopeId, forUpdate = false)?.let(::mapRecord)
        }
    }

    override fun claim(request: WorkflowNotificationClaim): WorkflowNotificationStoreResult =
        transactions.transaction { connection, _ ->
            val row = selectEnvelope(connection, request.tenantId, request.envelopeId, forUpdate = true)
                ?: return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            val mutationDigest = claimMutationDigest(request)
            replayAfterCas(row, request.expectedVersion, mutationDigest)?.let { return@transaction it }
            if (row.recordVersion != request.expectedVersion) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
            }
            mapRecord(row)
            val ready = row.queueStatus == WorkflowNotificationQueueStatus.QUEUED.code ||
                row.queueStatus == WorkflowNotificationQueueStatus.RETRY_WAIT.code &&
                row.nextAttemptAt != null && row.nextAttemptAt <= request.lease.acquiredAt ||
                row.queueStatus == WorkflowNotificationQueueStatus.LEASED.code &&
                row.leaseExpiresAt != null && row.leaseExpiresAt <= request.lease.acquiredAt &&
                row.providerRequestDigest == null
            if (!ready || request.lease.acquiredAt < row.updatedAt ||
                request.lease.fencingToken <= row.fencingToken || row.attempt == Int.MAX_VALUE ||
                row.recordVersion == Long.MAX_VALUE
            ) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            }
            connection.prepareStatement(CLAIM_SQL).use { statement ->
                statement.setString(1, WorkflowNotificationQueueStatus.LEASED.code)
                statement.setLong(2, row.recordVersion + 1L)
                statement.setInt(3, row.attempt + 1)
                statement.setString(4, request.lease.leaseId)
                statement.setString(5, request.lease.workerId)
                statement.setLong(6, request.lease.fencingToken)
                statement.setLong(7, request.lease.acquiredAt)
                statement.setLong(8, request.lease.expiresAt)
                statement.setString(9, mutationDigest)
                statement.setString(10, request.authorizationEvidenceDigest)
                statement.setLong(11, request.lease.acquiredAt)
                statement.setString(12, request.tenantId)
                statement.setString(13, request.envelopeId)
                statement.setLong(14, request.expectedVersion)
                statement.setString(15, row.queueStatus)
                if (statement.executeUpdate() != 1) {
                    return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
                }
            }
            WorkflowNotificationStoreResult.applied(loadLockedRecord(connection, request.tenantId, request.envelopeId))
        }

    override fun checkpointProviderCall(
        request: WorkflowNotificationProviderCheckpoint,
    ): WorkflowNotificationStoreResult = transactions.transaction { connection, _ ->
        val row = selectEnvelope(connection, request.tenantId, request.envelopeId, forUpdate = true)
            ?: return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
        val mutationDigest = checkpointMutationDigest(request)
        replayAfterCas(row, request.expectedVersion, mutationDigest)?.let { return@transaction it }
        if (row.recordVersion != request.expectedVersion) {
            return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
        }
        mapRecord(row)
        if (row.queueStatus != WorkflowNotificationQueueStatus.LEASED.code ||
            !leaseMatches(row, request.leaseId, request.fencingToken, request.checkpointedAt) ||
            row.recordVersion == Long.MAX_VALUE
        ) {
            return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.LEASE_MISMATCH)
        }
        connection.prepareStatement(CHECKPOINT_SQL).use { statement ->
            statement.setString(1, WorkflowNotificationQueueStatus.PROVIDER_CALL_STARTED.code)
            statement.setLong(2, row.recordVersion + 1L)
            statement.setString(3, request.providerRequestDigest)
            statement.setString(4, mutationDigest)
            statement.setString(5, request.authorizationEvidenceDigest)
            statement.setLong(6, request.checkpointedAt)
            statement.setString(7, request.tenantId)
            statement.setString(8, request.envelopeId)
            statement.setLong(9, request.expectedVersion)
            statement.setString(10, WorkflowNotificationQueueStatus.LEASED.code)
            statement.setString(11, request.leaseId)
            statement.setLong(12, request.fencingToken)
            if (statement.executeUpdate() != 1) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.LEASE_MISMATCH)
            }
        }
        WorkflowNotificationStoreResult.applied(loadLockedRecord(connection, request.tenantId, request.envelopeId))
    }

    override fun complete(request: WorkflowNotificationCompletion): WorkflowNotificationStoreResult =
        transactions.transaction { connection, _ ->
            val row = selectEnvelope(connection, request.tenantId, request.envelopeId, forUpdate = true)
                ?: return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            val mutationDigest = completionMutationDigest(request)
            replayAfterCas(row, request.expectedVersion, mutationDigest)?.let { return@transaction it }
            if (row.recordVersion != request.expectedVersion) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
            }
            val current = mapRecord(row)
            if (!leaseMatches(row, request.leaseId, request.fencingToken, request.completedAt) ||
                request.completedAt < row.updatedAt
            ) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.LEASE_MISMATCH)
            }
            val preCallSuppression = row.queueStatus == WorkflowNotificationQueueStatus.LEASED.code &&
                request.targetStatus == WorkflowNotificationQueueStatus.SUPPRESSED &&
                request.providerReceipt == null
            if (row.queueStatus != WorkflowNotificationQueueStatus.PROVIDER_CALL_STARTED.code && !preCallSuppression) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            }
            if (!completionEvidenceMatches(current.envelope, row.providerRequestDigest, request)) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            }
            if (row.recordVersion == Long.MAX_VALUE) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            }
            val evidencePayload = encodeEvidence(request.providerReceipt, request.delivery)
            connection.prepareStatement(COMPLETE_SQL).use { statement ->
                statement.setString(1, request.targetStatus.code)
                statement.setLong(2, row.recordVersion + 1L)
                setNullableLong(statement, 3, request.nextAttemptAt)
                setNullableBytes(statement, 4, evidencePayload)
                setNullableString(statement, 5, request.providerReceipt?.receiptDigest)
                setNullableString(statement, 6, request.delivery?.deliveryDigest)
                statement.setString(7, request.outcomeEvidenceDigest)
                statement.setString(8, mutationDigest)
                statement.setLong(9, request.completedAt)
                statement.setString(10, request.tenantId)
                statement.setString(11, request.envelopeId)
                statement.setLong(12, request.expectedVersion)
                statement.setString(13, row.queueStatus)
                statement.setString(14, request.leaseId)
                statement.setLong(15, request.fencingToken)
                if (statement.executeUpdate() != 1) {
                    return@transaction WorkflowNotificationStoreResult.failed(
                        WorkflowNotificationStoreCode.LEASE_MISMATCH,
                    )
                }
            }
            WorkflowNotificationStoreResult.applied(loadLockedRecord(connection, request.tenantId, request.envelopeId))
        }

    override fun recordDeliveryReport(
        request: WorkflowNotificationReportMutation,
    ): WorkflowNotificationStoreResult = transactions.transaction { connection, _ ->
        val report = request.report
        val mutationDigest = reportMutationDigest(request)
        val existingReport = selectReport(connection, report.tenantId, report.reportId, forUpdate = true)
        if (existingReport != null) {
            if (!reportRowMatches(existingReport, request, mutationDigest)) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
            }
            val current = selectEnvelope(connection, report.tenantId, report.envelopeId, forUpdate = false)
                ?: return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            return@transaction WorkflowNotificationStoreResult.replayed(mapRecord(current))
        }
        val row = selectEnvelope(connection, report.tenantId, report.envelopeId, forUpdate = true)
            ?: return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
        if (row.recordVersion != request.expectedVersion) {
            return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
        }
        val current = mapRecord(row)
        if ((current.status != WorkflowNotificationQueueStatus.ACCEPTED &&
                current.status != WorkflowNotificationQueueStatus.TRANSIENT_BOUNCE) ||
            !reportMatchesProvider(current, report) || report.observedAt < current.updatedAt ||
            row.recordVersion == Long.MAX_VALUE
        ) {
            return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
        }
        insertReport(connection, request, mutationDigest)
        connection.prepareStatement(REPORT_STATUS_SQL).use { statement ->
            statement.setString(1, report.status.code)
            statement.setLong(2, row.recordVersion + 1L)
            statement.setString(3, report.evidenceDigest)
            statement.setString(4, mutationDigest)
            statement.setString(5, request.authorizationEvidenceDigest)
            statement.setLong(6, report.observedAt)
            statement.setString(7, report.tenantId)
            statement.setString(8, report.envelopeId)
            statement.setLong(9, request.expectedVersion)
            statement.setString(10, row.queueStatus)
            if (statement.executeUpdate() != 1) {
                throw IllegalStateException("Workflow notification delivery report CAS changed while locked.")
            }
        }
        WorkflowNotificationStoreResult.applied(loadLockedRecord(connection, report.tenantId, report.envelopeId))
    }

    override fun reconcile(request: WorkflowNotificationReconciliation): WorkflowNotificationStoreResult =
        transactions.transaction { connection, _ ->
            val row = selectEnvelope(connection, request.tenantId, request.envelopeId, forUpdate = true)
                ?: return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            val mutationDigest = reconciliationMutationDigest(request)
            replayAfterCas(row, request.expectedVersion, mutationDigest)?.let { return@transaction it }
            if (row.recordVersion != request.expectedVersion) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
            }
            val current = mapRecord(row)
            val currentLease = current.lease
            val startedButExpired = current.status == WorkflowNotificationQueueStatus.PROVIDER_CALL_STARTED &&
                currentLease != null && currentLease.expiresAt <= request.reconciledAt
            if ((current.status != WorkflowNotificationQueueStatus.OUTCOME_UNKNOWN && !startedButExpired) ||
                request.reconciledAt < current.updatedAt
            ) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            }
            if (!reconciliationEvidenceMatches(current.envelope, row.providerRequestDigest, request)) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            }
            if (row.recordVersion == Long.MAX_VALUE) {
                return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            }
            val target = when (request.resolution) {
                WorkflowNotificationReconciliationResolution.ACCEPTED -> WorkflowNotificationQueueStatus.ACCEPTED
                WorkflowNotificationReconciliationResolution.NOT_SENT -> WorkflowNotificationQueueStatus.RETRY_WAIT
                WorkflowNotificationReconciliationResolution.TERMINAL_FAILURE ->
                    WorkflowNotificationQueueStatus.TERMINAL_FAILURE
                else -> return@transaction WorkflowNotificationStoreResult.failed(
                    WorkflowNotificationStoreCode.NOT_ELIGIBLE,
                )
            }
            val evidencePayload = encodeEvidence(request.providerReceipt, request.delivery)
            connection.prepareStatement(RECONCILE_SQL).use { statement ->
                statement.setString(1, target.code)
                statement.setLong(2, row.recordVersion + 1L)
                setNullableLong(statement, 3, request.nextAttemptAt)
                setNullableBytes(statement, 4, evidencePayload)
                setNullableString(statement, 5, request.providerReceipt?.receiptDigest)
                setNullableString(statement, 6, request.delivery?.deliveryDigest)
                statement.setString(7, request.evidenceDigest)
                statement.setString(8, mutationDigest)
                statement.setString(9, request.authorizationEvidenceDigest)
                statement.setLong(10, request.reconciledAt)
                statement.setString(11, request.tenantId)
                statement.setString(12, request.envelopeId)
                statement.setLong(13, request.expectedVersion)
                statement.setString(14, row.queueStatus)
                if (statement.executeUpdate() != 1) {
                    return@transaction WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
                }
            }
            WorkflowNotificationStoreResult.applied(loadLockedRecord(connection, request.tenantId, request.envelopeId))
        }

    private fun replayBatch(
        connection: Connection,
        requested: WorkflowNotificationEnqueueBatch,
        stored: BatchRow,
    ): WorkflowNotificationStoreResult {
        val batchId = stableDigest("workflow-notification-batch", requested.tenantId, requested.originIdempotencyKey)
        if (!batchRowMatches(stored, requested, batchId)) {
            return WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
        }
        val rows = selectBatchEnvelopes(connection, requested.tenantId, batchId)
        require(rows.size == requested.envelopes.size) {
            "Persisted workflow notification batch envelope count is inconsistent."
        }
        rows.zip(requested.envelopes).forEach { (row, envelope) ->
            val restored = mapRecord(row)
            require(row.envelopeId == envelope.envelopeId &&
                row.deduplicationKey == envelope.deduplicationKey &&
                restored.envelope.envelopeDigest == envelope.envelopeDigest
            ) { "Persisted workflow notification batch membership is inconsistent." }
        }
        return WorkflowNotificationStoreResult.replayed(mapRecord(rows.first()))
    }

    private fun insertBatch(
        connection: Connection,
        dialect: WorkflowNotificationJdbcDialect,
        id: String,
        batch: WorkflowNotificationEnqueueBatch,
    ): Boolean = connection.prepareStatement(insertBatchSql(dialect)).use { statement ->
        statement.setString(1, id)
        statement.setString(2, batch.tenantId)
        statement.setString(3, batch.originIdempotencyKey)
        statement.setString(4, batch.originIntentDigest)
        statement.setString(5, batch.batchDigest)
        statement.setString(6, batch.authorizationEvidenceDigest)
        statement.setInt(7, batch.envelopes.size)
        statement.setString(8, batch.envelopes.first().envelopeId)
        statement.setLong(9, batch.enqueuedAt)
        statement.setLong(10, batch.enqueuedAt)
        statement.setLong(11, batch.enqueuedAt)
        statement.executeUpdate() == 1
    }

    private fun insertEnvelope(
        connection: Connection,
        batch: WorkflowNotificationEnqueueBatch,
        batchId: String,
        ordinal: Int,
        envelope: WorkflowNotificationEnvelope,
    ) {
        val payload = WorkflowNotificationJdbcCodec.encodeEnvelope(envelope)
        connection.prepareStatement(INSERT_ENVELOPE_SQL).use { statement ->
            statement.setString(1, envelope.envelopeId)
            statement.setString(2, batch.tenantId)
            statement.setString(3, batchId)
            statement.setInt(4, ordinal)
            statement.setString(5, envelope.deduplicationKey)
            statement.setString(6, envelope.originIntentDigest)
            statement.setString(7, envelope.envelopeDigest)
            statement.setBytes(8, payload)
            statement.setString(9, WorkflowNotificationQueueStatus.QUEUED.code)
            statement.setLong(10, 0L)
            statement.setInt(11, 0)
            statement.setLong(12, 0L)
            statement.setString(13, batch.batchDigest)
            statement.setString(14, batch.authorizationEvidenceDigest)
            statement.setLong(15, envelope.enqueuedAt)
            statement.setLong(16, envelope.enqueuedAt)
            check(statement.executeUpdate() == 1) { "Workflow notification envelope insert failed." }
        }
    }

    private fun insertReport(
        connection: Connection,
        request: WorkflowNotificationReportMutation,
        mutationDigest: String,
    ) {
        val report = request.report
        connection.prepareStatement(INSERT_REPORT_SQL).use { statement ->
            statement.setString(1, report.reportId)
            statement.setString(2, report.tenantId)
            statement.setString(3, report.envelopeId)
            statement.setString(4, report.providerId)
            statement.setString(5, report.providerRevision)
            statement.setString(6, report.providerMessageRef)
            statement.setString(7, report.status.code)
            statement.setString(8, report.evidenceDigest)
            statement.setString(9, report.reportDigest)
            statement.setLong(10, request.expectedVersion)
            statement.setString(11, request.authorizationEvidenceDigest)
            statement.setString(12, mutationDigest)
            statement.setLong(13, report.observedAt)
            statement.setLong(14, report.observedAt)
            statement.setLong(15, report.observedAt)
            check(statement.executeUpdate() == 1) { "Workflow notification delivery report insert failed." }
        }
    }

    private fun selectBatch(
        connection: Connection,
        tenantId: String,
        originIdempotencyKey: String,
        forUpdate: Boolean,
    ): BatchRow? = connection.prepareStatement(SELECT_BATCH_SQL + lock(forUpdate)).use { statement ->
        statement.setString(1, tenantId)
        statement.setString(2, originIdempotencyKey)
        statement.executeQuery().use { result -> if (result.next()) mapBatch(result) else null }
    }

    private fun selectBatchEnvelopes(
        connection: Connection,
        tenantId: String,
        batchId: String,
    ): List<EnvelopeRow> = connection.prepareStatement(SELECT_BATCH_ENVELOPES_SQL).use { statement ->
        statement.setString(1, tenantId)
        statement.setString(2, batchId)
        statement.executeQuery().use { result ->
            ArrayList<EnvelopeRow>().also { rows -> while (result.next()) rows += mapEnvelope(result) }
        }
    }

    private fun selectEnvelopeCollision(
        connection: Connection,
        tenantId: String,
        envelope: WorkflowNotificationEnvelope,
    ): String? = connection.prepareStatement(SELECT_ENVELOPE_COLLISION_SQL).use { statement ->
        statement.setString(1, tenantId)
        statement.setString(2, envelope.envelopeId)
        statement.setString(3, envelope.deduplicationKey)
        statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
    }

    private fun selectEnvelope(
        connection: Connection,
        tenantId: String,
        envelopeId: String,
        forUpdate: Boolean,
    ): EnvelopeRow? = connection.prepareStatement(SELECT_ENVELOPE_SQL + lock(forUpdate)).use { statement ->
        statement.setString(1, tenantId)
        statement.setString(2, envelopeId)
        statement.executeQuery().use { result -> if (result.next()) mapEnvelope(result) else null }
    }

    private fun selectReport(
        connection: Connection,
        tenantId: String,
        reportId: String,
        forUpdate: Boolean,
    ): ReportRow? = connection.prepareStatement(SELECT_REPORT_SQL + lock(forUpdate)).use { statement ->
        statement.setString(1, tenantId)
        statement.setString(2, reportId)
        statement.executeQuery().use { result -> if (result.next()) mapReport(result) else null }
    }

    private fun loadLockedRecord(connection: Connection, tenantId: String, envelopeId: String): WorkflowNotificationRecord =
        mapRecord(checkNotNull(selectEnvelope(connection, tenantId, envelopeId, forUpdate = false)) {
            "Workflow notification record disappeared after mutation."
        })

    private fun mapRecord(row: EnvelopeRow): WorkflowNotificationRecord {
        val envelope = WorkflowNotificationJdbcCodec.decodeEnvelope(row.envelopePayload)
        require(envelope.envelopeId == row.envelopeId && envelope.deduplicationKey == row.deduplicationKey &&
            envelope.originIntentDigest == row.originIntentDigest && envelope.envelopeDigest == row.envelopeDigest &&
            envelope.enqueuedAt == row.createdAt
        ) { "Persisted workflow notification envelope projection is inconsistent." }
        val lease = row.leaseId?.let {
            WorkflowNotificationLease.of(
                it,
                requireNotNull(row.workerId),
                row.fencingToken,
                requireNotNull(row.leaseAcquiredAt),
                requireNotNull(row.leaseExpiresAt),
            )
        }
        require((lease == null) == (row.workerId == null && row.leaseAcquiredAt == null && row.leaseExpiresAt == null)) {
            "Persisted workflow notification lease projection is incomplete."
        }
        val evidence = row.providerEvidencePayload?.let(WorkflowNotificationJdbcCodec::decodeProviderEvidence)
        require((evidence == null) == (row.providerReceiptDigest == null && row.deliveryDigest == null)) {
            "Persisted workflow notification provider projection is incomplete."
        }
        evidence?.let {
            require(it.receipt.receiptDigest == row.providerReceiptDigest &&
                it.delivery.deliveryDigest == row.deliveryDigest &&
                it.receipt.tenantId == row.tenantId &&
                it.receipt.providerId == envelope.intent.template.providerId &&
                it.receipt.requestDigest == row.providerRequestDigest
            ) { "Persisted workflow notification provider projection is inconsistent." }
        }
        return WorkflowNotificationRecord.restore(
            row.tenantId,
            envelope,
            WorkflowNotificationQueueStatus.of(row.queueStatus),
            row.recordVersion,
            row.attempt,
            lease,
            row.nextAttemptAt,
            row.providerRequestDigest,
            evidence?.receipt,
            evidence?.delivery,
            row.outcomeEvidenceDigest,
            row.updatedAt,
        )
    }

    private fun mapBatch(result: ResultSet): BatchRow = BatchRow(
        result.getString("id"),
        result.getString("tenant_id"),
        result.getString("origin_idempotency_key"),
        result.getString("origin_intent_digest"),
        result.getString("batch_digest"),
        result.getString("authorization_evidence_digest"),
        result.getInt("envelope_count"),
        result.getString("first_envelope_id"),
        result.getLong("enqueued_time"),
        result.getLong("created_time"),
        result.getLong("updated_time"),
    )

    private fun mapEnvelope(result: ResultSet): EnvelopeRow = EnvelopeRow(
        result.getString("id"),
        result.getString("tenant_id"),
        result.getString("batch_id"),
        result.getInt("batch_ordinal"),
        result.getString("deduplication_key"),
        result.getString("origin_intent_digest"),
        result.getString("envelope_digest"),
        result.getBytes("envelope_payload"),
        result.getString("queue_status"),
        result.getLong("record_version"),
        result.getInt("attempt_count"),
        result.getString("lease_id"),
        result.getString("worker_id"),
        result.getLong("fencing_token"),
        nullableLong(result, "lease_acquired_time"),
        nullableLong(result, "lease_expires_time"),
        nullableLong(result, "next_attempt_time"),
        result.getString("provider_request_digest"),
        result.getBytes("provider_evidence_payload"),
        result.getString("provider_receipt_digest"),
        result.getString("delivery_digest"),
        result.getString("outcome_evidence_digest"),
        result.getString("last_mutation_digest"),
        result.getString("authorization_evidence_digest"),
        result.getLong("created_time"),
        result.getLong("updated_time"),
    )

    private fun mapReport(result: ResultSet): ReportRow = ReportRow(
        result.getString("id"),
        result.getString("tenant_id"),
        result.getString("envelope_id"),
        result.getString("provider_id"),
        result.getString("provider_revision"),
        result.getString("provider_message_ref"),
        result.getString("delivery_status"),
        result.getString("evidence_digest"),
        result.getString("report_digest"),
        result.getLong("expected_version"),
        result.getString("authorization_evidence_digest"),
        result.getString("mutation_digest"),
        result.getLong("observed_time"),
    )

    private fun replayAfterCas(
        row: EnvelopeRow,
        expectedVersion: Long,
        mutationDigest: String,
    ): WorkflowNotificationStoreResult? = if (expectedVersion < Long.MAX_VALUE &&
        row.recordVersion == expectedVersion + 1L && row.lastMutationDigest == mutationDigest
    ) {
        WorkflowNotificationStoreResult.replayed(mapRecord(row))
    } else {
        null
    }

    private fun leaseMatches(row: EnvelopeRow, leaseId: String, fencingToken: Long, at: Long): Boolean =
        row.leaseId == leaseId && row.fencingToken == fencingToken && row.leaseAcquiredAt != null &&
            row.leaseAcquiredAt <= at && row.leaseExpiresAt != null && row.leaseExpiresAt > at

    private fun completionEvidenceMatches(
        envelope: WorkflowNotificationEnvelope,
        providerRequestDigest: String?,
        request: WorkflowNotificationCompletion,
    ): Boolean {
        val receipt = request.providerReceipt
        val delivery = request.delivery
        if (receipt == null || delivery == null) {
            return receipt == null && delivery == null &&
                request.targetStatus != WorkflowNotificationQueueStatus.ACCEPTED
        }
        val expectedDeliveryStatus = when (request.targetStatus) {
            WorkflowNotificationQueueStatus.ACCEPTED -> WorkflowNotificationDeliveryStatus.ACCEPTED
            WorkflowNotificationQueueStatus.SUPPRESSED -> WorkflowNotificationDeliveryStatus.SUPPRESSED
            else -> return false
        }
        return delivery.status == expectedDeliveryStatus && receipt.outcome == WorkflowProviderOutcome.SUCCESS &&
            receipt.tenantId == request.tenantId && receipt.providerId == envelope.intent.template.providerId &&
            receipt.requestDigest == providerRequestDigest && receipt.resultDigest == delivery.deliveryDigest &&
            receipt.completedAtEpochMilli <= request.completedAt && request.completedAt <= receipt.expiresAtEpochMilli
    }

    private fun reconciliationEvidenceMatches(
        envelope: WorkflowNotificationEnvelope,
        providerRequestDigest: String?,
        request: WorkflowNotificationReconciliation,
    ): Boolean {
        return when (request.resolution) {
            WorkflowNotificationReconciliationResolution.ACCEPTED -> {
                val receipt = request.providerReceipt ?: return false
                val delivery = request.delivery ?: return false
                receipt.outcome == WorkflowProviderOutcome.SUCCESS &&
                    delivery.status == WorkflowNotificationDeliveryStatus.ACCEPTED &&
                    receipt.tenantId == request.tenantId &&
                    receipt.providerId == envelope.intent.template.providerId &&
                    receipt.requestDigest == providerRequestDigest &&
                    receipt.resultDigest == delivery.deliveryDigest &&
                    receipt.completedAtEpochMilli <= request.reconciledAt
            }
            WorkflowNotificationReconciliationResolution.NOT_SENT,
            WorkflowNotificationReconciliationResolution.TERMINAL_FAILURE ->
                request.providerReceipt == null && request.delivery == null
            else -> false
        }
    }

    private fun reportMatchesProvider(
        record: WorkflowNotificationRecord,
        report: WorkflowNotificationDeliveryReport,
    ): Boolean {
        val receipt = record.providerReceipt ?: return false
        val delivery = record.delivery ?: return false
        return receipt.outcome == WorkflowProviderOutcome.SUCCESS && receipt.providerId == report.providerId &&
            receipt.providerRevision == report.providerRevision &&
            delivery.status == WorkflowNotificationDeliveryStatus.ACCEPTED &&
            delivery.providerMessageRef == report.providerMessageRef
    }

    private fun batchRowMatches(row: BatchRow, batch: WorkflowNotificationEnqueueBatch, id: String): Boolean =
        row.id == id && row.tenantId == batch.tenantId &&
            row.originIdempotencyKey == batch.originIdempotencyKey &&
            row.originIntentDigest == batch.originIntentDigest && row.batchDigest == batch.batchDigest &&
            row.authorizationEvidenceDigest == batch.authorizationEvidenceDigest &&
            row.envelopeCount == batch.envelopes.size && row.firstEnvelopeId == batch.envelopes.first().envelopeId &&
            row.enqueuedAt == batch.enqueuedAt && row.createdAt == batch.enqueuedAt && row.updatedAt >= row.createdAt

    private fun reportRowMatches(
        row: ReportRow,
        request: WorkflowNotificationReportMutation,
        mutationDigest: String,
    ): Boolean {
        val report = request.report
        return row.id == report.reportId && row.tenantId == report.tenantId && row.envelopeId == report.envelopeId &&
            row.providerId == report.providerId && row.providerRevision == report.providerRevision &&
            row.providerMessageRef == report.providerMessageRef && row.status == report.status.code &&
            row.evidenceDigest == report.evidenceDigest && row.reportDigest == report.reportDigest &&
            row.expectedVersion == request.expectedVersion &&
            row.authorizationEvidenceDigest == request.authorizationEvidenceDigest &&
            row.mutationDigest == mutationDigest && row.observedAt == report.observedAt
    }

    private fun encodeEvidence(receipt: WorkflowProviderReceipt?, delivery: WorkflowNotificationDelivery?): ByteArray? =
        if (receipt == null && delivery == null) null else WorkflowNotificationJdbcCodec.encodeProviderEvidence(
            requireNotNull(receipt),
            requireNotNull(delivery),
        )

    private data class BatchRow(
        val id: String,
        val tenantId: String,
        val originIdempotencyKey: String,
        val originIntentDigest: String,
        val batchDigest: String,
        val authorizationEvidenceDigest: String,
        val envelopeCount: Int,
        val firstEnvelopeId: String,
        val enqueuedAt: Long,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private data class EnvelopeRow(
        val envelopeId: String,
        val tenantId: String,
        val batchId: String,
        val batchOrdinal: Int,
        val deduplicationKey: String,
        val originIntentDigest: String,
        val envelopeDigest: String,
        val envelopePayload: ByteArray,
        val queueStatus: String,
        val recordVersion: Long,
        val attempt: Int,
        val leaseId: String?,
        val workerId: String?,
        val fencingToken: Long,
        val leaseAcquiredAt: Long?,
        val leaseExpiresAt: Long?,
        val nextAttemptAt: Long?,
        val providerRequestDigest: String?,
        val providerEvidencePayload: ByteArray?,
        val providerReceiptDigest: String?,
        val deliveryDigest: String?,
        val outcomeEvidenceDigest: String?,
        val lastMutationDigest: String,
        val authorizationEvidenceDigest: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private data class ReportRow(
        val id: String,
        val tenantId: String,
        val envelopeId: String,
        val providerId: String,
        val providerRevision: String,
        val providerMessageRef: String,
        val status: String,
        val evidenceDigest: String,
        val reportDigest: String,
        val expectedVersion: Long,
        val authorizationEvidenceDigest: String,
        val mutationDigest: String,
        val observedAt: Long,
    )

    private companion object {
        const val SELECT_BATCH_SQL = """
            SELECT id, tenant_id, origin_idempotency_key, origin_intent_digest, batch_digest,
                authorization_evidence_digest, envelope_count, first_envelope_id, enqueued_time,
                created_time, updated_time
            FROM fw_wf_notification_batch WHERE tenant_id = ? AND origin_idempotency_key = ?
        """
        const val INSERT_BATCH_SQL = """
            INSERT INTO fw_wf_notification_batch(
                id, tenant_id, origin_idempotency_key, origin_intent_digest, batch_digest,
                authorization_evidence_digest, envelope_count, first_envelope_id, enqueued_time,
                created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val ENVELOPE_COLUMNS = """
            id, tenant_id, batch_id, batch_ordinal, deduplication_key, origin_intent_digest,
            envelope_digest, envelope_payload, queue_status, record_version, attempt_count,
            lease_id, worker_id, fencing_token, lease_acquired_time, lease_expires_time,
            next_attempt_time, provider_request_digest, provider_evidence_payload,
            provider_receipt_digest, delivery_digest, outcome_evidence_digest,
            last_mutation_digest, authorization_evidence_digest, created_time, updated_time
        """
        const val SELECT_ENVELOPE_SQL = "SELECT $ENVELOPE_COLUMNS FROM fw_wf_notification_envelope" +
            " WHERE tenant_id = ? AND id = ?"
        const val SELECT_BATCH_ENVELOPES_SQL = "SELECT $ENVELOPE_COLUMNS FROM fw_wf_notification_envelope" +
            " WHERE tenant_id = ? AND batch_id = ? ORDER BY batch_ordinal"
        const val SELECT_ENVELOPE_COLLISION_SQL = """
            SELECT id FROM fw_wf_notification_envelope
            WHERE tenant_id = ? AND (id = ? OR deduplication_key = ?)
        """
        const val INSERT_ENVELOPE_SQL = """
            INSERT INTO fw_wf_notification_envelope(
                id, tenant_id, batch_id, batch_ordinal, deduplication_key, origin_intent_digest,
                envelope_digest, envelope_payload, queue_status, record_version, attempt_count,
                fencing_token, last_mutation_digest, authorization_evidence_digest, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val CLAIM_SQL = """
            UPDATE fw_wf_notification_envelope
            SET queue_status = ?, record_version = ?, attempt_count = ?, lease_id = ?, worker_id = ?,
                fencing_token = ?, lease_acquired_time = ?, lease_expires_time = ?, next_attempt_time = NULL,
                provider_request_digest = NULL, provider_evidence_payload = NULL,
                provider_receipt_digest = NULL, delivery_digest = NULL, outcome_evidence_digest = NULL,
                last_mutation_digest = ?, authorization_evidence_digest = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ? AND queue_status = ?
        """
        const val CHECKPOINT_SQL = """
            UPDATE fw_wf_notification_envelope
            SET queue_status = ?, record_version = ?, provider_request_digest = ?,
                last_mutation_digest = ?, authorization_evidence_digest = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ? AND queue_status = ?
                AND lease_id = ? AND fencing_token = ?
        """
        const val COMPLETE_SQL = """
            UPDATE fw_wf_notification_envelope
            SET queue_status = ?, record_version = ?, lease_id = NULL, worker_id = NULL,
                lease_acquired_time = NULL, lease_expires_time = NULL, next_attempt_time = ?,
                provider_evidence_payload = ?, provider_receipt_digest = ?, delivery_digest = ?,
                outcome_evidence_digest = ?, last_mutation_digest = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ? AND queue_status = ?
                AND lease_id = ? AND fencing_token = ?
        """
        const val REPORT_STATUS_SQL = """
            UPDATE fw_wf_notification_envelope
            SET queue_status = ?, record_version = ?, outcome_evidence_digest = ?,
                last_mutation_digest = ?, authorization_evidence_digest = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ? AND queue_status = ?
        """
        const val RECONCILE_SQL = """
            UPDATE fw_wf_notification_envelope
            SET queue_status = ?, record_version = ?, lease_id = NULL, worker_id = NULL,
                lease_acquired_time = NULL, lease_expires_time = NULL, next_attempt_time = ?,
                provider_evidence_payload = ?, provider_receipt_digest = ?, delivery_digest = ?,
                outcome_evidence_digest = ?, last_mutation_digest = ?, authorization_evidence_digest = ?,
                updated_time = ?
            WHERE tenant_id = ? AND id = ? AND record_version = ? AND queue_status = ?
        """
        const val INSERT_REPORT_SQL = """
            INSERT INTO fw_wf_notification_delivery_report(
                id, tenant_id, envelope_id, provider_id, provider_revision, provider_message_ref,
                delivery_status, evidence_digest, report_digest, expected_version,
                authorization_evidence_digest, mutation_digest, observed_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val SELECT_REPORT_SQL = """
            SELECT id, tenant_id, envelope_id, provider_id, provider_revision, provider_message_ref,
                delivery_status, evidence_digest, report_digest, expected_version,
                authorization_evidence_digest, mutation_digest, observed_time
            FROM fw_wf_notification_delivery_report WHERE tenant_id = ? AND id = ?
        """

        fun insertBatchSql(dialect: WorkflowNotificationJdbcDialect): String = when (dialect) {
            WorkflowNotificationJdbcDialect.MYSQL -> INSERT_BATCH_SQL + " ON DUPLICATE KEY UPDATE id = id"
            WorkflowNotificationJdbcDialect.POSTGRESQL,
            WorkflowNotificationJdbcDialect.KINGBASE -> INSERT_BATCH_SQL + " ON CONFLICT DO NOTHING"
        }

        fun lock(forUpdate: Boolean): String = if (forUpdate) " FOR UPDATE" else ""
    }
}

private fun claimMutationDigest(request: WorkflowNotificationClaim): String = stableDigest(
    "workflow-notification-claim-v1",
    request.tenantId,
    request.envelopeId,
    request.expectedVersion.toString(),
    request.lease.leaseId,
    request.lease.workerId,
    request.lease.fencingToken.toString(),
    request.lease.acquiredAt.toString(),
    request.lease.expiresAt.toString(),
    request.authorizationEvidenceDigest,
)

private fun checkpointMutationDigest(request: WorkflowNotificationProviderCheckpoint): String = stableDigest(
    "workflow-notification-checkpoint-v1",
    request.tenantId,
    request.envelopeId,
    request.expectedVersion.toString(),
    request.leaseId,
    request.fencingToken.toString(),
    request.providerRequestDigest,
    request.authorizationEvidenceDigest,
    request.checkpointedAt.toString(),
)

private fun completionMutationDigest(request: WorkflowNotificationCompletion): String = stableDigest(
    "workflow-notification-completion-v1",
    request.tenantId,
    request.envelopeId,
    request.expectedVersion.toString(),
    request.leaseId,
    request.fencingToken.toString(),
    request.targetStatus.code,
    request.providerReceipt?.receiptDigest ?: "",
    request.delivery?.deliveryDigest ?: "",
    request.outcomeEvidenceDigest,
    request.nextAttemptAt?.toString() ?: "",
    request.completedAt.toString(),
)

private fun reportMutationDigest(request: WorkflowNotificationReportMutation): String = stableDigest(
    "workflow-notification-report-v1",
    request.report.reportDigest,
    request.expectedVersion.toString(),
    request.authorizationEvidenceDigest,
)

private fun reconciliationMutationDigest(request: WorkflowNotificationReconciliation): String = stableDigest(
    "workflow-notification-reconciliation-v1",
    request.tenantId,
    request.envelopeId,
    request.expectedVersion.toString(),
    request.resolution.code,
    request.providerReceipt?.receiptDigest ?: "",
    request.delivery?.deliveryDigest ?: "",
    request.evidenceDigest,
    request.authorizationEvidenceDigest,
    request.nextAttemptAt?.toString() ?: "",
    request.reconciledAt.toString(),
)

private fun stableDigest(vararg values: String): String {
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
    val alphabet = "0123456789abcdef"
    return buildString(64) {
        digest.digest().forEach { byte ->
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

private fun setNullableBytes(statement: PreparedStatement, index: Int, value: ByteArray?) {
    if (value == null) statement.setNull(index, Types.BINARY) else statement.setBytes(index, value)
}

private fun nullableLong(result: ResultSet, column: String): Long? {
    val value = result.getLong(column)
    return if (result.wasNull()) null else value
}
