package ai.icen.fw.agent.persistence.jdbc

import java.sql.Connection

/** SQL differences that cannot be expressed portably through JDBC. */
enum class AgentJdbcDialect {
    POSTGRESQL,
    MYSQL,
    KINGBASE;

    companion object {
        @JvmStatic
        fun detect(connection: Connection): AgentJdbcDialect {
            val product = connection.metaData.databaseProductName
            return when {
                product.equals("PostgreSQL", ignoreCase = true) -> POSTGRESQL
                product.equals("MySQL", ignoreCase = true) -> {
                    AgentMySqlDatabaseSupport.requireSupported(connection.metaData)
                    MYSQL
                }
                product.startsWith("Kingbase", ignoreCase = true) -> KINGBASE
                else -> throw IllegalStateException(
                    "Unsupported Agent database '$product'; expected PostgreSQL, MySQL 8 or KingbaseES.",
                )
            }
        }
    }
}
