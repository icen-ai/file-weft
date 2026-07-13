package ai.icen.fw.persistence.migration

import ai.icen.fw.persistence.database.MySqlDatabaseSupport
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Runs only FileWeft-owned migrations and records them in a FileWeft-owned
 * schema-history table.
 *
 * [schema] is optional for compatibility with the original single-argument
 * constructor. When it is omitted, the current schema reported by the
 * [DataSource] is used. Supplying it turns schema selection into an explicit
 * safety assertion: it must equal the data source's current schema.
 */
class FlywayMigrationRunner @JvmOverloads constructor(
    private val dataSource: DataSource,
    schema: String? = null,
    private val createSchema: Boolean = false,
) {
    // Product-specific identifier limits cannot be checked without opening the
    // DataSource. Keep construction side-effect free and apply those limits at
    // migrate/validate time after the database product is known.
    private val configuredSchema: String? = schema?.let(::validatedSchemaSyntax)

    init {
        require(!createSchema || configuredSchema != null) {
            "FileWeft createSchema=true requires an explicit migration schema"
        }
    }

    /**
     * Supported database products. Detection is based on
     * [DatabaseMetaData.getDatabaseProductName].
     */
    enum class DatabaseProduct {
        POSTGRESQL,
        MYSQL,
        KINGBASE,
    }

    /**
     * Applies pending FileWeft migrations and returns the number executed by
     * this invocation. Flyway's own schema-history lock makes concurrent calls
     * safe. The runner never repairs or baselines existing/untracked FileWeft
     * state. When the dedicated history is absent and no FileWeft sentinel is
     * present, it writes only an explicit version-0 namespace marker before
     * applying every versioned migration from V001 onward.
     */
    fun migrate(): Int {
        val targetSchema = resolveTargetSchema(allowMissingCurrentSchema = createSchema)
        rejectLegacyFileWeftHistory(targetSchema)

        val flyway = configuredFlyway(targetSchema)
        bootstrapNamespaceHistory(targetSchema, flyway)
        val migrationResult = flyway.migrate()
        val migrationsExecuted = migrationResult.migrations.count {
            it.category == "Versioned" || it.category == "Repeatable"
        }

        assertCurrentSchema(targetSchema, "migration")
        return migrationsExecuted
    }

    /**
     * Validates an already migrated FileWeft schema without creating or
     * changing schemas, history, or business tables.
     */
    fun validate() {
        val targetSchema = resolveTargetSchema(allowMissingCurrentSchema = false)
        rejectLegacyFileWeftHistory(targetSchema)
        requireValidNamespaceHistory(targetSchema)

        val flyway = configuredFlyway(targetSchema)
        flyway.validate()
        val pending = flyway.info().pending()
        check(pending.isEmpty()) {
            "FileWeft migration validation failed for schema '$targetSchema': " +
                "${pending.size} migration(s) are pending in $HISTORY_TABLE"
        }
        assertCurrentSchema(targetSchema, "validation")
    }

    private fun configuredFlyway(targetSchema: String): Flyway {
        val product = dataSource.connection.use { connection -> detectDatabaseProduct(connection) }
        val flywayDataSource = when (product) {
            DatabaseProduct.KINGBASE -> KingbaseFlywayDataSource(dataSource)
            DatabaseProduct.POSTGRESQL,
            DatabaseProduct.MYSQL -> dataSource
        }
        return Flyway.configure()
            .dataSource(flywayDataSource)
            .locations(migrationLocation(product))
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .table(HISTORY_TABLE)
            .defaultSchema(targetSchema)
            .schemas(targetSchema)
            .createSchemas(createSchema)
            .baselineOnMigrate(false)
            .baselineVersion(NAMESPACE_BASELINE_VERSION)
            .baselineDescription(NAMESPACE_BASELINE_DESCRIPTION)
            .ignoreMigrationPatterns(*emptyArray<String>())
            .validateOnMigrate(true)
            .load()
    }

    private fun readCurrentSchema(): CurrentSchema = dataSource.connection.use { connection ->
        val product = detectDatabaseProduct(connection)
        val sql = when (product) {
            DatabaseProduct.POSTGRESQL,
            DatabaseProduct.KINGBASE -> "SELECT current_schema()"
            DatabaseProduct.MYSQL -> "SELECT DATABASE()"
        }
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next()) { "DataSource did not return current schema" }
                CurrentSchema(
                    product = product,
                    name = result.getString(1)?.let { validatedSchemaName(it, product) },
                )
            }
        }
    }

    private fun resolveTargetSchema(allowMissingCurrentSchema: Boolean): String {
        val current = readCurrentSchema()
        val currentSchema = current.name
        val explicitSchema = configuredSchema?.let { validatedSchemaName(it, current.product) }
        if (explicitSchema == null) {
            return checkNotNull(currentSchema) {
                "FileWeft migration requires the DataSource to expose a non-null current schema"
            }
        }
        if (currentSchema == explicitSchema) {
            return explicitSchema
        }
        if (currentSchema == null && allowMissingCurrentSchema) {
            check(current.product != DatabaseProduct.MYSQL) {
                "FileWeft cannot safely create MySQL database '$explicitSchema' from a DataSource " +
                    "whose JDBC URL has no current database. Pre-create the database, bind it in " +
                    "the DataSource URL, and use createSchema=false."
            }
            return explicitSchema
        }
        throw IllegalStateException(
            "FileWeft migration schema mismatch: configured schema '$explicitSchema' " +
                "does not equal DataSource current_schema '${currentSchema ?: "<null>"}'",
        )
    }

    private fun assertCurrentSchema(expectedSchema: String, operation: String) {
        val actualSchema = readCurrentSchema().name
        check(actualSchema == expectedSchema) {
            "FileWeft $operation completed but DataSource current_schema " +
                "'${actualSchema ?: "<null>"}' does not equal '$expectedSchema'"
        }
    }

    private fun requireValidNamespaceHistory(targetSchema: String) {
        check(namespaceHistoryExistsAndIsValid(targetSchema)) {
            "FileWeft migration validation requires $targetSchema.$HISTORY_TABLE; " +
                "run migration mode MIGRATE before VALIDATE"
        }
    }

    private fun bootstrapNamespaceHistory(targetSchema: String, flyway: Flyway) {
        if (namespaceHistoryExistsAndIsValid(targetSchema, awaitEmptyHistory = true)) {
            return
        }

        val discoveredTables = discoverFileWeftSentinelTables(targetSchema)
        if (discoveredTables.isNotEmpty()) {
            // A concurrent runner may have created and committed the namespace
            // history after our first probe and then started V001. Re-check the
            // history before classifying those newly visible tables as untracked.
            if (namespaceHistoryExistsAndIsValid(targetSchema, awaitEmptyHistory = true)) {
                return
            }
            rejectUntrackedFileWeftSchema(targetSchema, discoveredTables)
        }

        try {
            flyway.baseline()
        } catch (failure: RuntimeException) {
            // Flyway serializes migrations once its history exists, but two
            // fresh-schema runners can still race while creating the schema or
            // the history table. Tolerate that bootstrap exception only after
            // the competing runner has published the exact reviewed V0 marker.
            if (!validNamespaceHistoryAppearedAfterBootstrapFailure(targetSchema, failure)) {
                throw failure
            }
        }

        check(namespaceHistoryExistsAndIsValid(targetSchema, awaitEmptyHistory = true)) {
            "FileWeft namespace bootstrap completed without creating $targetSchema.$HISTORY_TABLE"
        }
    }

    private fun namespaceHistoryExistsAndIsValid(
        targetSchema: String,
        awaitEmptyHistory: Boolean = false,
    ): Boolean = when (inspectNamespaceHistory(targetSchema)) {
        NamespaceHistoryState.ABSENT -> false
        NamespaceHistoryState.VALID -> true
        NamespaceHistoryState.EMPTY -> {
            check(awaitEmptyHistory) {
                "FileWeft migration history $targetSchema.$HISTORY_TABLE exists but contains no rows. " +
                    "Its required namespace marker is missing; refusing to treat an empty history as an untracked namespace."
            }
            awaitNamespaceMarker(targetSchema)
        }
    }

    private fun inspectNamespaceHistory(targetSchema: String): NamespaceHistoryState =
        dataSource.connection.use { connection ->
            if (!tableExists(connection, targetSchema, HISTORY_TABLE)) {
                return@use NamespaceHistoryState.ABSENT
            }
            inspectNamespaceMarker(connection, targetSchema)
        }

    private fun inspectNamespaceMarker(connection: Connection, targetSchema: String): NamespaceHistoryState {
        val historyTable = qualifiedIdentifier(
            connection.metaData.identifierQuoteString,
            targetSchema,
            HISTORY_TABLE,
        )
        val entries = connection.prepareStatement(
            "SELECT installed_rank, version, description, type, script, checksum, success " +
                "FROM $historyTable ORDER BY installed_rank",
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            NamespaceMarker(
                                installedRank = result.getInt("installed_rank"),
                                version = result.getString("version"),
                                description = result.getString("description"),
                                type = result.getString("type"),
                                script = result.getString("script"),
                                checksum = result.getObject("checksum")?.let { (it as Number).toInt() },
                                success = result.getBoolean("success").takeUnless { result.wasNull() },
                            ),
                        )
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            return NamespaceHistoryState.EMPTY
        }
        val markers = entries.filter { entry ->
            entry.version == NAMESPACE_BASELINE_VERSION || entry.type == NAMESPACE_BASELINE_TYPE
        }
        check(markers.size == 1) {
            "FileWeft migration history $targetSchema.$HISTORY_TABLE requires exactly one " +
                "version-0 BASELINE namespace marker but found ${markers.size}."
        }
        val marker = markers.single()
        check(
            marker.installedRank == NAMESPACE_BASELINE_INSTALLED_RANK &&
                marker.version == NAMESPACE_BASELINE_VERSION &&
                marker.description == NAMESPACE_BASELINE_DESCRIPTION &&
                marker.type == NAMESPACE_BASELINE_TYPE &&
                marker.script == NAMESPACE_BASELINE_SCRIPT &&
                marker.checksum == null &&
                marker.success == true,
        ) {
            "FileWeft migration history $targetSchema.$HISTORY_TABLE has an invalid namespace marker: $marker. " +
                "Expected installed_rank=$NAMESPACE_BASELINE_INSTALLED_RANK, version='$NAMESPACE_BASELINE_VERSION', " +
                "description='$NAMESPACE_BASELINE_DESCRIPTION', type='$NAMESPACE_BASELINE_TYPE', " +
                "script='$NAMESPACE_BASELINE_SCRIPT', checksum=NULL, success=true."
        }
        return NamespaceHistoryState.VALID
    }

    private fun awaitNamespaceMarker(targetSchema: String): Boolean {
        val deadline = System.nanoTime() + BOOTSTRAP_HISTORY_APPEAR_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(BOOTSTRAP_HISTORY_RECHECK_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                error(
                    "Interrupted while waiting for FileWeft migration history " +
                        "$targetSchema.$HISTORY_TABLE to publish its namespace marker",
                )
            }
            when (inspectNamespaceHistory(targetSchema)) {
                NamespaceHistoryState.VALID -> return true
                NamespaceHistoryState.EMPTY -> Unit
                NamespaceHistoryState.ABSENT -> error(
                    "FileWeft migration history $targetSchema.$HISTORY_TABLE disappeared while " +
                        "waiting for its namespace marker",
                )
            }
        }
        error(
            "FileWeft migration history $targetSchema.$HISTORY_TABLE remained empty for " +
                "$BOOTSTRAP_HISTORY_APPEAR_TIMEOUT_MILLIS ms without its required namespace marker; " +
                "refusing to continue",
        )
    }

    private fun discoverFileWeftSentinelTables(targetSchema: String): List<String> =
        dataSource.connection.use { connection ->
            FILEWEFT_SENTINEL_TABLES
                .filter { tableExists(connection, targetSchema, it) }
        }

    private fun rejectUntrackedFileWeftSchema(targetSchema: String, discoveredTables: List<String>): Nothing =
        error(
            "FileWeft tables ${discoveredTables.joinToString()} already exist in schema " +
                "'$targetSchema' without $HISTORY_TABLE. Refusing to baseline or replay " +
                "an untracked FileWeft schema.",
        )

    private fun validNamespaceHistoryAppearedAfterBootstrapFailure(
        targetSchema: String,
        bootstrapFailure: RuntimeException,
    ): Boolean {
        if (!bootstrapFailure.isConcurrentBootstrapConflict()) {
            return false
        }

        fun inspect(): Boolean = try {
            inspectNamespaceHistory(targetSchema) == NamespaceHistoryState.VALID
        } catch (invalidHistory: RuntimeException) {
            invalidHistory.addSuppressed(bootstrapFailure)
            throw invalidHistory
        }

        if (inspect()) {
            return true
        }

        val deadline = System.nanoTime() + BOOTSTRAP_HISTORY_APPEAR_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(BOOTSTRAP_HISTORY_RECHECK_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (inspect()) {
                return true
            }
        }
        return false
    }

    private fun RuntimeException.isConcurrentBootstrapConflict(): Boolean {
        val failures = generateSequence<Throwable>(this) { it.cause }.toList()
        val sqlFailures = failures.filterIsInstance<SQLException>()
        if (sqlFailures.any { failure -> failure.isKnownConcurrentBootstrapConflict() }) {
            return true
        }

        // Flyway 8, 9, and 11 can observe a MySQL history table after the
        // competing CREATE TABLE auto-commits but before that runner commits
        // the V0 marker. DbBaseline reports this narrow race as a plain
        // FlywayException without a SQLException cause. Accept only that exact
        // condition (and no unrelated SQL state), then still require the full
        // reviewed marker to appear within the bounded wait above.
        return failures.filterIsInstance<FlywayException>().any { failure ->
            failure.message.orEmpty().let { message ->
                message.startsWith(EMPTY_HISTORY_BASELINE_FAILURE_PREFIX) &&
                    message.contains(EMPTY_HISTORY_BASELINE_FAILURE_SUFFIX)
            }
        } && sqlFailures.all { failure -> failure.isKnownConcurrentBootstrapConflict() }
    }

    private fun SQLException.isKnownConcurrentBootstrapConflict(): Boolean =
        sqlState in CONCURRENT_BOOTSTRAP_SQL_STATES ||
            (sqlState == MYSQL_GENERAL_SQL_STATE && errorCode == MYSQL_DATABASE_EXISTS_ERROR_CODE)

    private fun rejectLegacyFileWeftHistory(targetSchema: String) {
        val legacyTable = dataSource.connection.use { connection ->
            if (!tableExists(connection, targetSchema, LEGACY_HISTORY_TABLE)) {
                return
            }
            qualifiedIdentifier(
                connection.metaData.identifierQuoteString,
                targetSchema,
                LEGACY_HISTORY_TABLE,
            )
        }
        val packagedScripts = configuredFlyway(targetSchema)
            .info()
            .all()
            .map { it.script }
            .filter { it.startsWith("V") || it.startsWith("R") }
            .toSet()
        check(packagedScripts.isNotEmpty()) {
            "No packaged FileWeft migrations were resolved for schema '$targetSchema'"
        }
        val placeholders = packagedScripts.joinToString(",") { "?" }
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT installed_rank FROM $legacyTable WHERE script IN ($placeholders) LIMIT 1",
            ).use { statement ->
                packagedScripts.forEachIndexed { index, script ->
                    statement.setString(index + 1, script)
                }
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        throw LegacyFileWeftMigrationHistoryException(targetSchema)
                    }
                }
            }
        }
    }

    private fun tableExists(
        connection: Connection,
        schema: String,
        table: String,
    ): Boolean {
        val metadata = connection.metaData
        val product = detectDatabaseProduct(connection)
        val schemaPattern = exactMetadataPattern(metadata, schema)
        val tablePattern = exactMetadataPattern(metadata, table)
        // JDBC catalog is an exact name, unlike schemaPattern/tableNamePattern.
        // Escaping '_' or '%' here makes Connector/J search a different
        // catalog and hides a history table in common names such as fw_prod.
        val catalog = if (product == DatabaseProduct.MYSQL) schema else null
        val metadataSchema = if (product == DatabaseProduct.MYSQL) null else schemaPattern
        return metadata.getTables(catalog, metadataSchema, tablePattern, arrayOf("TABLE")).use { result ->
            generateSequence { if (result.next()) result else null }
                .any { row ->
                    val namespace = when (product) {
                        DatabaseProduct.MYSQL -> row.getString("TABLE_CAT")
                        DatabaseProduct.POSTGRESQL,
                        DatabaseProduct.KINGBASE -> row.getString("TABLE_SCHEM")
                    }
                    namespace == schema && row.getString("TABLE_NAME") == table
                }
        }
    }

    private fun exactMetadataPattern(metadata: DatabaseMetaData, value: String): String {
        val escape = metadata.searchStringEscape
        if (escape.isNullOrEmpty()) {
            return value
        }
        return buildString(value.length) {
            var offset = 0
            while (offset < value.length) {
                when {
                    value.startsWith(escape, offset) -> {
                        append(escape).append(escape)
                        offset += escape.length
                    }
                    value[offset] == '_' || value[offset] == '%' -> {
                        append(escape).append(value[offset])
                        offset++
                    }
                    else -> {
                        append(value[offset])
                        offset++
                    }
                }
            }
        }
    }

    private fun qualifiedIdentifier(quote: String, schema: String, table: String): String {
        val normalizedQuote = quote.trim()
        if (normalizedQuote.isEmpty()) {
            require(UNQUOTED_IDENTIFIER.matches(schema)) {
                "Database does not expose identifier quoting and schema '$schema' is not a safe SQL identifier"
            }
            return "$schema.$table"
        }
        fun quoted(value: String): String = normalizedQuote +
            value.replace(normalizedQuote, normalizedQuote + normalizedQuote) +
            normalizedQuote
        return "${quoted(schema)}.${quoted(table)}"
    }

    companion object {
        const val HISTORY_TABLE: String = "fileweft_schema_history"

        private const val LEGACY_HISTORY_TABLE: String = "flyway_schema_history"
        private const val NAMESPACE_BASELINE_INSTALLED_RANK: Int = 1
        private const val NAMESPACE_BASELINE_VERSION: String = "0"
        private const val NAMESPACE_BASELINE_DESCRIPTION: String = "FileWeft namespace initialization"
        private const val NAMESPACE_BASELINE_TYPE: String = "BASELINE"
        private const val NAMESPACE_BASELINE_SCRIPT: String = NAMESPACE_BASELINE_DESCRIPTION
        private const val POSTGRESQL_IDENTIFIER_MAX_BYTES: Int = 63
        private const val MYSQL_IDENTIFIER_MAX_CHARS: Int = 64
        private const val BOOTSTRAP_HISTORY_APPEAR_TIMEOUT_MILLIS: Long = 5_000
        private const val BOOTSTRAP_HISTORY_RECHECK_MILLIS: Long = 50
        private const val NANOS_PER_MILLISECOND: Long = 1_000_000
        private const val MYSQL_GENERAL_SQL_STATE: String = "HY000"
        private const val MYSQL_DATABASE_EXISTS_ERROR_CODE: Int = 1007
        private const val EMPTY_HISTORY_BASELINE_FAILURE_PREFIX: String =
            "Unable to baseline schema history table "
        private const val EMPTY_HISTORY_BASELINE_FAILURE_SUFFIX: String =
            " as it already exists, and is empty."
        private val UNQUOTED_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_$]*")
        private val CONCURRENT_BOOTSTRAP_SQL_STATES = setOf(
            // PostgreSQL / Kingbase duplicate schema, table, or namespace marker.
            "42P06",
            "42P07",
            "23505",
            // MySQL duplicate table and integrity-constraint states. The
            // exception is accepted only if the exact reviewed V0 marker is
            // subsequently visible, so unrelated failures are never hidden.
            "42S01",
            "23000",
        )
        private val FILEWEFT_SENTINEL_TABLES = setOf(
            "fw_agent_result",
            "fw_agent_suggestion_confirmation",
            "fw_asset",
            "fw_audit_record",
            "fw_doctor_record",
            "fw_document",
            "fw_document_delivery_target",
            "fw_document_version",
            "fw_file_object",
            "fw_idempotency_record",
            "fw_operation_log",
            "fw_outbox_event",
            "fw_sync_record",
            "fw_task",
            "fw_upload_session",
            "fw_upload_session_part",
            "fw_workflow_instance",
            "fw_workflow_task",
        )

        /**
         * Detects the database product from JDBC metadata.
         */
        @JvmStatic
        fun detectDatabaseProduct(connection: Connection): DatabaseProduct {
            val productName = connection.metaData.databaseProductName
            return when {
                productName.equals("PostgreSQL", ignoreCase = true) -> DatabaseProduct.POSTGRESQL
                productName.equals("MySQL", ignoreCase = true) -> {
                    MySqlDatabaseSupport.requireSupported(connection.metaData)
                    DatabaseProduct.MYSQL
                }
                productName.startsWith("Kingbase", ignoreCase = true) -> DatabaseProduct.KINGBASE
                else -> error(
                    "Unsupported database product '$productName'; " +
                        "FileWeft supports PostgreSQL, MySQL 8, and Kingbase ES",
                )
            }
        }

        /**
         * Returns the Flyway location for the requested database product.
         */
        @JvmStatic
        fun migrationLocation(product: DatabaseProduct): String = when (product) {
            DatabaseProduct.POSTGRESQL -> "classpath:ai/icen/fw/db/migration/postgres"
            DatabaseProduct.MYSQL -> "classpath:ai/icen/fw/db/migration/mysql"
            DatabaseProduct.KINGBASE -> "classpath:ai/icen/fw/db/migration/kingbase"
        }

        private fun validatedSchemaSyntax(schema: String): String {
            require(schema.isNotEmpty()) { "FileWeft migration schema must not be empty" }
            val firstCodePoint = schema.codePointAt(0)
            val lastCodePoint = schema.codePointBefore(schema.length)
            require(!isUnicodeWhitespace(firstCodePoint) && !isUnicodeWhitespace(lastCodePoint)) {
                "FileWeft migration schema must not start or end with Unicode whitespace"
            }
            var offset = 0
            while (offset < schema.length) {
                val codePoint = schema.codePointAt(offset)
                val type = Character.getType(codePoint)
                require(
                    !Character.isISOControl(codePoint) &&
                        type != Character.FORMAT.toInt() &&
                        type != Character.LINE_SEPARATOR.toInt() &&
                        type != Character.PARAGRAPH_SEPARATOR.toInt() &&
                        type != Character.SURROGATE.toInt(),
                ) {
                    "FileWeft migration schema contains a forbidden control, format, separator, or surrogate character"
                }
                offset += Character.charCount(codePoint)
            }
            return schema
        }

        private fun validatedSchemaName(schema: String, product: DatabaseProduct): String {
            validatedSchemaSyntax(schema)
            val encodedLength = schema.toByteArray(StandardCharsets.UTF_8).size
            when (product) {
                DatabaseProduct.POSTGRESQL,
                DatabaseProduct.KINGBASE -> require(encodedLength <= POSTGRESQL_IDENTIFIER_MAX_BYTES) {
                    "FileWeft migration schema is $encodedLength UTF-8 bytes; $product identifiers allow at most " +
                        "$POSTGRESQL_IDENTIFIER_MAX_BYTES bytes"
                }
                DatabaseProduct.MYSQL -> {
                    val characterCount = schema.codePointCount(0, schema.length)
                    require(characterCount <= MYSQL_IDENTIFIER_MAX_CHARS) {
                        "FileWeft migration schema is $characterCount characters; $product identifiers allow at most " +
                            "$MYSQL_IDENTIFIER_MAX_CHARS characters"
                    }
                }
            }
            return schema
        }

        private fun isUnicodeWhitespace(codePoint: Int): Boolean =
            Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
    }

    private data class NamespaceMarker(
        val installedRank: Int,
        val version: String?,
        val description: String?,
        val type: String?,
        val script: String?,
        val checksum: Int?,
        val success: Boolean?,
    )

    private data class CurrentSchema(
        val product: DatabaseProduct,
        val name: String?,
    )

    private enum class NamespaceHistoryState {
        ABSENT,
        EMPTY,
        VALID,
    }
}
