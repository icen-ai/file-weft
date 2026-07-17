package ai.icen.fw.workflow.sla.persistence.jdbc

import java.sql.Connection

/** SQL dialects covered by the Workflow SLA persistence contract. */
enum class WorkflowSlaJdbcDialect {
    POSTGRESQL,
    MYSQL,
    KINGBASE;

    companion object {
        @JvmStatic
        fun detect(connection: Connection): WorkflowSlaJdbcDialect {
            val product = connection.metaData.databaseProductName
            return when {
                product.equals("PostgreSQL", ignoreCase = true) -> POSTGRESQL
                product.equals("MySQL", ignoreCase = true) -> {
                    check(connection.metaData.databaseMajorVersion >= 8) {
                        "Workflow SLA persistence requires MySQL 8 or newer."
                    }
                    MYSQL
                }
                product.startsWith("Kingbase", ignoreCase = true) -> KINGBASE
                else -> throw IllegalStateException(
                    "Unsupported Workflow SLA database; supported products are PostgreSQL, MySQL 8 and KingbaseES.",
                )
            }
        }
    }
}

/** Owned V038 migration locations; hosts add exactly one location to the Workflow Flyway line. */
enum class WorkflowSlaJdbcMigrationDialect(val location: String, val resourcePath: String) {
    POSTGRESQL(
        "classpath:ai/icen/fw/workflow/sla/db/migration/postgres",
        "/ai/icen/fw/workflow/sla/db/migration/postgres/V038__persist_workflow_sla.sql",
    ),
    MYSQL(
        "classpath:ai/icen/fw/workflow/sla/db/migration/mysql",
        "/ai/icen/fw/workflow/sla/db/migration/mysql/V038__persist_workflow_sla.sql",
    ),
    KINGBASE(
        "classpath:ai/icen/fw/workflow/sla/db/migration/kingbase",
        "/ai/icen/fw/workflow/sla/db/migration/kingbase/V038__persist_workflow_sla.sql",
    ),
}

class WorkflowSlaJdbcMigrations private constructor() {
    companion object {
        @JvmStatic
        fun location(dialect: WorkflowSlaJdbcMigrationDialect): String = dialect.location

        @JvmStatic
        fun resourcePath(dialect: WorkflowSlaJdbcMigrationDialect): String = dialect.resourcePath
    }
}
