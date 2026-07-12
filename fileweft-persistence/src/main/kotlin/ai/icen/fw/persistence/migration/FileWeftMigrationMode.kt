package ai.icen.fw.persistence.migration

/** Startup behavior for FileWeft-owned database migrations. */
enum class FileWeftMigrationMode {
    DISABLED,
    VALIDATE,
    MIGRATE,
}
