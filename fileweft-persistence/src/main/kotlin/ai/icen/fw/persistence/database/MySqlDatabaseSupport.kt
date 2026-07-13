package ai.icen.fw.persistence.database

import java.sql.DatabaseMetaData

/** Fail-closed MySQL version boundary shared by migrations and JDBC DML. */
internal object MySqlDatabaseSupport {
    fun requireSupported(metadata: DatabaseMetaData) {
        val productVersion = metadata.databaseProductVersion.orEmpty().trim()
        val match = VERSION_PREFIX.find(productVersion)
            ?: error(
                "Cannot verify MySQL version '$productVersion'; FileWeft requires MySQL 8.0.17 or newer in the 8.x line",
            )
        val (major, minor, patch) = match.destructured.toList().map(String::toInt)
        check(major == 8 && (minor > 0 || patch >= 17)) {
            "Unsupported MySQL version '$productVersion'; FileWeft requires MySQL 8.0.17 or newer in the 8.x line"
        }
    }

    private val VERSION_PREFIX = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)")
}
