package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.outbox.OutboxEventStatus
import ai.icen.fw.application.retention.SecureDeletionApplicationService
import ai.icen.fw.application.retention.DeletionVisibilityQuery
import ai.icen.fw.application.retention.DeletionVisibilityQuerySource
import ai.icen.fw.application.retention.SecureDeletionCompletionEvidence
import ai.icen.fw.application.retention.SecureDeletionExecution
import ai.icen.fw.application.retention.SecureDeletionExecutionStatus
import ai.icen.fw.application.retention.SecureDeletionFailureEvidence
import ai.icen.fw.application.retention.SecureDeletionRepository
import ai.icen.fw.application.retention.StoredSecureDeletionReceipt
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.retention.DeletionAuditEvidence
import ai.icen.fw.domain.retention.DeletionTombstone
import ai.icen.fw.domain.retention.SecureDeletionDecisionReason
import ai.icen.fw.domain.retention.SecureDeletionPlan
import ai.icen.fw.domain.retention.SecureDeletionStage
import ai.icen.fw.spi.retention.SecureDeletionProviderReceipt
import ai.icen.fw.spi.retention.SecureDeletionProviderStatus
import ai.icen.fw.spi.retention.SecureDeletionTarget
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

/**
 * Tenant-fenced JDBC persistence for tombstone-first secure deletion.
 *
 * Every method requires [JdbcApplicationTransaction]. External providers are
 * never called here. Plan locks are acquired before Outbox locks everywhere,
 * matching the worker's lock order and making stage, revision and lease fences
 * one atomic local decision.
 */
class JdbcSecureDeletionRepository(
    objectMapper: ObjectMapper,
) : SecureDeletionRepository, DeletionVisibilityQuerySource {
    private val jsonCodec = SecureDeletionJsonCodec(objectMapper)
    private val visibilityQuery = JdbcDeletionVisibilityQuery()

    override fun deletionVisibilityQuery(): DeletionVisibilityQuery = visibilityQuery

    override fun createIfAbsent(plan: SecureDeletionPlan, dispatchEventId: Identifier): Boolean {
        validatePlanBoundary(plan, dispatchEventId)
        val dialect = JdbcConnectionContext.requireDialect()
        val connection = JdbcConnectionContext.requireCurrent()
        val createToken = UUID.randomUUID().toString()
        val execution = SecureDeletionExecution.pending(plan, dispatchEventId)
        connection.prepareStatement(
            """
            INSERT INTO fw_secure_deletion_plan(
                id, tenant_id, create_token, dispatch_event_id, tombstone_id, decision_evidence_id,
                resource_type, resource_id, resource_revision, requested_by,
                policy_revision, legal_hold_revision, authorization_revision,
                index_idempotency_key, object_idempotency_key, current_stage, execution_status,
                failure_count, last_error, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ${dialect.upsertClause(listOf("tenant_id", "id"), emptyList())}
            """.trimIndent(),
        ).use { statement ->
            bindPlanInsert(statement, plan, execution, createToken)
            statement.executeUpdate()
        }

        val stored = findPlanRow(plan.tenantId, plan.id, lock = true)
            ?: throw SecureDeletionPersistenceException(
                "Secure-deletion plan insert conflicted outside the requested tenant.",
            )
        ensurePlanMatches(stored, plan, dispatchEventId)
        val inserted = stored.createToken == createToken
        if (inserted) {
            insertTombstone(plan.tombstone)
            if (!insertDecisionAudit(plan.decisionAuditEvidence)) {
                throw SecureDeletionPersistenceException(
                    "A newly created secure-deletion plan collided with existing decision evidence.",
                )
            }
        } else {
            val tombstone = findTombstone(plan.tenantId, plan.tombstone.id, lock = true)
                ?: throw SecureDeletionPersistenceException(
                    "Secure-deletion replay is missing its tenant-bound tombstone.",
                )
            ensureTombstoneMatches(tombstone, plan.tombstone)
            val audit = findAudit(plan.tenantId, plan.decisionAuditEvidence.id, lock = true)
                ?: throw SecureDeletionPersistenceException(
                    "Secure-deletion replay is missing its tenant-bound decision evidence.",
                )
            ensureAuditMatches(audit, decisionRecord(plan.decisionAuditEvidence))
        }
        return inserted
    }

    override fun appendDecisionAuditIfAbsent(evidence: DeletionAuditEvidence): Boolean {
        validateDecisionEvidenceBoundary(evidence)
        if (evidence.reason == SecureDeletionDecisionReason.ALLOWED) {
            throw SecureDeletionPersistenceException(
                "Allowed secure-deletion evidence must be persisted atomically with its plan and tombstone.",
            )
        }
        return insertDecisionAudit(evidence)
    }

    override fun findByPlanId(tenantId: Identifier, planId: Identifier): SecureDeletionExecution? {
        validateIdentifier(tenantId, "tenant id")
        validateIdentifier(planId, "plan id")
        val row = findPlanRow(tenantId, planId, lock = false) ?: return null
        return row.toExecution(findReceiptRows(tenantId, planId).map(PersistedReceiptRow::receipt))
    }

    override fun findForMutation(tenantId: Identifier, planId: Identifier): SecureDeletionExecution? {
        validateIdentifier(tenantId, "tenant id")
        validateIdentifier(planId, "plan id")
        val row = findPlanRow(tenantId, planId, lock = true) ?: return null
        return row.toExecution(findReceiptRows(tenantId, planId).map(PersistedReceiptRow::receipt))
    }

    override fun save(execution: SecureDeletionExecution) {
        validateExecutionBoundary(execution)
        val current = findPlanRow(execution.tenantId, execution.planId, lock = true)
            ?: throw SecureDeletionPersistenceException(
                "Secure-deletion execution no longer exists in the requested tenant.",
            )
        ensureExecutionIdentity(current, execution)
        val currentReceipts = findReceiptRows(execution.tenantId, execution.planId)
        validateExecutionTransition(current, currentReceipts, execution)
        requireOutboxFence(execution)

        val incomingByStage = execution.receipts.associateBy { it.stage }
        val currentByStage = currentReceipts.associateBy { it.receipt.stage }
        currentByStage.keys.forEach { stage ->
            if (stage !in incomingByStage) {
                throw SecureDeletionPersistenceException(
                    "Secure-deletion save attempted to remove durable provider evidence.",
                )
            }
        }
        incomingByStage.forEach { (stage, incoming) ->
            val persisted = currentByStage[stage]
            if (persisted == null) {
                insertReceipt(execution, incoming)
            } else {
                updateReceiptIfChanged(execution, persisted, incoming)
            }
        }

        if (projectionEquals(current, execution)) return
        val updated = JdbcConnectionContext.requireCurrent().prepareStatement(UPDATE_PLAN_SQL).use { statement ->
            statement.setString(1, execution.currentStage.name)
            statement.setString(2, execution.status.name)
            statement.setInt(3, execution.failureCount)
            statement.setNullableString(4, execution.lastError)
            statement.setLong(5, execution.updatedAt)
            statement.setString(6, execution.tenantId.value)
            statement.setString(7, execution.planId.value)
            statement.setLong(8, execution.resourceRevision)
            statement.setString(9, current.currentStage.name)
            statement.setString(10, current.status.name)
            statement.setLong(11, current.updatedAt)
            statement.executeUpdate()
        }
        if (updated != 1) {
            throw SecureDeletionPersistenceException(
                "Secure-deletion stage or revision fence changed before persistence.",
            )
        }
    }

    override fun appendCompletionEvidenceIfAbsent(evidence: SecureDeletionCompletionEvidence): Boolean {
        validateCompletionBoundary(evidence)
        val plan = findPlanRow(evidence.tenantId, evidence.planId, lock = true)
            ?: throw SecureDeletionPersistenceException(
                "Secure-deletion completion has no tenant-bound plan.",
            )
        ensureTerminalIdentity(plan, evidence.resourceType, evidence.resourceId, evidence.resourceRevision, evidence.tombstoneId)
        if (plan.status != SecureDeletionExecutionStatus.SUCCEEDED ||
            plan.currentStage != SecureDeletionStage.APPEND_COMPLETION_AUDIT
        ) {
            throw SecureDeletionPersistenceException(
                "Secure-deletion completion evidence is not backed by a successful execution.",
            )
        }
        val persisted = findReceiptRows(evidence.tenantId, evidence.planId).map(PersistedReceiptRow::receipt)
        ensureCompletionReceiptsMatch(persisted, evidence.receipts)
        return insertAudit(
            AuditRecord(
                id = evidence.id,
                tenantId = evidence.tenantId,
                evidenceType = AuditEvidenceType.COMPLETION,
                planId = evidence.planId,
                tombstoneId = evidence.tombstoneId,
                resourceType = evidence.resourceType,
                resourceId = evidence.resourceId,
                resourceRevision = evidence.resourceRevision,
                requestedBy = plan.requestedBy,
                occurredAt = evidence.completedAt,
                decisionReason = null,
                policyRevision = null,
                legalHoldRevision = null,
                authorizationRevision = null,
                activeLegalHoldIds = emptyList(),
                failedStage = null,
                failureCount = null,
                message = null,
            ),
        )
    }

    override fun appendFailureEvidenceIfAbsent(evidence: SecureDeletionFailureEvidence): Boolean {
        validateFailureBoundary(evidence)
        val plan = findPlanRow(evidence.tenantId, evidence.planId, lock = true)
            ?: throw SecureDeletionPersistenceException("Secure-deletion failure has no tenant-bound plan.")
        ensureTerminalIdentity(plan, evidence.resourceType, evidence.resourceId, evidence.resourceRevision, evidence.tombstoneId)
        if (plan.status != SecureDeletionExecutionStatus.FAILED ||
            plan.currentStage != evidence.failedStage ||
            plan.failureCount != evidence.failureCount ||
            plan.lastError != evidence.message
        ) {
            throw SecureDeletionPersistenceException(
                "Secure-deletion failure evidence does not match its durable terminal execution.",
            )
        }
        return insertAudit(
            AuditRecord(
                id = evidence.id,
                tenantId = evidence.tenantId,
                evidenceType = AuditEvidenceType.FAILURE,
                planId = evidence.planId,
                tombstoneId = evidence.tombstoneId,
                resourceType = evidence.resourceType,
                resourceId = evidence.resourceId,
                resourceRevision = evidence.resourceRevision,
                requestedBy = plan.requestedBy,
                occurredAt = evidence.failedAt,
                decisionReason = null,
                policyRevision = null,
                legalHoldRevision = null,
                authorizationRevision = null,
                activeLegalHoldIds = emptyList(),
                failedStage = evidence.failedStage,
                failureCount = evidence.failureCount,
                message = evidence.message,
            ),
        )
    }

    private fun insertDecisionAudit(evidence: DeletionAuditEvidence): Boolean =
        insertAudit(decisionRecord(evidence))

    private fun decisionRecord(evidence: DeletionAuditEvidence): AuditRecord = AuditRecord(
        id = evidence.id,
        tenantId = evidence.tenantId,
        evidenceType = AuditEvidenceType.DECISION,
        planId = evidence.planId,
        tombstoneId = evidence.tombstoneId,
        resourceType = evidence.resourceType,
        resourceId = evidence.resourceId,
        resourceRevision = evidence.resourceRevision,
        requestedBy = evidence.requestedBy,
        occurredAt = evidence.decidedAt,
        decisionReason = evidence.reason,
        policyRevision = evidence.policyRevision,
        legalHoldRevision = evidence.legalHoldRevision,
        authorizationRevision = evidence.authorizationRevision,
        activeLegalHoldIds = evidence.activeLegalHoldIds,
        failedStage = null,
        failureCount = null,
        message = null,
    )

    private fun insertAudit(record: AuditRecord): Boolean {
        validateAuditRecordBoundary(record)
        val dialect = JdbcConnectionContext.requireDialect()
        val createToken = UUID.randomUUID().toString()
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_secure_deletion_audit(
                id, tenant_id, create_token, evidence_type, plan_id, tombstone_id,
                resource_type, resource_id, resource_revision, requested_by, occurred_time,
                decision_reason, policy_revision, legal_hold_revision, authorization_revision,
                active_legal_hold_ids_json, failed_stage, failure_count, message, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ${dialect.jsonParameterBinding()}, ?, ?, ?, ?, ?)
            ${dialect.upsertClause(listOf("tenant_id", "id"), emptyList())}
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            statement.setString(index++, record.id.value)
            statement.setString(index++, record.tenantId.value)
            statement.setString(index++, createToken)
            statement.setString(index++, record.evidenceType.name)
            statement.setNullableString(index++, record.planId?.value)
            statement.setNullableString(index++, record.tombstoneId?.value)
            statement.setString(index++, record.resourceType)
            statement.setString(index++, record.resourceId.value)
            statement.setLong(index++, record.resourceRevision)
            statement.setString(index++, record.requestedBy.value)
            statement.setLong(index++, record.occurredAt)
            statement.setNullableString(index++, record.decisionReason?.name)
            statement.setNullableString(index++, record.policyRevision)
            statement.setNullableString(index++, record.legalHoldRevision)
            statement.setNullableString(index++, record.authorizationRevision)
            statement.setString(index++, jsonCodec.encodeIdentifierList(record.activeLegalHoldIds))
            statement.setNullableString(index++, record.failedStage?.name)
            statement.setNullableInt(index++, record.failureCount)
            statement.setNullableString(index++, record.message)
            statement.setLong(index++, record.occurredAt)
            statement.setLong(index, record.occurredAt)
            statement.executeUpdate()
        }
        val stored = findAudit(record.tenantId, record.id, lock = true)
            ?: throw SecureDeletionPersistenceException(
                "Secure-deletion audit insert conflicted outside the requested tenant.",
            )
        ensureAuditMatches(stored, record)
        return stored.createToken == createToken
    }

    private fun insertTombstone(tombstone: DeletionTombstone) {
        validateTombstoneBoundary(tombstone)
        JdbcConnectionContext.requireCurrent().prepareStatement(INSERT_TOMBSTONE_SQL).use { statement ->
            statement.setString(1, tombstone.id.value)
            statement.setString(2, tombstone.tenantId.value)
            statement.setString(3, tombstone.planId.value)
            statement.setString(4, tombstone.resourceType)
            statement.setString(5, tombstone.resourceId.value)
            statement.setLong(6, tombstone.resourceRevision)
            statement.setLong(7, tombstone.blockedAt)
            statement.setString(8, tombstone.policyRevision)
            statement.setString(9, tombstone.legalHoldRevision)
            statement.setString(10, tombstone.authorizationRevision)
            statement.setLong(11, tombstone.blockedAt)
            statement.setLong(12, tombstone.blockedAt)
            statement.executeUpdate()
        }
    }

    private fun insertReceipt(execution: SecureDeletionExecution, receipt: StoredSecureDeletionReceipt) {
        validateReceiptBoundary(receipt)
        val dialect = JdbcConnectionContext.requireDialect()
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_secure_deletion_receipt(
                tenant_id, plan_id, deletion_stage, idempotency_key, provider_id, provider_target,
                provider_status, request_binding_digest, receipt_reference, message, evidence_json,
                recorded_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ${dialect.jsonParameterBinding()}, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            bindReceipt(statement, execution, receipt, 1)
            statement.setLong(12, receipt.recordedAt)
            statement.setLong(13, receipt.recordedAt)
            statement.setLong(14, receipt.recordedAt)
            statement.executeUpdate()
        }
    }

    private fun updateReceiptIfChanged(
        execution: SecureDeletionExecution,
        persisted: PersistedReceiptRow,
        incoming: StoredSecureDeletionReceipt,
    ) {
        validateReceiptBoundary(incoming)
        ensureReceiptIdentity(persisted.receipt, incoming)
        if (receiptEquals(persisted.receipt, incoming)) return
        validateReceiptTransition(persisted.receipt, incoming)
        val dialect = JdbcConnectionContext.requireDialect()
        val updated = JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            UPDATE fw_secure_deletion_receipt
               SET provider_status = ?, receipt_reference = ?, message = ?,
                   evidence_json = ${dialect.jsonParameterBinding()}, recorded_time = ?, updated_time = ?
             WHERE tenant_id = ? AND plan_id = ? AND deletion_stage = ?
               AND idempotency_key = ? AND provider_id = ? AND provider_target = ?
               AND request_binding_digest = ? AND provider_status = ? AND recorded_time = ?
            """.trimIndent(),
        ).use { statement ->
            val provider = incoming.providerReceipt
            statement.setString(1, provider.status.name)
            statement.setNullableString(2, provider.receiptReference)
            statement.setNullableString(3, provider.message)
            statement.setString(4, jsonCodec.encodeEvidence(provider.evidence))
            statement.setLong(5, incoming.recordedAt)
            statement.setLong(6, incoming.recordedAt)
            statement.setString(7, execution.tenantId.value)
            statement.setString(8, execution.planId.value)
            statement.setString(9, incoming.stage.name)
            statement.setString(10, incoming.idempotencyKey)
            statement.setString(11, provider.providerId)
            statement.setString(12, provider.target.name)
            statement.setString(13, provider.requestBindingDigest)
            statement.setString(14, persisted.receipt.providerReceipt.status.name)
            statement.setLong(15, persisted.receipt.recordedAt)
            statement.executeUpdate()
        }
        if (updated != 1) {
            throw SecureDeletionPersistenceException(
                "Secure-deletion provider receipt changed before reconciliation persistence.",
            )
        }
    }

    private fun bindReceipt(
        statement: PreparedStatement,
        execution: SecureDeletionExecution,
        receipt: StoredSecureDeletionReceipt,
        start: Int,
    ) {
        val provider = receipt.providerReceipt
        statement.setString(start, execution.tenantId.value)
        statement.setString(start + 1, execution.planId.value)
        statement.setString(start + 2, receipt.stage.name)
        statement.setString(start + 3, receipt.idempotencyKey)
        statement.setString(start + 4, provider.providerId)
        statement.setString(start + 5, provider.target.name)
        statement.setString(start + 6, provider.status.name)
        statement.setString(start + 7, provider.requestBindingDigest)
        statement.setNullableString(start + 8, provider.receiptReference)
        statement.setNullableString(start + 9, provider.message)
        statement.setString(start + 10, jsonCodec.encodeEvidence(provider.evidence))
    }

    private fun requireOutboxFence(execution: SecureDeletionExecution) {
        val row = JdbcConnectionContext.requireCurrent().prepareStatement(LOCK_OUTBOX_SQL).use { statement ->
            statement.setString(1, execution.tenantId.value)
            statement.setString(2, execution.dispatchEventId.value)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else OutboxFenceRow(
                    tenantId = Identifier(rows.requiredString("tenant_id")),
                    id = Identifier(rows.requiredString("id")),
                    eventType = rows.requiredString("event_type"),
                    status = persistedEnum<OutboxEventStatus>(rows.requiredString("event_status"), "Outbox status"),
                    leaseOwner = rows.getString("lease_owner"),
                    leaseToken = rows.getString("lease_token"),
                )
            }
        } ?: throw SecureDeletionPersistenceException(
            "Secure-deletion execution has no tenant-bound Outbox dispatch fence.",
        )
        if (row.tenantId != execution.tenantId || row.id != execution.dispatchEventId ||
            row.eventType != SecureDeletionApplicationService.SECURE_DELETION_REQUESTED_EVENT_TYPE
        ) {
            throw SecureDeletionPersistenceException("Secure-deletion Outbox dispatch identity is invalid.")
        }
        val runningWithLease = row.status == OutboxEventStatus.RUNNING &&
            !row.leaseOwner.isNullOrBlank() && !row.leaseToken.isNullOrBlank()
        val exhausted = row.status == OutboxEventStatus.FAILED && row.leaseOwner == null && row.leaseToken == null
        if (!runningWithLease && !(execution.status == SecureDeletionExecutionStatus.FAILED && exhausted)) {
            throw SecureDeletionPersistenceException(
                "Secure-deletion mutation is not protected by a current leased or exhausted Outbox fence.",
            )
        }
    }

    private fun findPlanRow(tenantId: Identifier, planId: Identifier, lock: Boolean): PlanRow? {
        val suffix = if (lock) " ${JdbcConnectionContext.requireDialect().forUpdate()}" else ""
        return JdbcConnectionContext.requireCurrent().prepareStatement(
            "$SELECT_PLAN_SQL$suffix",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, planId.value)
            statement.executeQuery().use { rows -> if (rows.next()) mapPlan(rows) else null }
        }
    }

    private fun findTombstone(tenantId: Identifier, tombstoneId: Identifier, lock: Boolean): TombstoneRow? {
        val suffix = if (lock) " ${JdbcConnectionContext.requireDialect().forUpdate()}" else ""
        return JdbcConnectionContext.requireCurrent().prepareStatement(
            "$SELECT_TOMBSTONE_SQL$suffix",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, tombstoneId.value)
            statement.executeQuery().use { rows -> if (rows.next()) mapTombstone(rows) else null }
        }
    }

    private fun findAudit(tenantId: Identifier, evidenceId: Identifier, lock: Boolean): AuditRow? {
        val suffix = if (lock) " ${JdbcConnectionContext.requireDialect().forUpdate()}" else ""
        return JdbcConnectionContext.requireCurrent().prepareStatement(
            "$SELECT_AUDIT_SQL$suffix",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, evidenceId.value)
            statement.executeQuery().use { rows -> if (rows.next()) mapAudit(rows) else null }
        }
    }

    private fun findReceiptRows(tenantId: Identifier, planId: Identifier): List<PersistedReceiptRow> =
        JdbcConnectionContext.requireCurrent().prepareStatement(SELECT_RECEIPTS_SQL).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, planId.value)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(mapReceipt(rows))
                }
            }
        }

    private fun mapPlan(rows: ResultSet): PlanRow = persisted("secure-deletion plan") {
        PlanRow(
            id = Identifier(rows.requiredString("id")),
            tenantId = Identifier(rows.requiredString("tenant_id")),
            createToken = rows.requiredString("create_token"),
            dispatchEventId = Identifier(rows.requiredString("dispatch_event_id")),
            tombstoneId = Identifier(rows.requiredString("tombstone_id")),
            decisionEvidenceId = Identifier(rows.requiredString("decision_evidence_id")),
            resourceType = rows.requiredString("resource_type"),
            resourceId = Identifier(rows.requiredString("resource_id")),
            resourceRevision = rows.getLong("resource_revision"),
            requestedBy = Identifier(rows.requiredString("requested_by")),
            policyRevision = rows.requiredString("policy_revision"),
            legalHoldRevision = rows.requiredString("legal_hold_revision"),
            authorizationRevision = rows.requiredString("authorization_revision"),
            indexIdempotencyKey = rows.requiredString("index_idempotency_key"),
            objectIdempotencyKey = rows.requiredString("object_idempotency_key"),
            currentStage = persistedEnum(rows.requiredString("current_stage"), "secure-deletion stage"),
            status = persistedEnum(rows.requiredString("execution_status"), "secure-deletion status"),
            failureCount = rows.getInt("failure_count"),
            lastError = rows.getString("last_error"),
            createdAt = rows.getLong("created_time"),
            updatedAt = rows.getLong("updated_time"),
        )
    }

    private fun mapTombstone(rows: ResultSet): TombstoneRow = persisted("secure-deletion tombstone") {
        TombstoneRow(
            id = Identifier(rows.requiredString("id")),
            tenantId = Identifier(rows.requiredString("tenant_id")),
            planId = Identifier(rows.requiredString("plan_id")),
            resourceType = rows.requiredString("resource_type"),
            resourceId = Identifier(rows.requiredString("resource_id")),
            resourceRevision = rows.getLong("resource_revision"),
            blockedAt = rows.getLong("blocked_time"),
            policyRevision = rows.requiredString("policy_revision"),
            legalHoldRevision = rows.requiredString("legal_hold_revision"),
            authorizationRevision = rows.requiredString("authorization_revision"),
            createdAt = rows.getLong("created_time"),
            updatedAt = rows.getLong("updated_time"),
        )
    }

    private fun mapAudit(rows: ResultSet): AuditRow = persisted("secure-deletion audit") {
        AuditRow(
            id = Identifier(rows.requiredString("id")),
            tenantId = Identifier(rows.requiredString("tenant_id")),
            createToken = rows.requiredString("create_token"),
            evidenceType = persistedEnum(rows.requiredString("evidence_type"), "secure-deletion evidence type"),
            planId = rows.getString("plan_id")?.let(::Identifier),
            tombstoneId = rows.getString("tombstone_id")?.let(::Identifier),
            resourceType = rows.requiredString("resource_type"),
            resourceId = Identifier(rows.requiredString("resource_id")),
            resourceRevision = rows.getLong("resource_revision"),
            requestedBy = Identifier(rows.requiredString("requested_by")),
            occurredAt = rows.getLong("occurred_time"),
            decisionReason = rows.getString("decision_reason")?.let { value ->
                persistedEnum<SecureDeletionDecisionReason>(value, "secure-deletion decision reason")
            },
            policyRevision = rows.getString("policy_revision"),
            legalHoldRevision = rows.getString("legal_hold_revision"),
            authorizationRevision = rows.getString("authorization_revision"),
            activeLegalHoldIds = jsonCodec.decodeIdentifierList(rows.requiredString("active_legal_hold_ids_json")),
            failedStage = rows.getString("failed_stage")?.let { value ->
                persistedEnum<SecureDeletionStage>(value, "secure-deletion failed stage")
            },
            failureCount = rows.nullableInt("failure_count"),
            message = rows.getString("message"),
            createdAt = rows.getLong("created_time"),
            updatedAt = rows.getLong("updated_time"),
        )
    }

    private fun mapReceipt(rows: ResultSet): PersistedReceiptRow = persisted("secure-deletion provider receipt") {
        val stage = persistedEnum<SecureDeletionStage>(
            rows.requiredString("deletion_stage"),
            "secure-deletion receipt stage",
        )
        val receipt = StoredSecureDeletionReceipt(
            stage = stage,
            idempotencyKey = rows.requiredString("idempotency_key"),
            providerReceipt = SecureDeletionProviderReceipt(
                providerId = rows.requiredString("provider_id"),
                target = persistedEnum(rows.requiredString("provider_target"), "secure-deletion provider target"),
                status = persistedEnum(rows.requiredString("provider_status"), "secure-deletion provider status"),
                requestBindingDigest = rows.requiredString("request_binding_digest"),
                receiptReference = rows.getString("receipt_reference"),
                message = rows.getString("message"),
                evidence = jsonCodec.decodeEvidence(rows.requiredString("evidence_json")),
            ),
            recordedAt = rows.getLong("recorded_time"),
        )
        PersistedReceiptRow(
            tenantId = Identifier(rows.requiredString("tenant_id")),
            planId = Identifier(rows.requiredString("plan_id")),
            receipt = receipt,
            createdAt = rows.getLong("created_time"),
            updatedAt = rows.getLong("updated_time"),
        )
    }

    private fun bindPlanInsert(
        statement: PreparedStatement,
        plan: SecureDeletionPlan,
        execution: SecureDeletionExecution,
        createToken: String,
    ) {
        statement.setString(1, plan.id.value)
        statement.setString(2, plan.tenantId.value)
        statement.setString(3, createToken)
        statement.setString(4, execution.dispatchEventId.value)
        statement.setString(5, plan.tombstone.id.value)
        statement.setString(6, plan.decisionAuditEvidence.id.value)
        statement.setString(7, plan.resourceType)
        statement.setString(8, plan.resourceId.value)
        statement.setLong(9, plan.resourceRevision)
        statement.setString(10, plan.requestedBy.value)
        statement.setString(11, plan.tombstone.policyRevision)
        statement.setString(12, plan.tombstone.legalHoldRevision)
        statement.setString(13, plan.tombstone.authorizationRevision)
        statement.setString(14, execution.indexIdempotencyKey)
        statement.setString(15, execution.objectIdempotencyKey)
        statement.setString(16, execution.currentStage.name)
        statement.setString(17, execution.status.name)
        statement.setInt(18, execution.failureCount)
        statement.setNullableString(19, execution.lastError)
        statement.setLong(20, execution.createdAt)
        statement.setLong(21, execution.updatedAt)
    }

    private fun ensurePlanMatches(row: PlanRow, plan: SecureDeletionPlan, eventId: Identifier) {
        val pending = SecureDeletionExecution.pending(plan, eventId)
        if (row.id != plan.id || row.tenantId != plan.tenantId || row.dispatchEventId != eventId ||
            row.tombstoneId != plan.tombstone.id || row.decisionEvidenceId != plan.decisionAuditEvidence.id ||
            row.resourceType != plan.resourceType || row.resourceId != plan.resourceId ||
            row.resourceRevision != plan.resourceRevision || row.requestedBy != plan.requestedBy ||
            row.policyRevision != plan.tombstone.policyRevision ||
            row.legalHoldRevision != plan.tombstone.legalHoldRevision ||
            row.authorizationRevision != plan.tombstone.authorizationRevision ||
            row.indexIdempotencyKey != pending.indexIdempotencyKey ||
            row.objectIdempotencyKey != pending.objectIdempotencyKey ||
            row.createdAt != plan.createdAt
        ) {
            throw SecureDeletionPersistenceException(
                "Secure-deletion plan identifier was replayed with conflicting tenant-bound evidence.",
            )
        }
    }

    private fun ensureTombstoneMatches(row: TombstoneRow, tombstone: DeletionTombstone) {
        if (row.id != tombstone.id || row.tenantId != tombstone.tenantId || row.planId != tombstone.planId ||
            row.resourceType != tombstone.resourceType || row.resourceId != tombstone.resourceId ||
            row.resourceRevision != tombstone.resourceRevision || row.blockedAt != tombstone.blockedAt ||
            row.policyRevision != tombstone.policyRevision || row.legalHoldRevision != tombstone.legalHoldRevision ||
            row.authorizationRevision != tombstone.authorizationRevision ||
            row.createdAt != tombstone.blockedAt || row.updatedAt != tombstone.blockedAt
        ) {
            throw SecureDeletionPersistenceException("Secure-deletion tombstone replay conflicts with durable evidence.")
        }
    }

    private fun ensureAuditMatches(row: AuditRow, record: AuditRecord) {
        if (row.id != record.id || row.tenantId != record.tenantId || row.evidenceType != record.evidenceType ||
            row.planId != record.planId || row.tombstoneId != record.tombstoneId ||
            row.resourceType != record.resourceType || row.resourceId != record.resourceId ||
            row.resourceRevision != record.resourceRevision || row.requestedBy != record.requestedBy ||
            row.occurredAt != record.occurredAt || row.decisionReason != record.decisionReason ||
            row.policyRevision != record.policyRevision || row.legalHoldRevision != record.legalHoldRevision ||
            row.authorizationRevision != record.authorizationRevision ||
            row.activeLegalHoldIds != record.activeLegalHoldIds || row.failedStage != record.failedStage ||
            row.failureCount != record.failureCount || row.message != record.message ||
            row.createdAt != record.occurredAt || row.updatedAt != record.occurredAt
        ) {
            throw SecureDeletionPersistenceException(
                "Secure-deletion audit identifier was replayed with conflicting evidence.",
            )
        }
    }

    private fun ensureExecutionIdentity(row: PlanRow, execution: SecureDeletionExecution) {
        if (row.id != execution.planId || row.tenantId != execution.tenantId ||
            row.dispatchEventId != execution.dispatchEventId || row.tombstoneId != execution.tombstoneId ||
            row.decisionEvidenceId != execution.decisionEvidenceId || row.resourceType != execution.resourceType ||
            row.resourceId != execution.resourceId || row.resourceRevision != execution.resourceRevision ||
            row.requestedBy != execution.requestedBy || row.indexIdempotencyKey != execution.indexIdempotencyKey ||
            row.objectIdempotencyKey != execution.objectIdempotencyKey || row.createdAt != execution.createdAt
        ) {
            throw SecureDeletionPersistenceException(
                "Secure-deletion execution identity or resource revision does not match durable state.",
            )
        }
    }

    private fun validateExecutionTransition(
        row: PlanRow,
        persistedReceipts: List<PersistedReceiptRow>,
        execution: SecureDeletionExecution,
    ) {
        if (execution.updatedAt < row.updatedAt || execution.failureCount < row.failureCount ||
            execution.failureCount > row.failureCount + 1
        ) {
            throw SecureDeletionPersistenceException("Secure-deletion counters or time moved outside the CAS boundary.")
        }
        if (row.status == SecureDeletionExecutionStatus.SUCCEEDED || row.status == SecureDeletionExecutionStatus.FAILED) {
            if (!projectionEquals(row, execution) ||
                persistedReceipts.map(PersistedReceiptRow::receipt).size != execution.receipts.size ||
                !persistedReceipts.all { current ->
                    execution.receipts.any { incoming -> receiptEquals(current.receipt, incoming) }
                }
            ) {
                throw SecureDeletionPersistenceException("Terminal secure-deletion execution is immutable.")
            }
            return
        }
        val oldStageIndex = PROGRESS_STAGES.indexOf(row.currentStage)
        val newStageIndex = PROGRESS_STAGES.indexOf(execution.currentStage)
        val completesObjectStage = row.currentStage == SecureDeletionStage.PURGE_OBJECT_STORAGE &&
            execution.currentStage == SecureDeletionStage.APPEND_COMPLETION_AUDIT &&
            execution.status == SecureDeletionExecutionStatus.SUCCEEDED
        if (oldStageIndex < 0 || newStageIndex < oldStageIndex ||
            (newStageIndex - oldStageIndex > 1 && !completesObjectStage)
        ) {
            throw SecureDeletionPersistenceException("Secure-deletion stage moved outside its durable progression.")
        }
        val receipts = execution.receipts.associateBy { it.stage }
        if (newStageIndex > 0 &&
            receipts[SecureDeletionStage.PURGE_INDEX_PROJECTIONS]?.providerReceipt?.isVerifiedAbsent() != true
        ) {
            throw SecureDeletionPersistenceException("Secure-deletion index stage advanced without verified absence.")
        }
        if (newStageIndex > 1 &&
            receipts[SecureDeletionStage.PURGE_OBJECT_STORAGE]?.providerReceipt?.isVerifiedAbsent() != true
        ) {
            throw SecureDeletionPersistenceException("Secure-deletion object stage advanced without verified absence.")
        }
        when (execution.status) {
            SecureDeletionExecutionStatus.PENDING -> {
                if (execution.lastError != null) {
                    throw SecureDeletionPersistenceException("Pending secure deletion cannot retain an error.")
                }
                if (execution.currentStage == SecureDeletionStage.PURGE_INDEX_PROJECTIONS ||
                    execution.currentStage == SecureDeletionStage.PURGE_OBJECT_STORAGE
                ) {
                    if (receipts[execution.currentStage] != null) {
                        throw SecureDeletionPersistenceException(
                            "Pending secure deletion must advance immediately after provider evidence.",
                        )
                    }
                }
            }
            SecureDeletionExecutionStatus.RECONCILING -> {
                val currentReceipt = receipts[execution.currentStage]
                if (currentReceipt?.providerReceipt?.status != SecureDeletionProviderStatus.ACCEPTED_UNVERIFIED) {
                    throw SecureDeletionPersistenceException(
                        "Reconciling secure deletion requires an accepted unverified receipt.",
                    )
                }
            }
            SecureDeletionExecutionStatus.RETRY -> if (execution.failureCount != row.failureCount + 1 ||
                execution.lastError.isNullOrBlank()
            ) {
                throw SecureDeletionPersistenceException("Retryable secure deletion requires one durable failure.")
            }
            SecureDeletionExecutionStatus.FAILED -> if (execution.failureCount != row.failureCount + 1 ||
                execution.lastError.isNullOrBlank()
            ) {
                throw SecureDeletionPersistenceException("Failed secure deletion requires one durable failure.")
            }
            SecureDeletionExecutionStatus.SUCCEEDED -> if (
                execution.currentStage != SecureDeletionStage.APPEND_COMPLETION_AUDIT ||
                execution.lastError != null || execution.failureCount != row.failureCount
            ) {
                throw SecureDeletionPersistenceException("Successful secure deletion has invalid terminal state.")
            }
        }
    }

    private fun projectionEquals(row: PlanRow, execution: SecureDeletionExecution): Boolean =
        row.currentStage == execution.currentStage && row.status == execution.status &&
            row.failureCount == execution.failureCount && row.lastError == execution.lastError &&
            row.updatedAt == execution.updatedAt

    private fun ensureReceiptIdentity(current: StoredSecureDeletionReceipt, incoming: StoredSecureDeletionReceipt) {
        if (current.stage != incoming.stage || current.idempotencyKey != incoming.idempotencyKey ||
            current.providerReceipt.providerId != incoming.providerReceipt.providerId ||
            current.providerReceipt.target != incoming.providerReceipt.target ||
            current.providerReceipt.requestBindingDigest != incoming.providerReceipt.requestBindingDigest
        ) {
            throw SecureDeletionPersistenceException(
                "Secure-deletion provider identity changed after durable evidence was written.",
            )
        }
    }

    private fun validateReceiptTransition(current: StoredSecureDeletionReceipt, incoming: StoredSecureDeletionReceipt) {
        if (incoming.recordedAt < current.recordedAt) {
            throw SecureDeletionPersistenceException("Secure-deletion receipt time moved backwards.")
        }
        if (current.providerReceipt.status == SecureDeletionProviderStatus.VERIFIED_ABSENT ||
            current.providerReceipt.status == SecureDeletionProviderStatus.PERMANENT_FAILURE
        ) {
            throw SecureDeletionPersistenceException("Terminal secure-deletion provider evidence is immutable.")
        }
        if (current.providerReceipt.status == SecureDeletionProviderStatus.ACCEPTED_UNVERIFIED &&
            incoming.providerReceipt.status == SecureDeletionProviderStatus.RETRYABLE_FAILURE
        ) {
            throw SecureDeletionPersistenceException(
                "Accepted deletion evidence must remain available while reconciliation is retried.",
            )
        }
    }

    private fun ensureCompletionReceiptsMatch(
        persisted: List<StoredSecureDeletionReceipt>,
        evidence: List<StoredSecureDeletionReceipt>,
    ) {
        if (persisted.size != SecureDeletionTarget.values().size || evidence.size != persisted.size ||
            persisted.any { stored ->
                !stored.providerReceipt.isVerifiedAbsent() || evidence.none { receiptEquals(stored, it) }
            }
        ) {
            throw SecureDeletionPersistenceException(
                "Secure-deletion completion receipts do not match verified durable provider evidence.",
            )
        }
    }

    private fun receiptEquals(left: StoredSecureDeletionReceipt, right: StoredSecureDeletionReceipt): Boolean =
        left.stage == right.stage && left.idempotencyKey == right.idempotencyKey &&
            left.recordedAt == right.recordedAt &&
            left.providerReceipt.providerId == right.providerReceipt.providerId &&
            left.providerReceipt.target == right.providerReceipt.target &&
            left.providerReceipt.status == right.providerReceipt.status &&
            left.providerReceipt.requestBindingDigest == right.providerReceipt.requestBindingDigest &&
            left.providerReceipt.receiptReference == right.providerReceipt.receiptReference &&
            left.providerReceipt.message == right.providerReceipt.message &&
            left.providerReceipt.evidence == right.providerReceipt.evidence

    private fun ensureTerminalIdentity(
        plan: PlanRow,
        resourceType: String,
        resourceId: Identifier,
        resourceRevision: Long,
        tombstoneId: Identifier,
    ) {
        if (plan.resourceType != resourceType || plan.resourceId != resourceId ||
            plan.resourceRevision != resourceRevision || plan.tombstoneId != tombstoneId
        ) {
            throw SecureDeletionPersistenceException("Secure-deletion terminal evidence crossed its revision fence.")
        }
    }

    private fun validatePlanBoundary(plan: SecureDeletionPlan, dispatchEventId: Identifier) {
        validateIdentifier(plan.id, "plan id")
        validateIdentifier(plan.tenantId, "tenant id")
        validateIdentifier(dispatchEventId, "dispatch event id")
        validateIdentifier(plan.resourceId, "resource id")
        validateIdentifier(plan.requestedBy, "requester id")
        validateText(plan.resourceType, "resource type", MAX_RESOURCE_TYPE_LENGTH)
        validateTombstoneBoundary(plan.tombstone)
        validateDecisionEvidenceBoundary(plan.decisionAuditEvidence)
    }

    private fun validateExecutionBoundary(execution: SecureDeletionExecution) {
        validateIdentifier(execution.planId, "plan id")
        validateIdentifier(execution.tenantId, "tenant id")
        validateIdentifier(execution.dispatchEventId, "dispatch event id")
        validateIdentifier(execution.tombstoneId, "tombstone id")
        validateIdentifier(execution.decisionEvidenceId, "decision evidence id")
        validateIdentifier(execution.resourceId, "resource id")
        validateIdentifier(execution.requestedBy, "requester id")
        validateText(execution.resourceType, "resource type", MAX_RESOURCE_TYPE_LENGTH)
        validateText(execution.indexIdempotencyKey, "index idempotency key", MAX_IDEMPOTENCY_LENGTH)
        validateText(execution.objectIdempotencyKey, "object idempotency key", MAX_IDEMPOTENCY_LENGTH)
        execution.lastError?.let { validateText(it, "last error", MAX_MESSAGE_LENGTH) }
        execution.receipts.forEach(::validateReceiptBoundary)
    }

    private fun validateTombstoneBoundary(tombstone: DeletionTombstone) {
        validateIdentifier(tombstone.id, "tombstone id")
        validateIdentifier(tombstone.planId, "plan id")
        validateIdentifier(tombstone.tenantId, "tenant id")
        validateIdentifier(tombstone.resourceId, "resource id")
        validateText(tombstone.resourceType, "resource type", MAX_RESOURCE_TYPE_LENGTH)
        validateText(tombstone.policyRevision, "policy revision", MAX_REVISION_LENGTH)
        validateText(tombstone.legalHoldRevision, "legal-hold revision", MAX_REVISION_LENGTH)
        validateText(tombstone.authorizationRevision, "authorization revision", MAX_REVISION_LENGTH)
    }

    private fun validateDecisionEvidenceBoundary(evidence: DeletionAuditEvidence) {
        validateIdentifier(evidence.id, "audit evidence id")
        validateIdentifier(evidence.tenantId, "tenant id")
        validateIdentifier(evidence.resourceId, "resource id")
        validateIdentifier(evidence.requestedBy, "requester id")
        evidence.planId?.let { validateIdentifier(it, "plan id") }
        evidence.tombstoneId?.let { validateIdentifier(it, "tombstone id") }
        validateText(evidence.resourceType, "resource type", MAX_RESOURCE_TYPE_LENGTH)
        validateText(evidence.policyRevision, "policy revision", MAX_REVISION_LENGTH)
        validateText(evidence.legalHoldRevision, "legal-hold revision", MAX_REVISION_LENGTH)
        validateText(evidence.authorizationRevision, "authorization revision", MAX_REVISION_LENGTH)
        evidence.activeLegalHoldIds.forEach { validateIdentifier(it, "active legal-hold id") }
    }

    private fun validateReceiptBoundary(receipt: StoredSecureDeletionReceipt) {
        validateText(receipt.idempotencyKey, "idempotency key", MAX_IDEMPOTENCY_LENGTH)
        validateText(receipt.providerReceipt.providerId, "provider id", MAX_PROVIDER_ID_LENGTH)
        validateBindingDigest(receipt.providerReceipt.requestBindingDigest)
        receipt.providerReceipt.receiptReference?.let {
            validateText(it, "receipt reference", MAX_RECEIPT_REFERENCE_LENGTH)
        }
        receipt.providerReceipt.message?.let { validateText(it, "provider message", MAX_MESSAGE_LENGTH) }
        jsonCodec.encodeEvidence(receipt.providerReceipt.evidence)
    }

    private fun validateCompletionBoundary(evidence: SecureDeletionCompletionEvidence) {
        validateIdentifier(evidence.id, "completion evidence id")
        validateIdentifier(evidence.tenantId, "tenant id")
        validateIdentifier(evidence.planId, "plan id")
        validateIdentifier(evidence.tombstoneId, "tombstone id")
        validateIdentifier(evidence.resourceId, "resource id")
        validateText(evidence.resourceType, "resource type", MAX_RESOURCE_TYPE_LENGTH)
        evidence.receipts.forEach(::validateReceiptBoundary)
    }

    private fun validateFailureBoundary(evidence: SecureDeletionFailureEvidence) {
        validateIdentifier(evidence.id, "failure evidence id")
        validateIdentifier(evidence.tenantId, "tenant id")
        validateIdentifier(evidence.planId, "plan id")
        validateIdentifier(evidence.tombstoneId, "tombstone id")
        validateIdentifier(evidence.resourceId, "resource id")
        validateText(evidence.resourceType, "resource type", MAX_RESOURCE_TYPE_LENGTH)
        validateText(evidence.message, "failure message", MAX_MESSAGE_LENGTH)
    }

    private fun validateAuditRecordBoundary(record: AuditRecord) {
        validateIdentifier(record.id, "audit evidence id")
        validateIdentifier(record.tenantId, "tenant id")
        validateIdentifier(record.resourceId, "resource id")
        validateIdentifier(record.requestedBy, "requester id")
        record.planId?.let { validateIdentifier(it, "plan id") }
        record.tombstoneId?.let { validateIdentifier(it, "tombstone id") }
        validateText(record.resourceType, "resource type", MAX_RESOURCE_TYPE_LENGTH)
        record.policyRevision?.let { validateText(it, "policy revision", MAX_REVISION_LENGTH) }
        record.legalHoldRevision?.let { validateText(it, "legal-hold revision", MAX_REVISION_LENGTH) }
        record.authorizationRevision?.let { validateText(it, "authorization revision", MAX_REVISION_LENGTH) }
        record.message?.let { validateText(it, "audit message", MAX_MESSAGE_LENGTH) }
        jsonCodec.encodeIdentifierList(record.activeLegalHoldIds)
    }

    private fun validateIdentifier(identifier: Identifier, field: String) =
        validateText(identifier.value, field, MAX_IDENTIFIER_LENGTH)

    private fun validateBindingDigest(value: String) {
        if (value.length != 64 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw SecureDeletionPersistenceException(
                "Secure-deletion request binding digest is outside the persistence boundary.",
            )
        }
    }

    private fun validateText(value: String, field: String, maximumLength: Int) {
        if (value.isBlank() || value.length > maximumLength || value.any(::isUnsafeControl)) {
            throw SecureDeletionPersistenceException("Secure-deletion $field is outside the persistence boundary.")
        }
    }

    private fun isUnsafeControl(character: Char): Boolean = character < ' ' || character == '\u007f'

    private inline fun <T> persisted(description: String, action: () -> T): T = try {
        action()
    } catch (failure: SecureDeletionPersistenceException) {
        throw failure
    } catch (failure: Exception) {
        throw SecureDeletionPersistenceException("Persisted $description is invalid.", failure)
    }

    private inline fun <reified T : Enum<T>> persistedEnum(value: String, description: String): T = try {
        enumValueOf<T>(value)
    } catch (failure: IllegalArgumentException) {
        throw SecureDeletionPersistenceException("Persisted $description is unknown.", failure)
    }

    private fun ResultSet.requiredString(column: String): String =
        getString(column) ?: throw SecureDeletionPersistenceException("Persisted secure-deletion $column is null.")

    private fun ResultSet.nullableInt(column: String): Int? = getInt(column).let { value ->
        if (wasNull()) null else value
    }

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun PreparedStatement.setNullableInt(index: Int, value: Int?) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }

    private data class PlanRow(
        val id: Identifier,
        val tenantId: Identifier,
        val createToken: String,
        val dispatchEventId: Identifier,
        val tombstoneId: Identifier,
        val decisionEvidenceId: Identifier,
        val resourceType: String,
        val resourceId: Identifier,
        val resourceRevision: Long,
        val requestedBy: Identifier,
        val policyRevision: String,
        val legalHoldRevision: String,
        val authorizationRevision: String,
        val indexIdempotencyKey: String,
        val objectIdempotencyKey: String,
        val currentStage: SecureDeletionStage,
        val status: SecureDeletionExecutionStatus,
        val failureCount: Int,
        val lastError: String?,
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        fun toExecution(receipts: List<StoredSecureDeletionReceipt>): SecureDeletionExecution = try {
            SecureDeletionExecution(
                planId = id,
                tenantId = tenantId,
                dispatchEventId = dispatchEventId,
                tombstoneId = tombstoneId,
                decisionEvidenceId = decisionEvidenceId,
                resourceType = resourceType,
                resourceId = resourceId,
                resourceRevision = resourceRevision,
                requestedBy = requestedBy,
                indexIdempotencyKey = indexIdempotencyKey,
                objectIdempotencyKey = objectIdempotencyKey,
                currentStage = currentStage,
                status = status,
                receipts = receipts,
                failureCount = failureCount,
                lastError = lastError,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        } catch (failure: Exception) {
            throw SecureDeletionPersistenceException("Persisted secure-deletion execution is invalid.", failure)
        }
    }

    private data class TombstoneRow(
        val id: Identifier,
        val tenantId: Identifier,
        val planId: Identifier,
        val resourceType: String,
        val resourceId: Identifier,
        val resourceRevision: Long,
        val blockedAt: Long,
        val policyRevision: String,
        val legalHoldRevision: String,
        val authorizationRevision: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private enum class AuditEvidenceType { DECISION, COMPLETION, FAILURE }

    private data class AuditRecord(
        val id: Identifier,
        val tenantId: Identifier,
        val evidenceType: AuditEvidenceType,
        val planId: Identifier?,
        val tombstoneId: Identifier?,
        val resourceType: String,
        val resourceId: Identifier,
        val resourceRevision: Long,
        val requestedBy: Identifier,
        val occurredAt: Long,
        val decisionReason: SecureDeletionDecisionReason?,
        val policyRevision: String?,
        val legalHoldRevision: String?,
        val authorizationRevision: String?,
        val activeLegalHoldIds: List<Identifier>,
        val failedStage: SecureDeletionStage?,
        val failureCount: Int?,
        val message: String?,
    )

    private data class AuditRow(
        val id: Identifier,
        val tenantId: Identifier,
        val createToken: String,
        val evidenceType: AuditEvidenceType,
        val planId: Identifier?,
        val tombstoneId: Identifier?,
        val resourceType: String,
        val resourceId: Identifier,
        val resourceRevision: Long,
        val requestedBy: Identifier,
        val occurredAt: Long,
        val decisionReason: SecureDeletionDecisionReason?,
        val policyRevision: String?,
        val legalHoldRevision: String?,
        val authorizationRevision: String?,
        val activeLegalHoldIds: List<Identifier>,
        val failedStage: SecureDeletionStage?,
        val failureCount: Int?,
        val message: String?,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private data class PersistedReceiptRow(
        val tenantId: Identifier,
        val planId: Identifier,
        val receipt: StoredSecureDeletionReceipt,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private data class OutboxFenceRow(
        val tenantId: Identifier,
        val id: Identifier,
        val eventType: String,
        val status: OutboxEventStatus,
        val leaseOwner: String?,
        val leaseToken: String?,
    )

    private companion object {
        val PROGRESS_STAGES = listOf(
            SecureDeletionStage.PURGE_INDEX_PROJECTIONS,
            SecureDeletionStage.PURGE_OBJECT_STORAGE,
            SecureDeletionStage.FINALIZE_DATABASE,
            SecureDeletionStage.APPEND_COMPLETION_AUDIT,
        )

        const val MAX_IDENTIFIER_LENGTH = 64
        const val MAX_RESOURCE_TYPE_LENGTH = 128
        const val MAX_REVISION_LENGTH = 256
        const val MAX_IDEMPOTENCY_LENGTH = 1024
        const val MAX_PROVIDER_ID_LENGTH = 128
        const val MAX_RECEIPT_REFERENCE_LENGTH = 2048
        const val MAX_MESSAGE_LENGTH = 1024

        const val SELECT_PLAN_COLUMNS = """
            id, tenant_id, create_token, dispatch_event_id, tombstone_id, decision_evidence_id,
            resource_type, resource_id, resource_revision, requested_by,
            policy_revision, legal_hold_revision, authorization_revision,
            index_idempotency_key, object_idempotency_key, current_stage, execution_status,
            failure_count, last_error, created_time, updated_time
        """
        const val SELECT_PLAN_SQL =
            "SELECT $SELECT_PLAN_COLUMNS FROM fw_secure_deletion_plan WHERE tenant_id = ? AND id = ?"

        const val SELECT_TOMBSTONE_SQL = """
            SELECT id, tenant_id, plan_id, resource_type, resource_id, resource_revision, blocked_time,
                   policy_revision, legal_hold_revision, authorization_revision, created_time, updated_time
              FROM fw_secure_deletion_tombstone
             WHERE tenant_id = ? AND id = ?
        """

        const val SELECT_AUDIT_SQL = """
            SELECT id, tenant_id, create_token, evidence_type, plan_id, tombstone_id,
                   resource_type, resource_id, resource_revision, requested_by, occurred_time,
                   decision_reason, policy_revision, legal_hold_revision, authorization_revision,
                   active_legal_hold_ids_json, failed_stage, failure_count, message, created_time, updated_time
              FROM fw_secure_deletion_audit
             WHERE tenant_id = ? AND id = ?
        """

        const val SELECT_RECEIPTS_SQL = """
            SELECT tenant_id, plan_id, deletion_stage, idempotency_key, provider_id, provider_target,
                   provider_status, request_binding_digest, receipt_reference, message, evidence_json,
                   recorded_time, created_time, updated_time
              FROM fw_secure_deletion_receipt
             WHERE tenant_id = ? AND plan_id = ?
             ORDER BY deletion_stage
        """

        const val INSERT_TOMBSTONE_SQL = """
            INSERT INTO fw_secure_deletion_tombstone(
                id, tenant_id, plan_id, resource_type, resource_id, resource_revision, blocked_time,
                policy_revision, legal_hold_revision, authorization_revision, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        const val UPDATE_PLAN_SQL = """
            UPDATE fw_secure_deletion_plan
               SET current_stage = ?, execution_status = ?, failure_count = ?, last_error = ?, updated_time = ?
             WHERE tenant_id = ? AND id = ? AND resource_revision = ?
               AND current_stage = ? AND execution_status = ? AND updated_time = ?
        """

        const val LOCK_OUTBOX_SQL = """
            SELECT id, tenant_id, event_type, event_status, lease_owner, lease_token
              FROM fw_outbox_event
             WHERE tenant_id = ? AND id = ?
             FOR UPDATE
        """
    }
}
