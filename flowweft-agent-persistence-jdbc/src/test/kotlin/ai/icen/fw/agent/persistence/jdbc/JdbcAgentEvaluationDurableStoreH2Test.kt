package ai.icen.fw.agent.persistence.jdbc

import ai.icen.fw.agent.api.AgentEvaluationDiagnostic
import ai.icen.fw.agent.api.AgentEvaluationDiagnosticReason
import ai.icen.fw.agent.api.AgentEvaluationDiagnosticStatus
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.runtime.AgentEvaluationCommitStatus
import ai.icen.fw.agent.runtime.AgentEvaluationLease
import ai.icen.fw.agent.runtime.AgentEvaluationLeaseClaim
import ai.icen.fw.agent.runtime.AgentEvaluationLeaseClaimStatus
import ai.icen.fw.agent.runtime.AgentEvaluationRunKey
import ai.icen.fw.agent.runtime.AgentEvaluationRunStatus
import ai.icen.fw.agent.runtime.AgentEvaluationStateCommit
import ai.icen.fw.core.id.Identifier
import org.h2.jdbcx.JdbcDataSource
import org.h2.tools.RunScript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class JdbcAgentEvaluationDurableStoreH2Test {

    @Test
    fun `create is atomic owner scoped idempotent and tenant isolated`() {
        val dataSource = dataSource()
        val store = JdbcAgentEvaluationDurableStore(dataSource, AgentJdbcDialect.POSTGRESQL)
        val initial = AgentEvaluationJdbcTestFixture.initial()

        val created = store.create(initial)
        val replayed = store.create(AgentEvaluationJdbcTestFixture.initial("evaluation-replay"))
        val otherTenant = AgentEvaluationJdbcTestFixture.initial("evaluation-other", "租户-天津")

        assertTrue(created.created)
        assertFalse(replayed.created)
        assertEquals(initial.evaluationId, replayed.state.evaluationId)
        assertThrows(IllegalArgumentException::class.java) {
            store.create(
                AgentEvaluationJdbcTestFixture.initial(
                    evaluationId = "evaluation-mutated",
                    deadlineAt = 900L,
                ),
            )
        }
        assertTrue(store.create(otherTenant).created)
        assertEquals(initial.evaluationId, store.findByIdempotency(initial.idempotencyScope)?.evaluationId)
        assertNull(store.load(AgentEvaluationRunKey(Identifier("租户-天津"), initial.evaluationId)))
        assertEquals(otherTenant.evaluationId, store.load(otherTenant.key())?.evaluationId)

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM fw_agent_evaluation_idempotency").use { result ->
                    assertTrue(result.next())
                    assertEquals(2, result.getInt(1))
                }
                statement.executeQuery(
                    "SELECT idempotency_key_digest FROM fw_agent_evaluation_idempotency " +
                        "WHERE tenant_id = 'tenant-1'",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals(64, result.getString(1).length)
                    assertFalse(result.getString(1).contains("idempotency-1"))
                }
            }
        }
    }

    @Test
    fun `claim recovery heartbeat completion and stale writers are fenced`() {
        val store = JdbcAgentEvaluationDurableStore(dataSource(), AgentJdbcDialect.POSTGRESQL)
        val initial = AgentEvaluationJdbcTestFixture.initial()
        store.create(initial)

        val first = requireNotNull(
            store.claim(
                AgentEvaluationLeaseClaim(
                    initial.key(),
                    ProviderId("worker-1"),
                    Identifier("lease-1"),
                    110L,
                    100L,
                ),
            ).state,
        )
        assertEquals(1L, first.lease?.fencingToken)
        assertTrue(store.recoverable(150L, 10).isEmpty())
        assertEquals(initial.evaluationId, store.recoverable(210L, 10).single().evaluationId)

        val recoveredResult = store.claim(
            AgentEvaluationLeaseClaim(
                initial.key(),
                ProviderId("worker-2"),
                Identifier("lease-2"),
                210L,
                100L,
            ),
        )
        assertSame(AgentEvaluationLeaseClaimStatus.ACQUIRED, recoveredResult.status)
        val recovered = requireNotNull(recoveredResult.state)
        assertEquals(2L, recovered.lease?.fencingToken)

        val recoveredLease = requireNotNull(recovered.lease)
        val renewedLease = AgentEvaluationLease(
            recoveredLease.leaseId,
            recoveredLease.ownerId,
            recoveredLease.fencingToken,
            recoveredLease.acquiredAt,
            360L,
        )
        val heartbeat = recovered.heartbeat(recoveredLease, renewedLease, 220L)
        val stale = store.heartbeat(
            AgentEvaluationStateCommit(
                initial.key(),
                recovered.stateVersion,
                requireNotNull(first.lease),
                220L,
                heartbeat,
            ),
        )
        assertSame(AgentEvaluationCommitStatus.LEASE_LOST, stale.status)

        val heartbeated = requireNotNull(
            store.heartbeat(
                AgentEvaluationStateCommit(initial.key(), recovered.stateVersion, recoveredLease, 220L, heartbeat),
            ).state,
        )
        val progressed = AgentEvaluationJdbcTestFixture.progressed(heartbeated, 230L)
        assertSame(
            AgentEvaluationCommitStatus.APPLIED,
            store.heartbeat(
                AgentEvaluationStateCommit(
                    initial.key(),
                    heartbeated.stateVersion,
                    requireNotNull(heartbeated.lease),
                    230L,
                    progressed,
                ),
            ).status,
        )
        val completed = AgentEvaluationJdbcTestFixture.completed(progressed, 240L)
        val completionCommit = AgentEvaluationStateCommit(
            initial.key(),
            progressed.stateVersion,
            requireNotNull(progressed.lease),
            240L,
            completed,
        )
        assertSame(AgentEvaluationCommitStatus.APPLIED, store.complete(completionCommit).status)
        assertEquals(AgentEvaluationRunStatus.COMPLETED, store.load(initial.key())?.status)
        assertTrue(store.recoverable(500L, 10).isEmpty())
        assertSame(AgentEvaluationCommitStatus.VERSION_CONFLICT, store.complete(completionCommit).status)
    }

    @Test
    fun `trusted cancellation preclaim expiry and persisted digest corruption fail safely`() {
        val dataSource = dataSource()
        val store = JdbcAgentEvaluationDurableStore(dataSource, AgentJdbcDialect.POSTGRESQL)
        val cancellable = AgentEvaluationJdbcTestFixture.initial("evaluation-cancel", idempotencyKey = "cancel-key")
        store.create(cancellable)
        val cancelled = cancellable.cancelled("operator.cancelled", 110L)
        assertSame(
            AgentEvaluationCommitStatus.APPLIED,
            store.cancel(AgentEvaluationStateCommit(cancellable.key(), 0L, null, 110L, cancelled)).status,
        )

        val expirable = AgentEvaluationJdbcTestFixture.initial("evaluation-expire", idempotencyKey = "expire-key")
        store.create(expirable)
        val expired = expirable.expiredBeforeClaim(
            AgentEvaluationDiagnostic(
                AgentEvaluationDiagnosticStatus.EXPIRED,
                AgentEvaluationDiagnosticReason("evaluation.deadline-exceeded"),
                expirable.providerSnapshot.providerId,
                null,
                expirable.providerSnapshot.snapshotDigest,
                1_000L,
            ),
            1_000L,
        )
        assertSame(
            AgentEvaluationCommitStatus.APPLIED,
            store.fail(AgentEvaluationStateCommit(expirable.key(), 0L, null, 1_000L, expired)).status,
        )

        val workerFailure = AgentEvaluationJdbcTestFixture.initial(
            "evaluation-worker-fail",
            idempotencyKey = "worker-fail-key",
        )
        store.create(workerFailure)
        val running = requireNotNull(
            store.claim(
                AgentEvaluationLeaseClaim(
                    workerFailure.key(),
                    ProviderId("worker-failure"),
                    Identifier("lease-failure"),
                    110L,
                    10L,
                ),
            ).state,
        )
        val failed = running.failed(
            requireNotNull(running.lease),
            AgentEvaluationDiagnostic(
                AgentEvaluationDiagnosticStatus.FAILED,
                AgentEvaluationDiagnosticReason("evaluation.provider-failed"),
                running.providerSnapshot.providerId,
                null,
                running.providerSnapshot.snapshotDigest,
                120L,
            ),
            120L,
            false,
        )
        assertSame(
            AgentEvaluationCommitStatus.APPLIED,
            store.fail(
                AgentEvaluationStateCommit(
                    running.key(),
                    running.stateVersion,
                    requireNotNull(running.lease),
                    120L,
                    failed,
                ),
            ).status,
        )

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE fw_agent_evaluation_run SET memento_digest = ? WHERE tenant_id = ? AND id = ?",
            ).use { statement ->
                statement.setString(1, "0".repeat(64))
                statement.setString(2, cancellable.tenantId.value)
                statement.setString(3, cancellable.evaluationId.value)
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertThrows(IllegalArgumentException::class.java) { store.load(cancellable.key()) }
    }

    private fun dataSource(): JdbcDataSource {
        val dataSource = JdbcDataSource().apply {
            setURL(
                "jdbc:h2:mem:agent-evaluation-${System.nanoTime()};" +
                    "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            )
            user = "sa"
            password = ""
        }
        dataSource.connection.use { connection ->
            val path = "/ai/icen/fw/agent/db/migration/postgres/V031__create_agent_evaluation_runtime.sql"
            InputStreamReader(requireNotNull(javaClass.getResourceAsStream(path)), StandardCharsets.UTF_8).use { reader ->
                RunScript.execute(connection, reader)
            }
        }
        return dataSource
    }
}
