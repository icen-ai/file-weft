package ai.icen.fw.persistence.migration

/**
 * Signals that FileWeft migrations were previously recorded in Flyway's
 * shared default history table and cannot be replayed safely.
 */
class LegacyFileWeftMigrationHistoryException(
    val schema: String,
) : IllegalStateException(
    "Legacy FileWeft migrations were detected in $schema.flyway_schema_history. " +
        "Refusing to replay, repair, or baseline them into fileweft_schema_history; " +
        "migrate the history explicitly before starting FileWeft.",
)
