package ai.icen.fw.persistence.jdbc.dialect

import java.sql.PreparedStatement

/**
 * MySQL 8.0.17+ (8.x) dialect.
 */
object MySqlDialect : SqlDialect {
    override val productName: String = "MySQL"

    override fun currentSchemaExpression(): String = "DATABASE()"

    override fun jsonParameterBinding(parameter: String): String = "CAST($parameter AS JSON)"

    override fun jsonCast(expression: String): String = "CAST($expression AS JSON)"

    override fun jsonExtractText(column: String, path: String): String {
        val escapedPath = path.replace("\\", "\\\\").replace("\"", "\\\"")
        return "JSON_UNQUOTE(JSON_EXTRACT($column, '\$.\"$escapedPath\"'))"
    }

    override fun jsonType(): String = "json"

    override fun upsertClause(conflictColumns: List<String>, updateAssignments: List<String>): String {
        // MySQL does not support a conflict target; ON DUPLICATE KEY UPDATE
        // applies to any unique constraint violation. We rewrite EXCLUDED.col
        // references to VALUES(col).
        if (updateAssignments.isEmpty()) {
            val noOpColumn = conflictColumns.firstOrNull()
                ?: error("A no-op MySQL upsert requires at least one conflict column.")
            return "ON DUPLICATE KEY UPDATE $noOpColumn = $noOpColumn"
        }
        val assignments = updateAssignments.joinToString(", ") { assignment ->
            assignment.replace(Regex("""EXCLUDED\.([A-Za-z_][A-Za-z0-9_]*)""")) { matchResult ->
                "VALUES(${matchResult.groupValues[1]})"
            }
        }
        return "ON DUPLICATE KEY UPDATE $assignments"
    }

    override fun excludedColumnReference(column: String): String = "VALUES($column)"

    override fun returningClause(columns: List<String>): String = ""

    override fun forUpdateSkipLocked(): String = "FOR UPDATE SKIP LOCKED"

    override fun forUpdate(): String = "FOR UPDATE"

    override fun claimCandidateTable(table: String, orderedIndex: String): String =
        "$table FORCE INDEX ($orderedIndex)"

    override fun isDistinctFrom(column: String, parameter: String): String = "NOT ($column <=> $parameter)"

    override fun isNotDistinctFrom(column: String, parameter: String): String = "$column <=> $parameter"

    override fun arrayContainsAny(column: String, parameter: String): String =
        "JSON_CONTAINS(CAST($parameter AS JSON), JSON_QUOTE($column))"

    override fun setStringArrayParameter(statement: PreparedStatement, index: Int, values: Collection<String>) {
        statement.setString(index, createStringArrayParameter(statement.connection, values) as String)
    }

    override fun createStringArrayParameter(connection: java.sql.Connection, values: Collection<String>): Any {
        return values.joinToString(",", "[", "]") { value ->
            '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"'
        }
    }

    override fun nullsLastOrderBy(expression: String, descending: Boolean): String =
        "($expression IS NULL), $expression ${if (descending) "DESC" else "ASC"}"

    override fun trim(expression: String): String = "TRIM($expression)"

    override fun jsonToText(column: String): String = "CAST($column AS CHAR)"

    override fun maxIdentifierLength(): Int = 64
}
