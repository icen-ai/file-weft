package ai.icen.fw.capacity.persistence.jdbc

import java.sql.Connection
import java.sql.SQLException

enum class CapacityJdbcDialect {
    POSTGRESQL,
    MYSQL,
    KINGBASE;

    internal fun policySnapshotUpsertSql(): String = when (this) {
        POSTGRESQL, KINGBASE -> """
            INSERT INTO fw_capacity_policy_snapshot (
                id, tenant_id, provider_id, target_digest, workload_kind,
                resolution_digest, source_revision_digest, resolution_memento,
                observed_time, expires_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                provider_id = EXCLUDED.provider_id,
                target_digest = EXCLUDED.target_digest,
                workload_kind = EXCLUDED.workload_kind,
                resolution_digest = EXCLUDED.resolution_digest,
                source_revision_digest = EXCLUDED.source_revision_digest,
                resolution_memento = EXCLUDED.resolution_memento,
                observed_time = EXCLUDED.observed_time,
                expires_time = EXCLUDED.expires_time,
                updated_time = EXCLUDED.updated_time
        """.trimIndent()

        MYSQL -> """
            INSERT INTO fw_capacity_policy_snapshot (
                id, tenant_id, provider_id, target_digest, workload_kind,
                resolution_digest, source_revision_digest, resolution_memento,
                observed_time, expires_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                provider_id = VALUES(provider_id),
                target_digest = VALUES(target_digest),
                workload_kind = VALUES(workload_kind),
                resolution_digest = VALUES(resolution_digest),
                source_revision_digest = VALUES(source_revision_digest),
                resolution_memento = VALUES(resolution_memento),
                observed_time = VALUES(observed_time),
                expires_time = VALUES(expires_time),
                updated_time = VALUES(updated_time)
        """.trimIndent()
    }

    internal fun isUniqueViolation(failure: SQLException): Boolean =
        failure.sqlState?.startsWith("23") == true ||
            (this == MYSQL && failure.errorCode == 1062)

    companion object {
        @JvmStatic
        fun detect(connection: Connection): CapacityJdbcDialect {
            val metadata = connection.metaData
            val product = metadata.databaseProductName
            return when {
                product.equals("PostgreSQL", ignoreCase = true) -> POSTGRESQL
                product.equals("MySQL", ignoreCase = true) -> {
                    check(metadata.databaseMajorVersion >= 8) {
                        "FlowWeft Capacity persistence requires MySQL 8 or newer."
                    }
                    MYSQL
                }
                product.startsWith("Kingbase", ignoreCase = true) -> KINGBASE
                else -> throw IllegalStateException(
                    "Unsupported Capacity database; expected PostgreSQL, MySQL 8 or KingbaseES.",
                )
            }
        }
    }
}

enum class CapacityJdbcMigrationDialect(val location: String, val resourcePath: String) {
    POSTGRESQL(
        "classpath:ai/icen/fw/workflow/db/migration/postgres",
        "/ai/icen/fw/workflow/db/migration/postgres/V039__persist_capacity_runtime.sql",
    ),
    MYSQL(
        "classpath:ai/icen/fw/workflow/db/migration/mysql",
        "/ai/icen/fw/workflow/db/migration/mysql/V039__persist_capacity_runtime.sql",
    ),
    KINGBASE(
        "classpath:ai/icen/fw/workflow/db/migration/kingbase",
        "/ai/icen/fw/workflow/db/migration/kingbase/V039__persist_capacity_runtime.sql",
    ),
}

class CapacityJdbcMigrations private constructor() {
    companion object {
        @JvmStatic
        fun location(dialect: CapacityJdbcMigrationDialect): String = dialect.location

        @JvmStatic
        fun resourcePath(dialect: CapacityJdbcMigrationDialect): String = dialect.resourcePath
    }
}
