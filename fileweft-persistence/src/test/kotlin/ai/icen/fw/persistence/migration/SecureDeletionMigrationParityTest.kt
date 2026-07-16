package ai.icen.fw.persistence.migration

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecureDeletionMigrationParityTest {
    @Test
    fun `v036 exposes the same secure deletion schema and diagnostics in every dialect`() {
        val scripts = DIALECTS.associateWith(::migration)
        val requiredTokens = listOf(
            "fw_secure_deletion_plan",
            "fw_secure_deletion_tombstone",
            "fw_secure_deletion_audit",
            "fw_secure_deletion_receipt",
            "uq_fw_sec_del_plan_dispatch",
            "uq_fw_sec_del_tomb_resource",
            "idx_fw_sec_del_plan_status",
            "idx_fw_sec_del_plan_resource",
            "idx_fw_sec_del_audit_resource",
            "idx_fw_sec_del_receipt_status",
            "ck_fw_sec_del_plan_failure_shape",
            "PURGE_INDEX_PROJECTIONS",
            "PURGE_OBJECT_STORAGE",
            "VERIFIED_ABSENT",
            "ACCEPTED_UNVERIFIED",
            "request_binding_digest",
            "LEGAL_HOLD_EVIDENCE_EXPIRED",
            "RETENTION_POLICY_EVIDENCE_EXPIRED",
        )
        scripts.forEach { (dialect, script) ->
            requiredTokens.forEach { token ->
                assertTrue(token in script, "$dialect V036 is missing $token")
            }
            assertEquals(4, Regex("CREATE TABLE fw_secure_deletion_").findAll(script).count())
            assertEquals(3, Regex("PRIMARY KEY \\(tenant_id, id\\)").findAll(script).count())
        }
        assertTrue("jsonb" in scripts.getValue("postgres"))
        assertTrue("jsonb" in scripts.getValue("kingbase"))
        assertTrue("json          NOT NULL" in scripts.getValue("mysql"))
        assertEquals(
            4,
            Regex("ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin")
                .findAll(scripts.getValue("mysql"))
                .count(),
        )
    }

    private fun migration(dialect: String): String {
        val path = "ai/icen/fw/db/migration/$dialect/V036__create_secure_deletion.sql"
        return requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "Missing secure-deletion migration $path"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private companion object {
        val DIALECTS = listOf("postgres", "mysql", "kingbase")
    }
}
