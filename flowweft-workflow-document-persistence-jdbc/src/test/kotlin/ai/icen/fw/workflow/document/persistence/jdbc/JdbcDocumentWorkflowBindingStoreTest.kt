package ai.icen.fw.workflow.document.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.document.DocumentWorkflowAction
import ai.icen.fw.workflow.document.DocumentWorkflowBindingLookupRequest
import ai.icen.fw.workflow.document.DocumentWorkflowBindingReservationCode
import ai.icen.fw.workflow.document.DocumentWorkflowBindingReserveRequest
import ai.icen.fw.workflow.document.DocumentWorkflowBindingState
import ai.icen.fw.workflow.document.DocumentWorkflowBindingTransitionRequest
import ai.icen.fw.workflow.document.DocumentWorkflowPortOutcome
import ai.icen.fw.workflow.document.DocumentWorkflowRevisionPolicyRef
import ai.icen.fw.workflow.document.DocumentWorkflowSelection
import ai.icen.fw.workflow.document.DocumentWorkflowSubmissionRequest
import ai.icen.fw.workflow.document.DocumentWorkflowTemplateRef
import ai.icen.fw.workflow.domain.WorkflowExecutionIds
import ai.icen.fw.workflow.runtime.WorkflowRuntimeCommandOptions
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import org.h2.jdbcx.JdbcDataSource
import java.nio.charset.StandardCharsets
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class JdbcDocumentWorkflowBindingStoreTest {
    @Test
    fun `reserve is tenant scoped replay safe and blocks another active instance`() {
        val store = store("reserve")
        val first = submission(CONTEXT_A, "instance-1", "key-1", 100L, A)

        val reserved = store.reserve(DocumentWorkflowBindingReserveRequest.of(first, B))
        val replayed = store.reserve(DocumentWorkflowBindingReserveRequest.of(first, B))
        val conflict = store.reserve(DocumentWorkflowBindingReserveRequest.of(
            submission(CONTEXT_A, "instance-2", "key-2", 101L, C),
            B,
        ))
        val otherTenant = store.reserve(DocumentWorkflowBindingReserveRequest.of(
            submission(CONTEXT_B, "instance-1", "key-1", 100L, A),
            B,
        ))

        assertSame(DocumentWorkflowBindingReservationCode.RESERVED, reserved.code)
        assertSame(DocumentWorkflowBindingReservationCode.REPLAYED, replayed.code)
        assertSame(DocumentWorkflowBindingReservationCode.ACTIVE_CONFLICT, conflict.code)
        assertSame(DocumentWorkflowBindingReservationCode.RESERVED, otherTenant.code)
        assertEquals(reserved.binding!!.bindingDigest, replayed.binding!!.bindingDigest)
    }

    @Test
    fun `same instance with changed logical request is an idempotency conflict`() {
        val store = store("idempotency")
        val first = submission(CONTEXT_A, "instance-1", "key-1", 100L, A)
        store.reserve(DocumentWorkflowBindingReserveRequest.of(first, B))

        val conflict = store.reserve(DocumentWorkflowBindingReserveRequest.of(
            submission(CONTEXT_A, "instance-1", "key-1", 101L, C),
            B,
        ))

        assertSame(DocumentWorkflowBindingReservationCode.IDEMPOTENCY_CONFLICT, conflict.code)
    }

    @Test
    fun `transition uses compare and set and exact replay evidence`() {
        val store = store("transition")
        val submission = submission(CONTEXT_A, "instance-1", "key-1", 100L, A)
        val binding = assertNotNull(
            store.reserve(DocumentWorkflowBindingReserveRequest.of(submission, B)).binding,
        )
        val transition = DocumentWorkflowBindingTransitionRequest.of(
            CONTEXT_A,
            DocumentWorkflowAction.SUBMIT,
            SUBJECT.ref,
            submission.instanceId,
            binding.revision,
            DocumentWorkflowBindingState.RESERVED,
            DocumentWorkflowBindingState.ACTIVE,
            SUBJECT,
            SUBJECT,
            0L,
            0L,
            "activate-key",
            C,
            D,
            200L,
        )

        val applied = store.transition(transition)
        val replayed = store.transition(transition)
        val stale = store.transition(DocumentWorkflowBindingTransitionRequest.of(
            CONTEXT_A,
            DocumentWorkflowAction.SUBMIT,
            SUBJECT.ref,
            submission.instanceId,
            binding.revision,
            DocumentWorkflowBindingState.RESERVED,
            DocumentWorkflowBindingState.ACTIVE,
            SUBJECT,
            SUBJECT,
            0L,
            0L,
            "another-key",
            E,
            F,
            201L,
        ))

        assertSame(DocumentWorkflowPortOutcome.APPLIED, applied.outcome)
        assertSame(DocumentWorkflowPortOutcome.REPLAYED, replayed.outcome)
        assertSame(DocumentWorkflowPortOutcome.REJECTED, stale.outcome)
        assertSame(DocumentWorkflowBindingState.ACTIVE, applied.binding!!.state)
        assertEquals(2L, applied.binding!!.revision)
        assertEquals(
            applied.binding!!.bindingDigest,
            store.find(DocumentWorkflowBindingLookupRequest.of(CONTEXT_A, SUBJECT.ref, "instance-1", 300L))
                ?.bindingDigest,
        )
        assertNull(store.find(DocumentWorkflowBindingLookupRequest.of(CONTEXT_B, SUBJECT.ref, "instance-1", 300L)))
    }

    @Test
    fun `terminal reservation releases latch but keeps historical binding`() {
        val store = store("terminal")
        val first = submission(CONTEXT_A, "instance-1", "key-1", 100L, A)
        val binding = assertNotNull(store.reserve(DocumentWorkflowBindingReserveRequest.of(first, B)).binding)
        val terminal = store.transition(DocumentWorkflowBindingTransitionRequest.of(
            CONTEXT_A,
            DocumentWorkflowAction.SUBMIT,
            SUBJECT.ref,
            first.instanceId,
            binding.revision,
            DocumentWorkflowBindingState.RESERVED,
            DocumentWorkflowBindingState.TERMINAL,
            SUBJECT,
            SUBJECT,
            0L,
            0L,
            "abandon-key",
            C,
            D,
            150L,
        ))

        val next = store.reserve(DocumentWorkflowBindingReserveRequest.of(
            submission(CONTEXT_A, "instance-2", "key-2", 200L, E),
            F,
        ))

        assertSame(DocumentWorkflowPortOutcome.APPLIED, terminal.outcome)
        assertSame(DocumentWorkflowBindingState.TERMINAL, terminal.binding!!.state)
        assertSame(DocumentWorkflowBindingReservationCode.RESERVED, next.code)
        assertSame(
            DocumentWorkflowBindingState.TERMINAL,
            store.find(DocumentWorkflowBindingLookupRequest.of(CONTEXT_A, SUBJECT.ref, "instance-1", 300L))!!.state,
        )
    }

    @Test
    fun `all supported dialect migrations retain the durable latch and evidence columns`() {
        listOf("postgres", "mysql", "kingbase").forEach { dialect ->
            val path = "/ai/icen/fw/workflow/document/db/migration/$dialect/V036__persist_document_workflow_binding.sql"
            val sql = checkNotNull(javaClass.getResourceAsStream(path)).use {
                String(it.readBytes(), StandardCharsets.UTF_8).lowercase()
            }
            listOf(
                "fw_wf_doc_binding",
                "fw_wf_doc_active_binding",
                "tenant_id",
                "created_time",
                "updated_time",
                "binding_revision",
                "binding_digest",
                "reservation_authorization_digest",
                "last_evidence_digest",
                "unique (tenant_id, document_type, document_id)",
            ).forEach { required ->
                check(sql.contains(required)) { "$dialect V036 is missing $required" }
            }
        }
    }

    private fun store(name: String): JdbcDocumentWorkflowBindingStore {
        val dataSource = dataSource(name)
        migrate(dataSource)
        return JdbcDocumentWorkflowBindingStore(dataSource)
    }

    private fun dataSource(name: String): DataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:doc-$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
        user = "sa"
        password = ""
    }

    private fun migrate(dataSource: DataSource) {
        val path = "/ai/icen/fw/workflow/document/db/migration/postgres/V036__persist_document_workflow_binding.sql"
        val sql = checkNotNull(javaClass.getResourceAsStream(path)).use {
            String(it.readBytes(), StandardCharsets.UTF_8)
        }
        dataSource.connection.use { connection ->
            sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach { statement ->
                connection.createStatement().use { it.execute(statement) }
            }
        }
    }

    private fun submission(
        context: WorkflowTrustedCallContext,
        instanceId: String,
        key: String,
        now: Long,
        purpose: String,
    ): DocumentWorkflowSubmissionRequest = DocumentWorkflowSubmissionRequest.of(
        context,
        options("command-$instanceId-$now", key, now),
        instanceId,
        SUBJECT,
        SELECTION,
        purpose,
    )

    private fun options(commandId: String, key: String, now: Long): WorkflowRuntimeCommandOptions =
        WorkflowRuntimeCommandOptions.of(
            commandId,
            key,
            0L,
            now,
            16,
            WorkflowExecutionIds.of(
                listOf("$commandId-token"),
                listOf("$commandId-execution"),
                listOf("$commandId-work-item"),
                listOf("$commandId-effect"),
                listOf("$commandId-event"),
                listOf("$commandId-scope"),
            ),
        )

    private companion object {
        val A = "a".repeat(64)
        val B = "b".repeat(64)
        val C = "c".repeat(64)
        val D = "d".repeat(64)
        val E = "e".repeat(64)
        val F = "f".repeat(64)
        val SUBJECT = WorkflowSubjectSnapshot.of(WorkflowSubjectRef.of("document", "document-1"), "v1", B)
        val SELECTION = DocumentWorkflowSelection.of(
            "definition-1",
            WorkflowDefinitionRef.of("knowledge-approval", "1", C),
            DocumentWorkflowTemplateRef.of("knowledge", "1", D),
            DocumentWorkflowRevisionPolicyRef.of("1", E, "content-review"),
            "authority-1",
        )
        val CONTEXT_A = WorkflowTrustedCallContext.of(
            "tenant-a",
            WorkflowPrincipalRef.of("user", "alice"),
            "auth-a",
            F,
        )
        val CONTEXT_B = WorkflowTrustedCallContext.of(
            "tenant-b",
            WorkflowPrincipalRef.of("user", "alice"),
            "auth-b",
            F,
        )
    }
}
