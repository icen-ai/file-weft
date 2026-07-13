package ai.icen.fw.persistence.jdbc.dialect

import java.sql.PreparedStatement
import java.sql.Array as JdbcArray

/**
 * PostgreSQL 14+ dialect.
 */
object PostgreSqlDialect : SqlDialect {
    override val productName: String = "PostgreSQL"

    override fun currentSchemaExpression(): String = "current_schema()"

    override fun jsonParameterBinding(parameter: String): String = "$parameter::jsonb"

    override fun jsonCast(expression: String): String = "$expression::jsonb"

    override fun jsonExtractText(column: String, path: String): String = "$column ->> '$path'"

    override fun jsonType(): String = "jsonb"

    override fun upsertClause(conflictColumns: List<String>, updateAssignments: List<String>): String {
        val conflictTarget = conflictColumns.joinToString(", ")
        val assignments = updateAssignments.joinToString(", ")
        return "ON CONFLICT ($conflictTarget) DO UPDATE SET $assignments"
    }

    override fun excludedColumnReference(column: String): String = "EXCLUDED.$column"

    override fun returningClause(columns: List<String>): String = "RETURNING ${columns.joinToString(", ")}"

    override fun forUpdateSkipLocked(): String = "FOR UPDATE SKIP LOCKED"

    override fun forUpdate(): String = "FOR UPDATE"

    override fun isDistinctFrom(column: String, parameter: String): String = "$column IS DISTINCT FROM $parameter"

    override fun isNotDistinctFrom(column: String, parameter: String): String = "$column IS NOT DISTINCT FROM $parameter"

    override fun arrayContainsAny(column: String, parameter: String): String = "$column = ANY($parameter)"

    override fun setStringArrayParameter(statement: PreparedStatement, index: Int, values: Collection<String>) {
        val array = createStringArrayParameter(statement.connection, values)
        statement.setArray(index, array as JdbcArray)
    }

    override fun createStringArrayParameter(connection: java.sql.Connection, values: Collection<String>): Any =
        connection.createArrayOf("text", values.toTypedArray())

    override fun nullsLastOrderBy(expression: String, descending: Boolean): String =
        "$expression ${if (descending) "DESC" else "ASC"} NULLS LAST"

    override fun trim(expression: String): String = "btrim($expression)"

    override fun jsonToText(column: String): String = "$column::text"

    override fun maxIdentifierLength(): Int = 63
}
