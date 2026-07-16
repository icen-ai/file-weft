package ai.icen.fw.reliability.persistence.jdbc

import java.sql.DatabaseMetaData

/** Fail-closed MySQL boundary: V040 relies on enforced CHECK constraints from 8.0.17+. */
internal object ReliabilityMySqlDatabaseSupport {
    fun requireSupported(metadata: DatabaseMetaData) {
        val version = metadata.databaseProductVersion.orEmpty().trim()
        val match = VERSION_PREFIX.find(version)
            ?: error("Cannot verify MySQL version '$version'; FlowWeft Reliability requires MySQL 8.0.17+ in the 8.x line.")
        val (major, minor, patch) = match.destructured.toList().map(String::toInt)
        check(major == 8 && (minor > 0 || patch >= 17)) {
            "Unsupported MySQL version '$version'; FlowWeft Reliability requires MySQL 8.0.17+ in the 8.x line."
        }
    }

    private val VERSION_PREFIX = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)")
}
