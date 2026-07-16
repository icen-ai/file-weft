package ai.icen.fw.governance.persistence.jdbc

import java.sql.Connection

enum class GovernanceJdbcDialect {
    POSTGRESQL,
    MYSQL,
    KINGBASE;

    internal fun claimReadySql(): String = """
        SELECT id, tenant_id, record_id, record_id_digest, plan_id, plan_id_digest, event_type,
               run_version, state_digest, record_digest, memento_version,
               run_memento, run_memento_digest,
               OCTET_LENGTH(run_memento) AS run_memento_size, available_time, fencing_token
        FROM fw_governance_deletion_outbox
        WHERE tenant_id = ? AND acknowledged_time IS NULL AND available_time <= ?
          AND (lease_expires_time IS NULL OR lease_expires_time <= ?)
        ORDER BY available_time, id
        LIMIT ? FOR UPDATE SKIP LOCKED
    """.trimIndent()

    companion object {
        @JvmStatic
        fun detect(connection: Connection): GovernanceJdbcDialect {
            val metadata = connection.metaData
            val product = metadata.databaseProductName
            return when {
                product.equals("PostgreSQL", ignoreCase = true) -> POSTGRESQL
                product.equals("MySQL", ignoreCase = true) -> {
                    check(metadata.databaseMajorVersion >= 8) {
                        "FlowWeft Governance persistence requires MySQL 8 or newer."
                    }
                    MYSQL
                }
                product.startsWith("Kingbase", ignoreCase = true) -> KINGBASE
                else -> throw IllegalStateException(
                    "Unsupported Governance database; expected PostgreSQL, MySQL 8 or KingbaseES.",
                )
            }
        }
    }
}

enum class GovernanceJdbcMigrationDialect(val location: String, val resourcePath: String) {
    POSTGRESQL(
        "classpath:ai/icen/fw/workflow/db/migration/postgres",
        "/ai/icen/fw/workflow/db/migration/postgres/V041__persist_governance_runtime.sql",
    ),
    MYSQL(
        "classpath:ai/icen/fw/workflow/db/migration/mysql",
        "/ai/icen/fw/workflow/db/migration/mysql/V041__persist_governance_runtime.sql",
    ),
    KINGBASE(
        "classpath:ai/icen/fw/workflow/db/migration/kingbase",
        "/ai/icen/fw/workflow/db/migration/kingbase/V041__persist_governance_runtime.sql",
    ),
}

class GovernanceJdbcMigrations private constructor() {
    companion object {
        @JvmStatic
        fun location(dialect: GovernanceJdbcMigrationDialect): String = dialect.location

        @JvmStatic
        fun resourcePath(dialect: GovernanceJdbcMigrationDialect): String = dialect.resourcePath
    }
}
