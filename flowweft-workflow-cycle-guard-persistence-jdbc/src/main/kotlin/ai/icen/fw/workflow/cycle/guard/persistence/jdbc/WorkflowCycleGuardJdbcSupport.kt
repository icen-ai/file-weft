package ai.icen.fw.workflow.cycle.guard.persistence.jdbc

import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardScope
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleBudgetPolicy
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource

internal class WorkflowCycleGuardJdbcTransactions(private val dataSource: DataSource) {
    fun <T> read(action: (Connection) -> T): T = dataSource.connection.use(action)

    fun <T> transaction(action: (Connection) -> T): T {
        val connection = dataSource.connection
        val originalAutoCommit = connection.autoCommit
        val originalReadOnly = connection.isReadOnly
        try {
            connection.autoCommit = false
            connection.isReadOnly = false
            val result = try {
                action(connection)
            } catch (failure: Throwable) {
                rollback(connection, failure)
                throw failure
            }
            // A commit exception is deliberately propagated so the caller can reconcile the receipt.
            connection.commit()
            return result
        } finally {
            try {
                connection.isReadOnly = originalReadOnly
            } catch (_: Throwable) {
                // The primary outcome has already been retained.
            }
            try {
                connection.autoCommit = originalAutoCommit
            } catch (_: Throwable) {
                // Connection close remains the cleanup boundary.
            }
            connection.close()
        }
    }

    private fun rollback(connection: Connection, primary: Throwable) {
        try {
            connection.rollback()
        } catch (rollbackFailure: Throwable) {
            primary.addSuppressed(rollbackFailure)
        }
    }
}

internal object WorkflowCycleGuardJdbcIds {
    fun aggregate(scope: WorkflowCycleGuardScope): String {
        val subject = scope.subject
        return digest(
            "flowweft-workflow-cycle-guard-aggregate-v1",
            scope.tenantId,
            scope.instanceId,
            scope.definitionId,
            scope.definitionRef.key,
            scope.definitionRef.version,
            scope.definitionRef.digest,
            scope.operation.code,
            subject?.ref?.type,
            subject?.ref?.id,
        )
    }

    fun receipt(tenantId: String, idempotencyKey: String): String = digest(
        "flowweft-workflow-cycle-guard-receipt-v1",
        tenantId,
        idempotencyKey,
    )

    fun policyBinding(policy: WorkflowCycleBudgetPolicy): String = policyBinding(
        policy.policyId,
        policy.policyVersion,
        policy.policyDigest,
        policy.authorityRevision,
        policy.maximumPerCycle,
        policy.maximumPerInstance,
    )

    fun policyBinding(
        policyId: String,
        policyVersion: String,
        policyDigest: String,
        policyAuthorityRevision: String,
        maximumPerCycle: Int,
        maximumPerInstance: Int,
    ): String = digest(
        "flowweft-workflow-cycle-guard-policy-binding-v1",
        policyId,
        policyVersion,
        policyDigest,
        policyAuthorityRevision,
        maximumPerCycle.toString(),
        maximumPerInstance.toString(),
    )

    private fun digest(domain: String, vararg values: String?): String {
        val hash = MessageDigest.getInstance("SHA-256")
        update(hash, domain)
        hash.update(ByteBuffer.allocate(4).putInt(values.size).array())
        values.forEach { value ->
            if (value == null) {
                hash.update(0.toByte())
            } else {
                hash.update(1.toByte())
                update(hash, value)
            }
        }
        return hash.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun update(hash: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        hash.update(bytes)
    }
}
