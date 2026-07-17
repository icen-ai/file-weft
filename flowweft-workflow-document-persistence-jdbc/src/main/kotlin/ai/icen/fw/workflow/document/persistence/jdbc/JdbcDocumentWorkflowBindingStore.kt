package ai.icen.fw.workflow.document.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.document.DocumentWorkflowAction
import ai.icen.fw.workflow.document.DocumentWorkflowBinding
import ai.icen.fw.workflow.document.DocumentWorkflowBindingApplicationPort
import ai.icen.fw.workflow.document.DocumentWorkflowBindingLookupRequest
import ai.icen.fw.workflow.document.DocumentWorkflowBindingReservation
import ai.icen.fw.workflow.document.DocumentWorkflowBindingReservationCode
import ai.icen.fw.workflow.document.DocumentWorkflowBindingReserveRequest
import ai.icen.fw.workflow.document.DocumentWorkflowBindingState
import ai.icen.fw.workflow.document.DocumentWorkflowBindingTransitionRequest
import ai.icen.fw.workflow.document.DocumentWorkflowBindingTransitionResult
import ai.icen.fw.workflow.document.DocumentWorkflowPortOutcome
import ai.icen.fw.workflow.document.DocumentWorkflowRevisionPolicyRef
import ai.icen.fw.workflow.document.DocumentWorkflowSelection
import ai.icen.fw.workflow.document.DocumentWorkflowTemplateRef
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Durable document-to-workflow saga binding.
 *
 * The binding history and the active-document latch are separate rows in one local transaction.
 * Therefore terminal workflows remain auditable while only RESERVED, ACTIVE, revision-wait and
 * reconciliation states block a second workflow. No external call is made while a row is locked.
 */
class JdbcDocumentWorkflowBindingStore(
    private val dataSource: DataSource,
) : DocumentWorkflowBindingApplicationPort {
    override fun reserve(request: DocumentWorkflowBindingReserveRequest): DocumentWorkflowBindingReservation =
        try {
            transaction { connection -> reserveInTransaction(connection, request) }
        } catch (_: SQLException) {
            resolveReservationAfterFailure(request)
        }

    override fun find(request: DocumentWorkflowBindingLookupRequest): DocumentWorkflowBinding? =
        try {
            read { connection ->
                selectBinding(
                    connection,
                    request.callContext.tenantId,
                    request.instanceId,
                    request.document,
                    false,
                )?.binding
            }
        } catch (failure: SQLException) {
            throw IllegalStateException("Document workflow binding persistence is unavailable.", failure)
        }

    override fun transition(
        request: DocumentWorkflowBindingTransitionRequest,
    ): DocumentWorkflowBindingTransitionResult = try {
        transaction { connection -> transitionInTransaction(connection, request) }
    } catch (_: SQLException) {
        resolveTransitionAfterFailure(request)
    }

    private fun reserveInTransaction(
        connection: Connection,
        request: DocumentWorkflowBindingReserveRequest,
    ): DocumentWorkflowBindingReservation {
        val submission = request.submission
        val tenantId = submission.callContext.tenantId
        val document = submission.expectedSubject.ref
        val existing = selectBinding(connection, tenantId, submission.instanceId, null, true)
        if (existing != null) return classifyExistingReservation(existing.binding, submission)

        val activeInstance = selectActiveInstance(connection, tenantId, document, true)
        if (activeInstance != null) {
            val active = checkNotNull(selectBinding(connection, tenantId, activeInstance, document, true)) {
                "Document workflow active latch has no binding."
            }
            return classifyActiveReservation(active.binding, submission)
        }

        val binding = DocumentWorkflowBinding.of(
            tenantId,
            document,
            submission.instanceId,
            DocumentWorkflowBindingState.RESERVED,
            submission.expectedSubject,
            submission.expectedSelection,
            submission.options.idempotencyKey,
            submission.requestDigest,
            submission.action,
            submission.options.idempotencyKey,
            submission.requestDigest,
            0L,
            1L,
        )
        insertBinding(connection, binding, request, submission.options.now)
        insertActiveLatch(connection, binding, submission.options.now)
        return DocumentWorkflowBindingReservation.accepted(
            DocumentWorkflowBindingReservationCode.RESERVED,
            binding,
        )
    }

    private fun transitionInTransaction(
        connection: Connection,
        request: DocumentWorkflowBindingTransitionRequest,
    ): DocumentWorkflowBindingTransitionResult {
        val tenantId = request.callContext.tenantId
        val stored = selectBinding(connection, tenantId, request.instanceId, request.document, true)
            ?: return rejected("binding-not-found")
        if (isTransitionReplay(stored, request)) {
            return DocumentWorkflowBindingTransitionResult.success(
                DocumentWorkflowPortOutcome.REPLAYED,
                stored.binding,
            )
        }
        if (stored.binding.lastOperationIdempotencyKey == request.idempotencyKey &&
            (stored.binding.lastOperationRequestDigest != request.logicalRequestDigest ||
                stored.lastEvidenceDigest != request.evidenceDigest)
        ) return rejected("idempotency-conflict")

        if (!matchesExpected(stored.binding, request)) return rejected("binding-conflict")
        val activeInstance = selectActiveInstance(connection, tenantId, request.document, true)
        if (activeInstance != request.instanceId) return rejected("active-latch-conflict")
        if (stored.binding.revision == Long.MAX_VALUE) return rejected("binding-revision-exhausted")

        val next = DocumentWorkflowBinding.of(
            tenantId,
            stored.binding.document,
            stored.binding.instanceId,
            request.targetState,
            request.targetSubject,
            stored.binding.selection,
            stored.binding.startIdempotencyKey,
            stored.binding.startRequestDigest,
            request.action,
            request.idempotencyKey,
            request.logicalRequestDigest,
            request.targetCycleNumber,
            stored.binding.revision + 1L,
        )
        if (updateBinding(connection, stored, next, request) != 1) return rejected("binding-conflict")
        if (request.targetState == DocumentWorkflowBindingState.TERMINAL) {
            if (deleteActiveLatch(connection, tenantId, request.document, request.instanceId) != 1) {
                throw IllegalStateException("Document workflow active latch disappeared during termination.")
            }
        } else if (touchActiveLatch(
                connection,
                tenantId,
                request.document,
                request.instanceId,
                request.transitionedAtEpochMilli,
            ) != 1
        ) {
            throw IllegalStateException("Document workflow active latch disappeared during transition.")
        }
        return DocumentWorkflowBindingTransitionResult.success(DocumentWorkflowPortOutcome.APPLIED, next)
    }

    private fun resolveReservationAfterFailure(
        request: DocumentWorkflowBindingReserveRequest,
    ): DocumentWorkflowBindingReservation = try {
        read { connection ->
            val submission = request.submission
            val tenantId = submission.callContext.tenantId
            selectBinding(connection, tenantId, submission.instanceId, null, false)?.let {
                return@read classifyExistingReservation(it.binding, submission)
            }
            val activeInstance = selectActiveInstance(connection, tenantId, submission.expectedSubject.ref, false)
                ?: return@read unknownReservation()
            val active = selectBinding(
                connection,
                tenantId,
                activeInstance,
                submission.expectedSubject.ref,
                false,
            ) ?: return@read unknownReservation()
            classifyActiveReservation(active.binding, submission)
        }
    } catch (_: RuntimeException) {
        unknownReservation()
    } catch (_: SQLException) {
        unknownReservation()
    }

    private fun resolveTransitionAfterFailure(
        request: DocumentWorkflowBindingTransitionRequest,
    ): DocumentWorkflowBindingTransitionResult = try {
        read { connection ->
            val stored = selectBinding(
                connection,
                request.callContext.tenantId,
                request.instanceId,
                request.document,
                false,
            )
            if (stored != null && isTransitionReplay(stored, request)) {
                DocumentWorkflowBindingTransitionResult.success(
                    DocumentWorkflowPortOutcome.REPLAYED,
                    stored.binding,
                )
            } else {
                unknownTransition()
            }
        }
    } catch (_: RuntimeException) {
        unknownTransition()
    } catch (_: SQLException) {
        unknownTransition()
    }

    private fun classifyExistingReservation(
        binding: DocumentWorkflowBinding,
        submission: ai.icen.fw.workflow.document.DocumentWorkflowSubmissionRequest,
    ): DocumentWorkflowBindingReservation = if (binding.matches(submission)) {
        DocumentWorkflowBindingReservation.accepted(DocumentWorkflowBindingReservationCode.REPLAYED, binding)
    } else {
        DocumentWorkflowBindingReservation.rejected(DocumentWorkflowBindingReservationCode.IDEMPOTENCY_CONFLICT)
    }

    private fun classifyActiveReservation(
        binding: DocumentWorkflowBinding,
        submission: ai.icen.fw.workflow.document.DocumentWorkflowSubmissionRequest,
    ): DocumentWorkflowBindingReservation = if (binding.instanceId == submission.instanceId) {
        classifyExistingReservation(binding, submission)
    } else {
        DocumentWorkflowBindingReservation.rejected(DocumentWorkflowBindingReservationCode.ACTIVE_CONFLICT)
    }

    private fun matchesExpected(
        binding: DocumentWorkflowBinding,
        request: DocumentWorkflowBindingTransitionRequest,
    ): Boolean = binding.tenantId == request.callContext.tenantId &&
        binding.document == request.document &&
        binding.instanceId == request.instanceId &&
        binding.revision == request.expectedRevision &&
        binding.state == request.expectedState &&
        binding.subject == request.expectedSubject &&
        binding.cycleNumber == request.expectedCycleNumber

    private fun isTransitionReplay(
        stored: StoredBinding,
        request: DocumentWorkflowBindingTransitionRequest,
    ): Boolean = stored.binding.tenantId == request.callContext.tenantId &&
        stored.binding.document == request.document &&
        stored.binding.instanceId == request.instanceId &&
        stored.binding.revision > request.expectedRevision &&
        stored.binding.state == request.targetState &&
        stored.binding.subject == request.targetSubject &&
        stored.binding.cycleNumber == request.targetCycleNumber &&
        stored.binding.lastAction == request.action &&
        stored.binding.lastOperationIdempotencyKey == request.idempotencyKey &&
        stored.binding.lastOperationRequestDigest == request.logicalRequestDigest &&
        stored.lastEvidenceDigest == request.evidenceDigest

    private fun insertBinding(
        connection: Connection,
        binding: DocumentWorkflowBinding,
        request: DocumentWorkflowBindingReserveRequest,
        now: Long,
    ) {
        connection.prepareStatement(INSERT_BINDING_SQL).use { statement ->
            var index = 1
            statement.setString(index++, bindingId(binding.tenantId, binding.instanceId))
            statement.setString(index++, binding.tenantId)
            statement.setString(index++, binding.document.type)
            statement.setString(index++, binding.document.id)
            statement.setString(index++, binding.instanceId)
            statement.setString(index++, binding.state.code)
            statement.setString(index++, binding.subject.revision)
            statement.setString(index++, binding.subject.digest)
            statement.setString(index++, binding.selection.definitionId)
            statement.setString(index++, binding.selection.definitionRef.key)
            statement.setString(index++, binding.selection.definitionRef.version)
            statement.setString(index++, binding.selection.definitionRef.digest)
            statement.setString(index++, binding.selection.templateRef.key)
            statement.setString(index++, binding.selection.templateRef.revision)
            statement.setString(index++, binding.selection.templateRef.digest)
            statement.setString(index++, binding.selection.revisionPolicy.revision)
            statement.setString(index++, binding.selection.revisionPolicy.digest)
            statement.setString(index++, binding.selection.revisionPolicy.resumeNodeId)
            statement.setString(index++, binding.selection.authorityRevision)
            statement.setString(index++, binding.selection.selectionDigest)
            statement.setString(index++, binding.startIdempotencyKey)
            statement.setString(index++, binding.startRequestDigest)
            statement.setString(index++, request.requestDigest)
            statement.setString(index++, request.authorizationDecisionDigest)
            statement.setString(index++, binding.lastAction.code)
            statement.setString(index++, binding.lastOperationIdempotencyKey)
            statement.setString(index++, binding.lastOperationRequestDigest)
            statement.setString(index++, request.authorizationDecisionDigest)
            statement.setLong(index++, binding.cycleNumber)
            statement.setLong(index++, binding.revision)
            statement.setString(index++, binding.bindingDigest)
            statement.setLong(index++, now)
            statement.setLong(index, now)
            check(statement.executeUpdate() == 1) { "Document workflow binding insert was not applied." }
        }
    }

    private fun insertActiveLatch(connection: Connection, binding: DocumentWorkflowBinding, now: Long) {
        connection.prepareStatement(INSERT_ACTIVE_SQL).use { statement ->
            statement.setString(1, activeId(binding.tenantId, binding.document))
            statement.setString(2, binding.tenantId)
            statement.setString(3, binding.document.type)
            statement.setString(4, binding.document.id)
            statement.setString(5, binding.instanceId)
            statement.setLong(6, now)
            statement.setLong(7, now)
            check(statement.executeUpdate() == 1) { "Document workflow active latch insert was not applied." }
        }
    }

    private fun updateBinding(
        connection: Connection,
        stored: StoredBinding,
        next: DocumentWorkflowBinding,
        request: DocumentWorkflowBindingTransitionRequest,
    ): Int = connection.prepareStatement(UPDATE_BINDING_SQL).use { statement ->
        statement.setString(1, next.state.code)
        statement.setString(2, next.subject.revision)
        statement.setString(3, next.subject.digest)
        statement.setString(4, next.lastAction.code)
        statement.setString(5, next.lastOperationIdempotencyKey)
        statement.setString(6, next.lastOperationRequestDigest)
        statement.setString(7, request.evidenceDigest)
        statement.setLong(8, next.cycleNumber)
        statement.setLong(9, next.revision)
        statement.setString(10, next.bindingDigest)
        statement.setLong(11, request.transitionedAtEpochMilli)
        statement.setString(12, stored.id)
        statement.setString(13, next.tenantId)
        statement.setLong(14, stored.binding.revision)
        statement.executeUpdate()
    }

    private fun selectBinding(
        connection: Connection,
        tenantId: String,
        instanceId: String,
        document: WorkflowSubjectRef?,
        forUpdate: Boolean,
    ): StoredBinding? {
        val sql = buildString {
            append(SELECT_BINDING_SQL)
            if (document != null) append(" AND document_type = ? AND document_id = ?")
            if (forUpdate) append(" FOR UPDATE")
        }
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, instanceId)
            if (document != null) {
                statement.setString(3, document.type)
                statement.setString(4, document.id)
            }
            statement.executeQuery().use { result -> if (result.next()) mapBinding(result) else null }
        }
    }

    private fun selectActiveInstance(
        connection: Connection,
        tenantId: String,
        document: WorkflowSubjectRef,
        forUpdate: Boolean,
    ): String? {
        val sql = SELECT_ACTIVE_SQL + if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, document.type)
            statement.setString(3, document.id)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
    }

    private fun mapBinding(result: ResultSet): StoredBinding {
        val document = WorkflowSubjectRef.of(result.getString("document_type"), result.getString("document_id"))
        val subject = WorkflowSubjectSnapshot.of(
            document,
            result.getString("subject_revision"),
            result.getString("subject_digest"),
        )
        val selection = DocumentWorkflowSelection.of(
            result.getString("definition_id"),
            WorkflowDefinitionRef.of(
                result.getString("definition_key"),
                result.getString("definition_version"),
                result.getString("definition_digest"),
            ),
            DocumentWorkflowTemplateRef.of(
                result.getString("template_key"),
                result.getString("template_revision"),
                result.getString("template_digest"),
            ),
            DocumentWorkflowRevisionPolicyRef.of(
                result.getString("revision_policy_revision"),
                result.getString("revision_policy_digest"),
                result.getString("revision_resume_node_id"),
            ),
            result.getString("selection_authority_revision"),
        )
        check(selection.selectionDigest == result.getString("selection_digest")) {
            "Document workflow selection evidence is corrupt."
        }
        val binding = DocumentWorkflowBinding.of(
            result.getString("tenant_id"),
            document,
            result.getString("instance_id"),
            bindingState(result.getString("state_code")),
            subject,
            selection,
            result.getString("start_idempotency_key"),
            result.getString("start_request_digest"),
            DocumentWorkflowAction.of(result.getString("last_action_code")),
            result.getString("last_operation_idempotency_key"),
            result.getString("last_operation_request_digest"),
            result.getLong("cycle_number"),
            result.getLong("binding_revision"),
        )
        check(binding.bindingDigest == result.getString("binding_digest")) {
            "Document workflow binding evidence is corrupt."
        }
        return StoredBinding(
            result.getString("id"),
            binding,
            result.getString("last_evidence_digest"),
        )
    }

    private fun bindingState(code: String): DocumentWorkflowBindingState = when (code) {
        DocumentWorkflowBindingState.RESERVED.code -> DocumentWorkflowBindingState.RESERVED
        DocumentWorkflowBindingState.ACTIVE.code -> DocumentWorkflowBindingState.ACTIVE
        DocumentWorkflowBindingState.WAITING_SUBJECT_REVISION.code ->
            DocumentWorkflowBindingState.WAITING_SUBJECT_REVISION
        DocumentWorkflowBindingState.RECONCILIATION_REQUIRED.code ->
            DocumentWorkflowBindingState.RECONCILIATION_REQUIRED
        DocumentWorkflowBindingState.TERMINAL.code -> DocumentWorkflowBindingState.TERMINAL
        else -> throw IllegalStateException("Unsupported document workflow binding state.")
    }

    private fun touchActiveLatch(
        connection: Connection,
        tenantId: String,
        document: WorkflowSubjectRef,
        instanceId: String,
        now: Long,
    ): Int = connection.prepareStatement(TOUCH_ACTIVE_SQL).use { statement ->
        statement.setLong(1, now)
        statement.setString(2, tenantId)
        statement.setString(3, document.type)
        statement.setString(4, document.id)
        statement.setString(5, instanceId)
        statement.executeUpdate()
    }

    private fun deleteActiveLatch(
        connection: Connection,
        tenantId: String,
        document: WorkflowSubjectRef,
        instanceId: String,
    ): Int = connection.prepareStatement(DELETE_ACTIVE_SQL).use { statement ->
        statement.setString(1, tenantId)
        statement.setString(2, document.type)
        statement.setString(3, document.id)
        statement.setString(4, instanceId)
        statement.executeUpdate()
    }

    private fun <T> read(action: (Connection) -> T): T = dataSource.connection.use(action)

    private fun <T> transaction(action: (Connection) -> T): T {
        val connection = dataSource.connection
        val originalAutoCommit = connection.autoCommit
        try {
            connection.autoCommit = false
            val result = try {
                action(connection)
            } catch (failure: Throwable) {
                try {
                    connection.rollback()
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
            connection.commit()
            return result
        } finally {
            try {
                connection.autoCommit = originalAutoCommit
            } catch (_: Throwable) {
                // Closing the connection remains the final cleanup boundary.
            }
            connection.close()
        }
    }

    private fun rejected(code: String): DocumentWorkflowBindingTransitionResult =
        DocumentWorkflowBindingTransitionResult.failure(DocumentWorkflowPortOutcome.REJECTED, code)

    private fun unknownTransition(): DocumentWorkflowBindingTransitionResult =
        DocumentWorkflowBindingTransitionResult.failure(
            DocumentWorkflowPortOutcome.OUTCOME_UNKNOWN,
            "persistence-outcome-unknown",
        )

    private fun unknownReservation(): DocumentWorkflowBindingReservation =
        DocumentWorkflowBindingReservation.rejected(DocumentWorkflowBindingReservationCode.OUTCOME_UNKNOWN)

    private fun bindingId(tenantId: String, instanceId: String): String =
        stableDigest("flowweft-document-workflow-binding-v1", tenantId, instanceId)

    private fun activeId(tenantId: String, document: WorkflowSubjectRef): String =
        stableDigest("flowweft-document-workflow-active-v1", tenantId, document.type, document.id)

    private fun stableDigest(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private class StoredBinding(
        val id: String,
        val binding: DocumentWorkflowBinding,
        val lastEvidenceDigest: String,
    )

    private companion object {
        const val INSERT_BINDING_SQL = """
            INSERT INTO fw_wf_doc_binding (
                id, tenant_id, document_type, document_id, instance_id, state_code,
                subject_revision, subject_digest, definition_id, definition_key, definition_version,
                definition_digest, template_key, template_revision, template_digest,
                revision_policy_revision, revision_policy_digest, revision_resume_node_id,
                selection_authority_revision, selection_digest, start_idempotency_key,
                start_request_digest, reservation_request_digest, reservation_authorization_digest,
                last_action_code, last_operation_idempotency_key, last_operation_request_digest,
                last_evidence_digest, cycle_number, binding_revision, binding_digest,
                created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        const val INSERT_ACTIVE_SQL = """
            INSERT INTO fw_wf_doc_active_binding (
                id, tenant_id, document_type, document_id, instance_id, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """

        const val SELECT_BINDING_SQL = """
            SELECT * FROM fw_wf_doc_binding WHERE tenant_id = ? AND instance_id = ?
        """

        const val SELECT_ACTIVE_SQL = """
            SELECT instance_id FROM fw_wf_doc_active_binding
            WHERE tenant_id = ? AND document_type = ? AND document_id = ?
        """

        const val UPDATE_BINDING_SQL = """
            UPDATE fw_wf_doc_binding SET
                state_code = ?, subject_revision = ?, subject_digest = ?, last_action_code = ?,
                last_operation_idempotency_key = ?, last_operation_request_digest = ?,
                last_evidence_digest = ?, cycle_number = ?, binding_revision = ?, binding_digest = ?,
                updated_time = ?
            WHERE id = ? AND tenant_id = ? AND binding_revision = ?
        """

        const val TOUCH_ACTIVE_SQL = """
            UPDATE fw_wf_doc_active_binding SET updated_time = ?
            WHERE tenant_id = ? AND document_type = ? AND document_id = ? AND instance_id = ?
        """

        const val DELETE_ACTIVE_SQL = """
            DELETE FROM fw_wf_doc_active_binding
            WHERE tenant_id = ? AND document_type = ? AND document_id = ? AND instance_id = ?
        """
    }
}
