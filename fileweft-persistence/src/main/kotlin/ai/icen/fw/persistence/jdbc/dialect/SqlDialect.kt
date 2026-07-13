package ai.icen.fw.persistence.jdbc.dialect

import java.sql.PreparedStatement

/**
 * Database-specific SQL fragments used by FileWeft JDBC repositories.
 *
 * The interface is intentionally small and focused on the PostgreSQL/MySQL/Kingbase
 * differences actually present in FileWeft DML. It lives inside the persistence
 * module; higher layers never see it.
 */
interface SqlDialect {
    /** Product name used to select this dialect. */
    val productName: String

    /** SQL expression that returns the current schema/database name. */
    fun currentSchemaExpression(): String

    /**
     * Binds a JSON parameter for use in an INSERT/UPDATE statement.
     * PostgreSQL/Kingbase use `?::jsonb`; MySQL uses `CAST(? AS JSON)`.
     */
    fun jsonParameterBinding(parameter: String = "?"): String

    /**
     * Casts an expression to the dialect's JSON type.
     * PostgreSQL/Kingbase: `expr::jsonb`; MySQL: `CAST(expr AS JSON)`.
     */
    fun jsonCast(expression: String): String

    /**
     * Extracts a JSON text value from a JSON column.
     * PostgreSQL/Kingbase: `column ->> 'path'`; MySQL: `JSON_UNQUOTE(JSON_EXTRACT(column, '$.path'))`.
     */
    fun jsonExtractText(column: String, path: String): String

    /**
     * Returns the data type name for JSON columns.
     * PostgreSQL/Kingbase: `jsonb`; MySQL: `json`.
     */
    fun jsonType(): String

    /**
     * Returns the upsert conflict resolution clause for the given unique columns.
     *
     * PostgreSQL/Kingbase: `ON CONFLICT (col1, col2) DO UPDATE SET ...`.
     * MySQL: `ON DUPLICATE KEY UPDATE ...` (no conflict target).
     *
     * The [updateAssignments] list must already be dialect-neutral `col = EXCLUDED.col`
     * style; the dialect rewrites `EXCLUDED.col` to `VALUES(col)` for MySQL.
     */
    fun upsertClause(conflictColumns: List<String>, updateAssignments: List<String>): String

    /**
     * Returns the string used in the SET clause of an upsert to refer to the
     * proposed/excluded value of a column.
     * PostgreSQL/Kingbase: `EXCLUDED.column`; MySQL: `VALUES(column)`.
     */
    fun excludedColumnReference(column: String): String

    /**
     * Returns a `RETURNING` clause fragment or an empty string if unsupported.
     */
    fun returningClause(columns: List<String>): String

    /**
     * Row-level locking clause.
     * PostgreSQL, Kingbase, and MySQL 8 support `FOR UPDATE SKIP LOCKED`.
     */
    fun forUpdateSkipLocked(): String

    /**
     * Plain row-level locking clause.
     */
    fun forUpdate(): String

    /**
     * Table expression used by ordered worker claims. MySQL needs an explicit
     * index hint so the optimizer cannot fall back to a filesort that locks
     * every eligible row before `SKIP LOCKED` is applied.
     */
    fun claimCandidateTable(table: String, orderedIndex: String): String = table

    /**
     * SQL boolean expression for "column IS DISTINCT FROM parameter".
     * PostgreSQL/Kingbase support `IS DISTINCT FROM`; MySQL uses `<=>` (NULL-safe
     * equals) negated: `NOT (column <=> ?)`.
     */
    fun isDistinctFrom(column: String, parameter: String = "?"): String

    /**
     * SQL expression that tests whether a value is NULL.
     */
    fun isNull(expression: String): String = "$expression IS NULL"

    /**
     * SQL expression that tests whether a value is not NULL.
     */
    fun isNotNull(expression: String): String = "$expression IS NOT NULL"

    /**
     * Returns the dialect-specific array literal syntax for a JDBC array parameter.
     * PostgreSQL/Kingbase bind a `text[]` with `connection.createArrayOf("text", ...)`;
     * MySQL has no text array type, so values are passed as a JSON array string and
     * compared with `JSON_CONTAINS` or `JSON_OVERLAPS`.
     */
    fun arrayContainsAny(column: String, parameter: String = "?"): String

    /**
     * Limit clause for pagination.
     * PostgreSQL/Kingbase: `LIMIT ?`; MySQL: `LIMIT ?`.
     */
    fun limitClause(): String = "LIMIT ?"

    /**
     * Binds a collection of string values as a dialect-appropriate parameter.
     * PostgreSQL/Kingbase create a JDBC `text[]` array; MySQL writes a JSON
     * array string for use with [arrayContainsAny].
     */
    fun setStringArrayParameter(statement: PreparedStatement, index: Int, values: Collection<String>)

    /**
     * Creates a dialect-appropriate array parameter value.
     * PostgreSQL/Kingbase return a JDBC `text[]` array; MySQL returns a JSON
     * array string. Callers must bind the returned value with the matching
     * setter ([java.sql.PreparedStatement.setArray] or [java.sql.PreparedStatement.setString]).
     */
    fun createStringArrayParameter(connection: java.sql.Connection, values: Collection<String>): Any

    /**
     * Returns an ORDER BY expression that sorts NULLs after non-null values.
     * PostgreSQL/Kingbase: `expr DESC NULLS LAST`; MySQL: `(expr IS NULL), expr DESC`.
     */
    fun nullsLastOrderBy(expression: String, descending: Boolean = true): String

    /**
     * Returns the dialect-specific string trimming function.
     * PostgreSQL/Kingbase: `btrim(expr)`; MySQL: `TRIM(expr)`.
     */
    fun trim(expression: String): String

    /**
     * Casts a JSON column to a text string in a dialect-safe way.
     * PostgreSQL/Kingbase: `column::text`; MySQL: `CAST(column AS CHAR)`.
     */
    fun jsonToText(column: String): String

    /**
     * SQL boolean expression for "column IS NOT DISTINCT FROM parameter".
     * PostgreSQL/Kingbase: `column IS NOT DISTINCT FROM parameter`; MySQL: `column <=> parameter`.
     */
    fun isNotDistinctFrom(column: String, parameter: String = "?"): String

    /**
     * Maximum length of a schema/database identifier in bytes or characters.
     */
    fun maxIdentifierLength(): Int
}
