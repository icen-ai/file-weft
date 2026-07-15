package ai.icen.fw.workflow.persistence.jdbc

import java.sql.Connection

/** SQL differences that cannot be expressed portably by JDBC. */
enum class WorkflowJdbcDialect {
    POSTGRESQL,
    MYSQL,
    KINGBASE;

    internal fun insertInstanceSql(): String = when (this) {
        MYSQL -> INSERT_INSTANCE_PREFIX + " ON DUPLICATE KEY UPDATE id = id"
        POSTGRESQL,
        KINGBASE -> INSERT_INSTANCE_PREFIX + " ON CONFLICT (tenant_id, id) DO NOTHING"
    }

    internal fun insertDefinitionSql(): String = when (this) {
        MYSQL -> INSERT_DEFINITION_PREFIX + " ON DUPLICATE KEY UPDATE id = id"
        POSTGRESQL,
        KINGBASE -> INSERT_DEFINITION_PREFIX + " ON CONFLICT (tenant_id, id) DO NOTHING"
    }

    internal fun insertDefinitionRootSql(): String = when (this) {
        MYSQL -> INSERT_DEFINITION_ROOT_PREFIX + " ON DUPLICATE KEY UPDATE id = id"
        POSTGRESQL,
        KINGBASE -> INSERT_DEFINITION_ROOT_PREFIX + " ON CONFLICT (tenant_id, id) DO NOTHING"
    }

    companion object {
        @JvmStatic
        fun detect(connection: Connection): WorkflowJdbcDialect {
            val product = connection.metaData.databaseProductName
            return when {
                product.equals("PostgreSQL", ignoreCase = true) -> POSTGRESQL
                product.equals("MySQL", ignoreCase = true) -> MYSQL
                product.startsWith("Kingbase", ignoreCase = true) -> KINGBASE
                else -> throw IllegalStateException(
                    "Unsupported workflow database '$product'; supported products are PostgreSQL, MySQL 8 and KingbaseES.",
                )
            }
        }

        private const val INSERT_INSTANCE_PREFIX = """
            INSERT INTO fw_wf_instance(
                id, tenant_id, definition_id, definition_key, definition_version, definition_digest,
                subject_type, subject_id, subject_revision, subject_digest,
                initiator_type, initiator_id, status, instance_version, state_digest, state_payload,
                created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        private const val INSERT_DEFINITION_PREFIX = """
            INSERT INTO fw_wf_definition_version(
                id, tenant_id, definition_id, definition_key, definition_version,
                definition_digest, schema_version, definition_status, definition_payload,
                execution_receipt_id, capability_digest, receipt_accepted_time,
                receipt_valid_until, receipt_digest, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        private const val INSERT_DEFINITION_ROOT_PREFIX = """
            INSERT INTO fw_wf_definition(
                id, tenant_id, definition_key, title, lifecycle_status, latest_version_id,
                created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """
    }
}
