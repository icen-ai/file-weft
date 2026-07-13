package ai.icen.fw.persistence.jdbc.dialect

import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlDialectTest {
    @Test
    fun `PostgreSQL and Kingbase use conflict no-op for an empty update set`() {
        assertEquals(
            "ON CONFLICT (tenant_id, idempotency_key) DO NOTHING",
            PostgreSqlDialect.upsertClause(listOf("tenant_id", "idempotency_key"), emptyList()),
        )
        assertEquals(
            "ON CONFLICT (tenant_id, idempotency_key) DO NOTHING",
            KingbaseDialect.upsertClause(listOf("tenant_id", "idempotency_key"), emptyList()),
        )
    }

    @Test
    fun `MySQL uses a deterministic no-op assignment for an empty update set`() {
        assertEquals(
            "ON DUPLICATE KEY UPDATE tenant_id = tenant_id",
            MySqlDialect.upsertClause(listOf("tenant_id", "idempotency_key"), emptyList()),
        )
    }

    @Test
    fun `dialects retain real update assignments`() {
        val assignments = listOf("title = EXCLUDED.title")
        assertEquals(
            "ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title",
            PostgreSqlDialect.upsertClause(listOf("id"), assignments),
        )
        assertEquals(
            "ON DUPLICATE KEY UPDATE title = VALUES(title)",
            MySqlDialect.upsertClause(listOf("id"), assignments),
        )
    }

    @Test
    fun `MySQL 8 workers use skip locked`() {
        assertEquals("FOR UPDATE SKIP LOCKED", MySqlDialect.forUpdateSkipLocked())
        assertEquals(
            "fw_task FORCE INDEX (idx_fw_task_claim_order)",
            MySqlDialect.claimCandidateTable("fw_task", "idx_fw_task_claim_order"),
        )
        assertEquals(
            "fw_task",
            PostgreSqlDialect.claimCandidateTable("fw_task", "idx_fw_task_claim_order"),
        )
    }

    @Test
    fun `MySQL quotes JSON object keys and compares visibility as JSON`() {
        assertEquals(
            "JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '\$.\"catalog.folder-id\"'))",
            MySqlDialect.jsonExtractText("metadata_json", "catalog.folder-id"),
        )
        assertEquals(
            "JSON_CONTAINS(CAST(? AS JSON), JSON_QUOTE(folder_id))",
            MySqlDialect.arrayContainsAny("folder_id", "?"),
        )
    }

    @Test
    fun `KingbaseES product name resolves to the Kingbase dialect`() {
        assertEquals(KingbaseDialect, SqlDialects.detect(connectionWithProductName("KingbaseES")))
    }

    @Test
    fun `MySQL support fails closed below the required 8_0_17 baseline`() {
        assertFailsWith<IllegalStateException> {
            SqlDialects.detect(connectionWithProductName("MySQL", "8.0.16"))
        }
        assertEquals(MySqlDialect, SqlDialects.detect(connectionWithProductName("MySQL", "8.0.17")))
        assertEquals(MySqlDialect, SqlDialects.detect(connectionWithProductName("MySQL", "8.4.6")))
        assertFailsWith<IllegalStateException> {
            SqlDialects.detect(connectionWithProductName("MySQL", "9.0.0"))
        }
    }

    private fun connectionWithProductName(productName: String, productVersion: String = "12.1.0"): Connection {
        val metadata = Proxy.newProxyInstance(
            DatabaseMetaData::class.java.classLoader,
            arrayOf(DatabaseMetaData::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getDatabaseProductName" -> productName
                "getDatabaseProductVersion" -> productVersion
                else -> error("Unexpected DatabaseMetaData call: ${method.name}")
            }
        } as DatabaseMetaData
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getMetaData" -> metadata
                else -> error("Unexpected Connection call: ${method.name}")
            }
        } as Connection
    }
}
