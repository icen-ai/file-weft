package ai.icen.fw.persistence.jdbc.dialect

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
}
