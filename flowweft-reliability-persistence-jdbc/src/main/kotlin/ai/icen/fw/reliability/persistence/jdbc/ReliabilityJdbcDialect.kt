package ai.icen.fw.reliability.persistence.jdbc

import java.sql.Connection
import java.sql.SQLException

enum class ReliabilityJdbcDialect {
    POSTGRESQL,
    MYSQL,
    KINGBASE;

    internal fun isUniqueViolation(failure: SQLException): Boolean = when (this) {
        POSTGRESQL, KINGBASE -> failure.sqlState == "23505"
        MYSQL -> failure.errorCode == 1062
    }

    internal fun epochMillisExpression(): String = when (this) {
        POSTGRESQL, KINGBASE -> "CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000 AS BIGINT)"
        MYSQL -> "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)"
    }

    companion object {
        @JvmStatic
        fun detect(connection: Connection): ReliabilityJdbcDialect {
            val metadata = connection.metaData
            val product = metadata.databaseProductName
            return when {
                product.startsWith("Kingbase", ignoreCase = true) -> KINGBASE
                product.equals("PostgreSQL", ignoreCase = true) -> POSTGRESQL
                product.equals("MySQL", ignoreCase = true) -> {
                    check(metadata.databaseMajorVersion >= 8) {
                        "FlowWeft Reliability persistence requires MySQL 8 or newer."
                    }
                    MYSQL
                }
                else -> throw IllegalStateException(
                    "Unsupported Reliability database; expected PostgreSQL, MySQL 8 or KingbaseES.",
                )
            }
        }
    }
}

enum class ReliabilityJdbcMigrationDialect(val location: String, val resourcePath: String) {
    POSTGRESQL(
        "classpath:ai/icen/fw/workflow/db/migration/postgres",
        "/ai/icen/fw/workflow/db/migration/postgres/V040__persist_reliability_runtime.sql",
    ),
    MYSQL(
        "classpath:ai/icen/fw/workflow/db/migration/mysql",
        "/ai/icen/fw/workflow/db/migration/mysql/V040__persist_reliability_runtime.sql",
    ),
    KINGBASE(
        "classpath:ai/icen/fw/workflow/db/migration/kingbase",
        "/ai/icen/fw/workflow/db/migration/kingbase/V040__persist_reliability_runtime.sql",
    ),
}

class ReliabilityJdbcMigrations private constructor() {
    companion object {
        @JvmStatic fun location(dialect: ReliabilityJdbcMigrationDialect): String = dialect.location
        @JvmStatic fun resourcePath(dialect: ReliabilityJdbcMigrationDialect): String = dialect.resourcePath
    }
}
