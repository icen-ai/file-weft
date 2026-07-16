package ai.icen.fw.observability.jdbc

import ai.icen.fw.observability.SystemDoctorBucket
import ai.icen.fw.observability.SystemDoctorCapability
import ai.icen.fw.observability.SystemDoctorCode
import ai.icen.fw.observability.SystemDoctorProbeRequest
import ai.icen.fw.observability.SystemDoctorProbeSignal
import ai.icen.fw.observability.SystemDoctorProbeState
import ai.icen.fw.observability.SystemDoctorRepairAction
import ai.icen.fw.observability.SystemDoctorSeverity
import java.sql.Connection

class JdbcDatabaseSystemDoctorProbe(
    access: JdbcSystemDoctorAccess,
    probeId: String,
) : AbstractJdbcSystemDoctorProbe(
    access,
    jdbcProbeDescriptor(access, SystemDoctorCapability.DATABASE, probeId, "database"),
) {
    override fun inspect(connection: Connection, request: SystemDoctorProbeRequest): JdbcProbeSnapshot {
        val statement = access.prepare(connection, request, "SELECT 1")
        statement.use { bounded ->
            bounded.executeQuery().use { rows ->
                if (!rows.next() || rows.getInt(1) != 1) unavailableJdbcDoctor()
            }
        }
        access.requireAfterQuery(request)
        return JdbcProbeSnapshot(
            SystemDoctorProbeState.HEALTHY,
            listOf(
                SystemDoctorProbeSignal(
                    SystemDoctorSeverity.HEALTHY,
                    SystemDoctorCode.DATABASE_AVAILABLE,
                    1L,
                    SystemDoctorBucket.AVAILABLE,
                    SystemDoctorRepairAction.NONE,
                ),
            ),
        )
    }
}

class JdbcMigrationHistorySystemDoctorProbe(
    access: JdbcSystemDoctorAccess,
    probeId: String,
    private val definition: JdbcMigrationHistoryDefinition,
) : AbstractJdbcSystemDoctorProbe(
    access,
    jdbcProbeDescriptor(
        access,
        SystemDoctorCapability.HISTORY,
        probeId,
        "migration-history",
        listOf(definition.configurationDigest),
    ),
) {
    override fun inspect(connection: Connection, request: SystemDoctorProbeRequest): JdbcProbeSnapshot {
        var incompleteCount = failedMigrationCount(connection, request)
        definition.requiredVersions.forEach { version ->
            val count = successfulVersionCount(connection, request, version)
            incompleteCount = when {
                count == 1L -> incompleteCount
                count == 0L -> safeJdbcAdd(incompleteCount, 1L)
                else -> safeJdbcAdd(incompleteCount, count - 1L)
            }
        }
        access.requireAfterQuery(request)
        return if (incompleteCount == 0L) {
            JdbcProbeSnapshot(
                SystemDoctorProbeState.HEALTHY,
                listOf(
                    SystemDoctorProbeSignal(
                        SystemDoctorSeverity.HEALTHY,
                        SystemDoctorCode.HISTORY_COMPLETE,
                        definition.requiredVersions.size.toLong(),
                        SystemDoctorBucket.COMPLETE,
                        SystemDoctorRepairAction.NONE,
                    ),
                ),
            )
        } else {
            JdbcProbeSnapshot(
                SystemDoctorProbeState.DEGRADED,
                listOf(
                    SystemDoctorProbeSignal(
                        SystemDoctorSeverity.ERROR,
                        SystemDoctorCode.HISTORY_INCOMPLETE,
                        incompleteCount,
                        SystemDoctorBucket.PARTIAL,
                        SystemDoctorRepairAction.REPAIR_HISTORY,
                    ),
                ),
            )
        }
    }

    private fun successfulVersionCount(
        connection: Connection,
        request: SystemDoctorProbeRequest,
        version: JdbcTrustedValue,
    ): Long {
        val sql = "SELECT COUNT(*) FROM ${definition.table.sql} " +
            "WHERE ${definition.versionColumn.sql} = ? AND ${definition.successColumn.sql} = ?"
        val statement = access.prepare(connection, request, sql)
        statement.use { bounded ->
            bounded.setString(1, version.value)
            bounded.setBoolean(2, true)
            bounded.executeQuery().use { rows ->
                if (!rows.next()) unavailableJdbcDoctor()
                return safeJdbcCount(rows.getLong(1))
            }
        }
    }

    private fun failedMigrationCount(
        connection: Connection,
        request: SystemDoctorProbeRequest,
    ): Long {
        val sql = "SELECT COUNT(*) FROM ${definition.table.sql} WHERE ${definition.successColumn.sql} = ?"
        val statement = access.prepare(connection, request, sql)
        statement.use { bounded ->
            bounded.setBoolean(1, false)
            bounded.executeQuery().use { rows ->
                if (!rows.next()) unavailableJdbcDoctor()
                return safeJdbcCount(rows.getLong(1))
            }
        }
    }
}
