package ai.icen.fw.workflow.notification.persistence.jdbc

import java.sql.Connection

/** SQL differences used by the standalone durable Workflow notification store. */
enum class WorkflowNotificationJdbcDialect {
    POSTGRESQL,
    MYSQL,
    KINGBASE;

    companion object {
        @JvmStatic
        fun detect(connection: Connection): WorkflowNotificationJdbcDialect {
            val product = connection.metaData.databaseProductName
            return when {
                product.equals("PostgreSQL", ignoreCase = true) -> POSTGRESQL
                product.equals("MySQL", ignoreCase = true) -> {
                    check(connection.metaData.databaseMajorVersion >= 8) {
                        "FlowWeft Workflow notifications require MySQL 8 or newer."
                    }
                    MYSQL
                }
                product.startsWith("Kingbase", ignoreCase = true) -> KINGBASE
                else -> throw IllegalStateException(
                    "Unsupported Workflow notification database '$product'; expected PostgreSQL, MySQL 8 or KingbaseES.",
                )
            }
        }
    }
}
