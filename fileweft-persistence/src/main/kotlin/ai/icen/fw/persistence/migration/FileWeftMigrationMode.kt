package ai.icen.fw.persistence.migration

/** Startup behavior for FlowWeft-owned database migrations. */
enum class FileWeftMigrationMode {
    DISABLED,
    VALIDATE,
    MIGRATE,
}
