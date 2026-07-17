package ai.icen.fw.agent.persistence.jdbc

import ai.icen.fw.agent.api.AgentRunStatus
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.runtime.AgentRunCreateStatus
import ai.icen.fw.agent.runtime.AgentRunKey
import ai.icen.fw.agent.runtime.AgentRunLeaseClaim
import ai.icen.fw.agent.runtime.AgentRunLeaseClaimStatus
import ai.icen.fw.agent.runtime.AgentStoreCommitStatus
import ai.icen.fw.core.id.Identifier
import org.h2.jdbcx.JdbcDataSource
import org.h2.tools.RunScript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class JdbcAgentDurableRunStoreH2Test {
    @Test
    fun `create binds exact owner request and preserves unicode case tenant isolation`() {
        val dataSource = dataSource()
        val store = JdbcAgentDurableRunStore(dataSource, AgentJdbcDialect.POSTGRESQL)
        val initial = AgentDurableJdbcTestFixture.initial()

        assertSame(AgentRunCreateStatus.CREATED, store.create(initial).status)
        val replay = store.create(AgentDurableJdbcTestFixture.initial(runId = "run-replay"))
        assertSame(AgentRunCreateStatus.REPLAYED, replay.status)
        assertEquals(initial.state.runId, replay.state.runId)

        assertThrows(IllegalArgumentException::class.java) {
            store.create(
                AgentDurableJdbcTestFixture.initial(
                    runId = "run-mutated",
                    requestVariant = "mutated",
                ),
            )
        }

        val caseDistinct = AgentDurableJdbcTestFixture.initial(
            runId = "run-case-distinct",
            tenantId = "租户-天津-a",
        )
        assertSame(AgentRunCreateStatus.CREATED, store.create(caseDistinct).status)
        assertNull(
            store.load(
                AgentRunKey(
                    Identifier("租户-天津-a"),
                    initial.state.runId,
                ),
            ),
        )
        assertEquals(caseDistinct.state.runId, store.load(caseDistinct.state.let {
            AgentRunKey(it.tenantId, it.runId)
        })?.runId)
        assertEquals(listOf(1L), store.events(key(initial), 0L, 10).map { event -> event.sequence })

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM fw_agent_run").use { result ->
                    assertTrue(result.next())
                    assertEquals(2, result.getInt(1))
                }
                statement.executeQuery("SELECT COUNT(*) FROM fw_agent_idempotency").use { result ->
                    assertTrue(result.next())
                    assertEquals(2, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `lease CAS ordered events and operation evidence remain durable after pending clears`() {
        val dataSource = dataSource()
        val store = JdbcAgentDurableRunStore(dataSource, AgentJdbcDialect.POSTGRESQL)
        val initial = AgentDurableJdbcTestFixture.initial()
        store.create(initial)

        assertSame(
            AgentRunLeaseClaimStatus.BUSY,
            store.claimLease(
                AgentRunLeaseClaim(
                    key(initial),
                    ProviderId("worker-old-clock"),
                    Identifier("lease-old-clock"),
                    99L,
                    100L,
                ),
            ).status,
        )
        val claimed = requireNotNull(
            store.claimLease(
                AgentRunLeaseClaim(
                    key(initial),
                    ProviderId("worker-1"),
                    Identifier("lease-1"),
                    110L,
                    500L,
                ),
            ).state,
        )
        assertEquals(1L, claimed.lease?.fencingToken)

        val runningCommit = AgentDurableJdbcTestFixture.running(claimed)
        val running = requireNotNull(store.commit(runningCommit).state)
        val checkpointCommit = AgentDurableJdbcTestFixture.checkpointModel(running)
        val checkpointed = requireNotNull(store.commit(checkpointCommit).state)
        assertEquals("operation-model-1", checkpointed.pendingOperation?.operationId?.value)
        assertEquals(checkpointed.stateVersion, store.load(key(initial))?.stateVersion)

        val completionCommit = AgentDurableJdbcTestFixture.completeModel(checkpointed)
        assertSame(AgentStoreCommitStatus.APPLIED, store.commit(completionCommit).status)
        assertEquals(AgentRunStatus.COMPLETED, store.load(key(initial))?.status)
        assertEquals(listOf(1L, 2L, 3L, 4L), store.events(key(initial), 0L, 10).map { it.sequence })
        assertTrue(store.recoverable(1_000L, 10).isEmpty())
        assertSame(AgentStoreCommitStatus.VERSION_CONFLICT, store.commit(completionCommit).status)

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT operation_outcome, operation_memento_digest, outcome_binding_digest, " +
                        "evidence_updated_time, updated_time " +
                        "FROM fw_agent_operation",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals("COMPLETED", result.getString("operation_outcome"))
                    assertEquals(64, result.getString("operation_memento_digest").length)
                    assertEquals(64, result.getString("outcome_binding_digest").length)
                    assertEquals(130L, result.getLong("evidence_updated_time"))
                    assertEquals(140L, result.getLong("updated_time"))
                }
            }
        }
    }

    @Test
    fun `state event and current operation projection corruption fail closed`() {
        val dataSource = dataSource()
        val store = JdbcAgentDurableRunStore(dataSource, AgentJdbcDialect.POSTGRESQL)
        val initial = AgentDurableJdbcTestFixture.initial()
        store.create(initial)
        val claimed = requireNotNull(
            store.claimLease(
                AgentRunLeaseClaim(
                    key(initial),
                    ProviderId("worker-1"),
                    Identifier("lease-1"),
                    110L,
                    500L,
                ),
            ).state,
        )
        val running = requireNotNull(store.commit(AgentDurableJdbcTestFixture.running(claimed)).state)
        store.commit(AgentDurableJdbcTestFixture.checkpointModel(running))

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                assertEquals(
                    1,
                    statement.executeUpdate(
                        "UPDATE fw_agent_operation SET operation_digest = '${"0".repeat(64)}'",
                    ),
                )
            }
        }
        assertThrows(IllegalStateException::class.java) { store.load(key(initial)) }
    }

    private fun key(commit: ai.icen.fw.agent.runtime.AgentRunCreateCommit): AgentRunKey =
        AgentRunKey(commit.state.tenantId, commit.state.runId)

    private fun dataSource(): JdbcDataSource {
        val dataSource = JdbcDataSource().apply {
            setURL(
                "jdbc:h2:mem:agent-runtime-${System.nanoTime()};" +
                    "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            )
            user = "sa"
            password = ""
        }
        dataSource.connection.use { connection ->
            val path = "/ai/icen/fw/agent/db/migration/postgres/V030__create_agent_durable_runtime.sql"
            InputStreamReader(requireNotNull(javaClass.getResourceAsStream(path)), StandardCharsets.UTF_8).use { reader ->
                RunScript.execute(connection, reader)
            }
        }
        return dataSource
    }
}
