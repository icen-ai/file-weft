package ai.icen.fw.persistence.jdbc.dialect

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
        return dialects.find { productName.equals(it.productName, ignoreCase = true) }
            ?: error(
                "Unsupported database product '$productName'; " +
                    "FileWeft supports ${dialects.joinToString { it.productName }}",
            )
    }

    @JvmStatic
    fun detect(dataSource: DataSource): SqlDialect =
        dataSource.connection.use { connection -> detect(connection) }
}
