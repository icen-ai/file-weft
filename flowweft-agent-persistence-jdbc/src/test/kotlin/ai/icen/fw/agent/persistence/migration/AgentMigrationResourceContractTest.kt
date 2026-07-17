package ai.icen.fw.agent.persistence.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentMigrationResourceContractTest {
    @Test
    fun `V030 owns generic runtime and V031 atomically adds evaluation in every dialect`() {
        val inventories = listOf("postgres", "mysql", "kingbase").associateWith { dialect ->
            val generic = resource(dialect, "V030__create_agent_durable_runtime.sql")
            val evaluation = resource(dialect, "V031__create_agent_evaluation_runtime.sql")
            assertTrue(generic.startsWith("-- FlowWeft Agent generic durable runtime V030."))
            assertTrue(evaluation.startsWith("-- FlowWeft Agent evaluation persistence V031."))
            listOf(generic, evaluation).forEach { sql ->
                assertFalse(sql.contains("CREATE TABLE fw_document"))
                assertFalse(sql.contains("CREATE TABLE fw_wf_"))
                assertFalse(sql.contains("fileweft_schema_history"))
                assertFalse(sql.contains("flowweft_workflow_schema_history"))
                assertFalse(sql.contains("fixture_payload"))
                assertFalse(sql.contains("raw_output"))
                assertFalse(sql.contains("provider_output"))
            }
            if (dialect == "mysql") {
                assertTrue(generic.contains("DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"))
                assertTrue(evaluation.contains("tenant_id varchar(256)"))
                assertTrue(evaluation.contains("DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"))
                assertFalse(evaluation.contains("varbinary"))
            }
            Inventory(tables(generic), tables(evaluation))
        }

        assertEquals(inventories.getValue("postgres"), inventories.getValue("mysql"))
        assertEquals(inventories.getValue("postgres"), inventories.getValue("kingbase"))
        assertEquals(
            setOf("fw_agent_run", "fw_agent_idempotency", "fw_agent_event", "fw_agent_operation"),
            inventories.getValue("postgres").generic,
        )
        assertEquals(
            setOf("fw_agent_evaluation_run", "fw_agent_evaluation_idempotency"),
            inventories.getValue("postgres").evaluation,
        )
        assertEquals("flowweft_agent_schema_history", AgentFlywayMigrationRunner.HISTORY_TABLE)
        assertEquals("29", AgentFlywayMigrationRunner.BASELINE_VERSION)
        assertEquals(30, AgentFlywayMigrationRunner.FIRST_AGENT_VERSION)
        assertEquals(
            "classpath:ai/icen/fw/agent/db/migration/postgres",
            AgentFlywayMigrationRunner.location(AgentFlywayMigrationRunner.DatabaseProduct.POSTGRESQL),
        )
    }

    private fun resource(dialect: String, name: String): String {
        val path = "/ai/icen/fw/agent/db/migration/$dialect/$name"
        return requireNotNull(javaClass.getResource(path)).readText(Charsets.UTF_8)
    }

    private fun tables(sql: String): Set<String> = TABLE.findAll(sql)
        .map { match -> match.groupValues[1].lowercase() }
        .toSet()

    private data class Inventory(val generic: Set<String>, val evaluation: Set<String>)

    private companion object {
        val TABLE = Regex("CREATE TABLE\\s+(fw_agent_[a-z_]+)", RegexOption.IGNORE_CASE)
    }
}
