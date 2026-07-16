package ai.icen.fw.persistence.jdbc.dialect

import ai.icen.fw.persistence.database.MySqlDatabaseSupport
import java.sql.Connection
import javax.sql.DataSource

/**
 * Resolves a [SqlDialect] from JDBC metadata.
 */
object SqlDialects {
    private val dialects = listOf(PostgreSqlDialect, MySqlDialect, KingbaseDialect)

    @JvmStatic
    fun detect(connection: Connection): SqlDialect {
        val productName = connection.metaData.databaseProductName
        val dialect = dialects.find { dialect ->
            productName.equals(dialect.productName, ignoreCase = true) ||
                (dialect === KingbaseDialect && productName.startsWith("Kingbase", ignoreCase = true))
        }
            ?: error(
                "Unsupported database product '$productName'; " +
                    "FlowWeft supports ${dialects.joinToString { it.productName }}",
            )
        if (dialect === MySqlDialect) {
            MySqlDatabaseSupport.requireSupported(connection.metaData)
        }
        return dialect
    }

    @JvmStatic
    fun detect(dataSource: DataSource): SqlDialect =
        dataSource.connection.use { connection -> detect(connection) }
}
