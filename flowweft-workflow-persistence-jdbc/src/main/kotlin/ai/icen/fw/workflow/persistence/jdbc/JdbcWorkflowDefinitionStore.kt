package ai.icen.fw.workflow.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.domain.WorkflowDefinitionExecutionReceipt
import ai.icen.fw.workflow.domain.WorkflowDefinitionIndex
import ai.icen.fw.workflow.runtime.WorkflowRuntimeDefinitionRecord
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource

/**
 * Immutable definition-version deployment store. The supplied execution receipt is trusted runtime
 * evidence; this adapter persists it but never creates or authorizes it.
 */
class JdbcWorkflowDefinitionStore @JvmOverloads constructor(
    dataSource: DataSource,
    dialect: WorkflowJdbcDialect? = null,
) {
    private val transactions = WorkflowJdbcTransactions(dataSource, dialect)

    fun install(record: WorkflowRuntimeDefinitionRecord, installedAt: Long) {
        require(installedAt >= record.executionReceipt.acceptedAt) {
            "Workflow definition installation cannot predate its execution receipt."
        }
        val definition = record.index.definition
        val receipt = record.executionReceipt
        transactions.transaction { connection, sqlDialect ->
            insertDefinitionRoot(connection, sqlDialect, record, installedAt)
            val inserted = insertDefinitionVersion(connection, sqlDialect, record, installedAt)
            val existing = loadVersionForUpdate(connection, definition.tenantId, definition.definitionId, definition.ref)
                ?: throw IllegalStateException("Workflow definition version insert was not observable in its transaction.")
            check(existing.index.definition.contentDigest == definition.contentDigest &&
                existing.executionReceipt.receiptDigest == receipt.receiptDigest
            ) {
                "Workflow definition versions are immutable and the persisted identity already differs."
            }
            if (inserted || definition.status.code == "published") {
                connection.prepareStatement(
                    """
                    UPDATE fw_wf_definition
                    SET title = ?, lifecycle_status = ?, latest_version_id = ?, updated_time = ?
                    WHERE tenant_id = ? AND id = ? AND definition_key = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, definition.title)
                    statement.setString(2, definition.status.code)
                    statement.setString(3, versionRowId(definition.definitionId, definition.ref))
                    statement.setLong(4, installedAt)
                    statement.setString(5, definition.tenantId)
                    statement.setString(6, definition.definitionId)
                    statement.setString(7, definition.key)
                    check(statement.executeUpdate() == 1) { "Workflow definition root identity is inconsistent." }
                }
            }
        }
    }

    fun load(
        tenantId: String,
        definitionId: String,
        definitionRef: WorkflowDefinitionRef,
    ): WorkflowRuntimeDefinitionRecord? = transactions.read { connection, _ ->
        loadVersion(connection, tenantId, definitionId, definitionRef, forUpdate = false)
    }

    private fun insertDefinitionRoot(
        connection: Connection,
        dialect: WorkflowJdbcDialect,
        record: WorkflowRuntimeDefinitionRecord,
        now: Long,
    ) {
        val definition = record.index.definition
        connection.prepareStatement(dialect.insertDefinitionRootSql()).use { statement ->
            statement.setString(1, definition.definitionId)
            statement.setString(2, definition.tenantId)
            statement.setString(3, definition.key)
            statement.setString(4, definition.title)
            statement.setString(5, definition.status.code)
            statement.setString(6, versionRowId(definition.definitionId, definition.ref))
            statement.setLong(7, now)
            statement.setLong(8, now)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "SELECT definition_key FROM fw_wf_definition WHERE tenant_id = ? AND id = ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, definition.tenantId)
            statement.setString(2, definition.definitionId)
            statement.executeQuery().use { result ->
                check(result.next() && result.getString(1) == definition.key) {
                    "Workflow definition identifier is already bound to another key."
                }
            }
        }
    }

    private fun insertDefinitionVersion(
        connection: Connection,
        dialect: WorkflowJdbcDialect,
        record: WorkflowRuntimeDefinitionRecord,
        now: Long,
    ): Boolean {
        val definition = record.index.definition
        val receipt = record.executionReceipt
        connection.prepareStatement(dialect.insertDefinitionSql()).use { statement ->
            statement.setString(1, versionRowId(definition.definitionId, definition.ref))
            statement.setString(2, definition.tenantId)
            statement.setString(3, definition.definitionId)
            statement.setString(4, definition.ref.key)
            statement.setString(5, definition.ref.version)
            statement.setString(6, definition.ref.digest)
            statement.setInt(7, definition.schemaVersion)
            statement.setString(8, definition.status.code)
            statement.setBytes(9, WorkflowJdbcBinaryCodec.encodeDefinition(definition))
            statement.setString(10, receipt.receiptId)
            statement.setString(11, receipt.capabilityDigest)
            statement.setLong(12, receipt.acceptedAt)
            statement.setLong(13, receipt.validUntil)
            statement.setString(14, receipt.receiptDigest)
            statement.setLong(15, now)
            statement.setLong(16, now)
            return statement.executeUpdate() == 1
        }
    }

    private fun loadVersionForUpdate(
        connection: Connection,
        tenantId: String,
        definitionId: String,
        ref: WorkflowDefinitionRef,
    ): WorkflowRuntimeDefinitionRecord? = loadVersion(connection, tenantId, definitionId, ref, true)

    private fun loadVersion(
        connection: Connection,
        tenantId: String,
        definitionId: String,
        ref: WorkflowDefinitionRef,
        forUpdate: Boolean,
    ): WorkflowRuntimeDefinitionRecord? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        connection.prepareStatement(
            """
            SELECT definition_payload, schema_version, execution_receipt_id, capability_digest,
                   receipt_accepted_time, receipt_valid_until, receipt_digest
            FROM fw_wf_definition_version
            WHERE tenant_id = ? AND definition_id = ? AND definition_key = ?
              AND definition_version = ? AND definition_digest = ?$lock
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, definitionId)
            statement.setString(3, ref.key)
            statement.setString(4, ref.version)
            statement.setString(5, ref.digest)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                val definition = WorkflowJdbcBinaryCodec.decodeDefinition(result.getBytes("definition_payload"))
                check(definition.tenantId == tenantId && definition.definitionId == definitionId &&
                    definition.ref == ref && definition.contentDigest == ref.digest
                ) { "Persisted workflow definition payload does not match its indexed identity." }
                val receipt = WorkflowDefinitionExecutionReceipt.of(
                    result.getString("execution_receipt_id"),
                    tenantId,
                    definitionId,
                    ref,
                    result.getInt("schema_version"),
                    result.getString("capability_digest"),
                    result.getLong("receipt_accepted_time"),
                    result.getLong("receipt_valid_until"),
                )
                check(receipt.receiptDigest == result.getString("receipt_digest")) {
                    "Persisted workflow execution receipt digest is inconsistent."
                }
                return WorkflowRuntimeDefinitionRecord.of(WorkflowDefinitionIndex.compile(definition), receipt)
            }
        }
    }

    private fun versionRowId(definitionId: String, ref: WorkflowDefinitionRef): String =
        MessageDigest.getInstance("SHA-256").run {
            listOf("workflow-definition-version", definitionId, ref.version, ref.digest).forEach { value ->
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                update(byteArrayOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte(),
                ))
                update(bytes)
            }
            digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
}
