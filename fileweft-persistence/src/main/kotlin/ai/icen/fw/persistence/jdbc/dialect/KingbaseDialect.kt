package ai.icen.fw.persistence.jdbc.dialect

import java.sql.PreparedStatement

/**
 * Kingbase ES V8R6 dialect.
 *
 * Kingbase is PostgreSQL-compatible for most FlowWeft DML. Differences are
 * handled by overriding the specific features it does not support (e.g. INCLUDE
 * indexes are DDL-only and do not affect DML).
 */
object KingbaseDialect : SqlDialect {
    override val productName: String = "Kingbase"

    override fun currentSchemaExpression(): String = "current_schema()"

    override fun jsonParameterBinding(parameter: String): String = "$parameter::jsonb"

    override fun jsonCast(expression: String): String = "$expression::jsonb"

    override fun jsonExtractText(column: String, path: String): String = "$column ->> '$path'"

    override fun jsonType(): String = "jsonb"

    override fun upsertClause(conflictColumns: List<String>, updateAssignments: List<String>): String =
        PostgreSqlDialect.upsertClause(conflictColumns, updateAssignments)

    override fun excludedColumnReference(column: String): String = "EXCLUDED.$column"

    override fun returningClause(columns: List<String>): String =
        PostgreSqlDialect.returningClause(columns)

    override fun forUpdateSkipLocked(): String = "FOR UPDATE SKIP LOCKED"

    override fun forUpdate(): String = "FOR UPDATE"

    override fun isDistinctFrom(column: String, parameter: String): String =
        PostgreSqlDialect.isDistinctFrom(column, parameter)

    override fun isNotDistinctFrom(column: String, parameter: String): String =
        PostgreSqlDialect.isNotDistinctFrom(column, parameter)

    override fun arrayContainsAny(column: String, parameter: String): String =
        PostgreSqlDialect.arrayContainsAny(column, parameter)

    override fun setStringArrayParameter(statement: PreparedStatement, index: Int, values: Collection<String>) =
        PostgreSqlDialect.setStringArrayParameter(statement, index, values)

    override fun createStringArrayParameter(connection: java.sql.Connection, values: Collection<String>): Any =
        PostgreSqlDialect.createStringArrayParameter(connection, values)

    override fun nullsLastOrderBy(expression: String, descending: Boolean): String =
        PostgreSqlDialect.nullsLastOrderBy(expression, descending)

    override fun trim(expression: String): String = PostgreSqlDialect.trim(expression)

    override fun jsonToText(column: String): String = PostgreSqlDialect.jsonToText(column)

    override fun maxIdentifierLength(): Int = 63
}
