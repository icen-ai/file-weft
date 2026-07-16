package ai.icen.fw.observability.jdbc

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.observability.SystemDoctorCapability
import ai.icen.fw.observability.SystemDoctorClock
import ai.icen.fw.observability.SystemDoctorProbeRequirement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import javax.sql.DataSource

enum class JdbcSystemDoctorDialect {
    POSTGRESQL,
    MYSQL,
    KINGBASE,
    H2,
}

/** One identifier segment accepted by generated read-only SQL. */
class JdbcTrustedSqlIdentifier private constructor(internal val sql: String) {
    override fun toString(): String = "JdbcTrustedSqlIdentifier(<redacted>)"

    companion object {
        private val PATTERN = Regex("[a-z][a-z0-9_]{0,62}")

        @JvmStatic
        fun of(value: String): JdbcTrustedSqlIdentifier = JdbcTrustedSqlIdentifier(
            value.also {
                require(PATTERN.matches(it)) {
                    "JDBC System Doctor SQL identifiers must be explicit lower-case whitelist entries."
                }
            },
        )
    }
}

/** A table is assembled from validated segments; arbitrary SQL is never accepted. */
class JdbcTrustedTable private constructor(
    val schema: JdbcTrustedSqlIdentifier?,
    val table: JdbcTrustedSqlIdentifier,
) {
    internal val sql: String = if (schema == null) table.sql else "${schema.sql}.${table.sql}"

    override fun toString(): String = "JdbcTrustedTable(<redacted>)"

    companion object {
        @JvmStatic
        fun of(table: String): JdbcTrustedTable = JdbcTrustedTable(null, JdbcTrustedSqlIdentifier.of(table))

        @JvmStatic
        fun qualified(schema: String, table: String): JdbcTrustedTable = JdbcTrustedTable(
            JdbcTrustedSqlIdentifier.of(schema),
            JdbcTrustedSqlIdentifier.of(table),
        )
    }
}

/** Trusted configuration value that is always bound as a JDBC parameter. */
class JdbcTrustedValue private constructor(internal val value: String) {
    override fun toString(): String = "JdbcTrustedValue(<redacted>)"

    companion object {
        private val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")

        @JvmStatic
        fun of(value: String): JdbcTrustedValue = JdbcTrustedValue(
            value.also {
                require(PATTERN.matches(it)) { "JDBC System Doctor trusted value is invalid." }
            },
        )
    }
}

/** Encoded tenant value remains a bound parameter and never appears in output or SQL text. */
class JdbcTenantBinding private constructor(internal val value: String) {
    override fun toString(): String = "JdbcTenantBinding(<redacted>)"

    companion object {
        @JvmStatic
        fun of(value: String): JdbcTenantBinding = JdbcTenantBinding(
            value.also {
                require(it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl)) {
                    "JDBC System Doctor tenant binding is invalid."
                }
            },
        )
    }
}

fun interface JdbcTenantBindingPort {
    fun bind(tenantId: Identifier): JdbcTenantBinding

    companion object {
        /** For schemas that persist the host tenant identifier verbatim. */
        @JvmField
        val IDENTIFIER_VALUE: JdbcTenantBindingPort = JdbcTenantBindingPort { tenantId ->
            JdbcTenantBinding.of(tenantId.value)
        }
    }
}

/** Shared access policy. DataSource identity is represented only by a safe revision token. */
class JdbcSystemDoctorAccess @JvmOverloads constructor(
    internal val dataSource: DataSource,
    val expectedDialect: JdbcSystemDoctorDialect,
    dataSourceRevision: String,
    val maximumQueryTimeoutMillis: Long = 2_000L,
    internal val clock: SystemDoctorClock = SystemDoctorClock.SYSTEM,
) {
    val dataSourceRevision: String = jdbcDoctorCode(dataSourceRevision, "JDBC data source revision is invalid.")
    internal val configurationDigest: String

    init {
        require(maximumQueryTimeoutMillis in 50L..30_000L) {
            "JDBC System Doctor query timeout is invalid."
        }
        configurationDigest = JdbcDoctorDigest("flowweft.system-doctor.jdbc-access.v1")
            .add(expectedDialect.name)
            .add(this.dataSourceRevision)
            .add(maximumQueryTimeoutMillis)
            .finish()
    }

    override fun toString(): String =
        "JdbcSystemDoctorAccess(expectedDialect=$expectedDialect, <redacted>)"
}

class JdbcMigrationHistoryDefinition(
    val table: JdbcTrustedTable,
    val versionColumn: JdbcTrustedSqlIdentifier,
    val successColumn: JdbcTrustedSqlIdentifier,
    requiredVersions: Collection<JdbcTrustedValue>,
) {
    val requiredVersions: List<JdbcTrustedValue>
    internal val configurationDigest: String

    init {
        val snapshot = ArrayList(requiredVersions)
        require(snapshot.isNotEmpty() && snapshot.size <= JdbcDoctorLimits.MAX_MIGRATION_VERSIONS) {
            "JDBC migration version inventory is invalid."
        }
        require(snapshot.map { value -> value.value }.distinct().size == snapshot.size) {
            "JDBC migration version inventory contains duplicates."
        }
        this.requiredVersions = Collections.unmodifiableList(snapshot)
        val digest = JdbcDoctorDigest("flowweft.system-doctor.jdbc-history.v1")
            .add(table.sql)
            .add(versionColumn.sql)
            .add(successColumn.sql)
        snapshot.sortedBy { value -> value.value }.forEach { value -> digest.add(value.value) }
        configurationDigest = digest.finish()
    }

    override fun toString(): String =
        "JdbcMigrationHistoryDefinition(requiredVersionCount=${requiredVersions.size}, <redacted>)"
}

enum class JdbcQueueWorkload(val capability: SystemDoctorCapability) {
    OUTBOX(SystemDoctorCapability.OUTBOX_QUEUE),
    NOTIFICATION(SystemDoctorCapability.NOTIFICATION_QUEUE),
    EFFECT(SystemDoctorCapability.EFFECT_QUEUE),
    SLA(SystemDoctorCapability.EFFECT_QUEUE),
    WORKFLOW(SystemDoctorCapability.EFFECT_QUEUE),
    AGENT(SystemDoctorCapability.AGENT_QUEUE),
    RETRIEVAL(SystemDoctorCapability.RETRIEVAL_QUEUE),
}

/** Explicit table/column/status whitelist for one queue projection. */
class JdbcQueueDefinition(
    val workload: JdbcQueueWorkload,
    val table: JdbcTrustedTable,
    val tenantColumn: JdbcTrustedSqlIdentifier,
    val stateColumn: JdbcTrustedSqlIdentifier,
    val createdTimeColumn: JdbcTrustedSqlIdentifier,
    val nextEligibleTimeColumn: JdbcTrustedSqlIdentifier?,
    readyStates: Collection<JdbcTrustedValue>,
    failedStates: Collection<JdbcTrustedValue>,
    outcomeUnknownStates: Collection<JdbcTrustedValue>,
    reconciliationPendingStates: Collection<JdbcTrustedValue>,
    val maximumReadyCount: Long,
    val maximumOldestReadyAgeMillis: Long,
    tenantBindingRevision: String,
    internal val tenantBinding: JdbcTenantBindingPort,
) {
    val readyStates: List<JdbcTrustedValue> = immutableStates(readyStates, true, "ready")
    val failedStates: List<JdbcTrustedValue> = immutableStates(failedStates, false, "failed")
    val outcomeUnknownStates: List<JdbcTrustedValue> = immutableStates(
        outcomeUnknownStates,
        false,
        "outcome-unknown",
    )
    val reconciliationPendingStates: List<JdbcTrustedValue> = immutableStates(
        reconciliationPendingStates,
        false,
        "reconciliation",
    )
    val tenantBindingRevision: String = jdbcDoctorCode(
        tenantBindingRevision,
        "JDBC queue tenant binding revision is invalid.",
    )
    internal val configurationDigest: String

    init {
        require(maximumReadyCount in 0L..JdbcDoctorLimits.MAX_COUNT) {
            "JDBC queue backlog threshold is invalid."
        }
        require(maximumOldestReadyAgeMillis in 1L..JdbcDoctorLimits.MAX_AGE_MILLIS) {
            "JDBC queue age threshold is invalid."
        }
        val allStates = readyStates + failedStates + outcomeUnknownStates + reconciliationPendingStates
        require(allStates.map { value -> value.value }.distinct().size == allStates.size) {
            "JDBC queue state classes must not overlap."
        }
        configurationDigest = JdbcDoctorDigest("flowweft.system-doctor.jdbc-queue.v1")
            .add(workload.name)
            .add(table.sql)
            .add(tenantColumn.sql)
            .add(stateColumn.sql)
            .add(createdTimeColumn.sql)
            .add(nextEligibleTimeColumn?.sql ?: "-")
            .add(this.tenantBindingRevision)
            .add(maximumReadyCount)
            .add(maximumOldestReadyAgeMillis)
            .addStates("ready", readyStates)
            .addStates("failed", failedStates)
            .addStates("unknown", outcomeUnknownStates)
            .addStates("reconciliation", reconciliationPendingStates)
            .finish()
    }

    override fun toString(): String = "JdbcQueueDefinition(workload=$workload, <redacted>)"
}

/** Read-only projection for active leases and stale running work. */
class JdbcWorkerLeaseDefinition(
    val table: JdbcTrustedTable,
    val tenantColumn: JdbcTrustedSqlIdentifier,
    val stateColumn: JdbcTrustedSqlIdentifier,
    val leaseExpiresTimeColumn: JdbcTrustedSqlIdentifier,
    val updatedTimeColumn: JdbcTrustedSqlIdentifier,
    activeStates: Collection<JdbcTrustedValue>,
    val maximumRunningAgeMillis: Long,
    tenantBindingRevision: String,
    internal val tenantBinding: JdbcTenantBindingPort,
) {
    val activeStates: List<JdbcTrustedValue> = immutableStates(activeStates, true, "active lease")
    val tenantBindingRevision: String = jdbcDoctorCode(
        tenantBindingRevision,
        "JDBC lease tenant binding revision is invalid.",
    )
    internal val configurationDigest: String

    init {
        require(maximumRunningAgeMillis in 1L..JdbcDoctorLimits.MAX_AGE_MILLIS) {
            "JDBC worker lease age threshold is invalid."
        }
        configurationDigest = JdbcDoctorDigest("flowweft.system-doctor.jdbc-lease.v1")
            .add(table.sql)
            .add(tenantColumn.sql)
            .add(stateColumn.sql)
            .add(leaseExpiresTimeColumn.sql)
            .add(updatedTimeColumn.sql)
            .add(this.tenantBindingRevision)
            .add(maximumRunningAgeMillis)
            .addStates("active", activeStates)
            .finish()
    }

    override fun toString(): String = "JdbcWorkerLeaseDefinition(<redacted>)"
}

class JdbcSystemDoctorProbeDescriptor internal constructor(
    val capability: SystemDoctorCapability,
    probeId: String,
    contractVersion: String,
    configurationDigest: String,
) {
    val probeId: String = jdbcDoctorCode(probeId, "JDBC System Doctor probe id is invalid.")
    val contractVersion: String = jdbcDoctorCode(
        contractVersion,
        "JDBC System Doctor contract version is invalid.",
    )
    val configurationDigest: String = jdbcDoctorDigest(
        configurationDigest,
        "JDBC System Doctor configuration digest is invalid.",
    )

    fun requirement(
        required: Boolean,
        timeoutMillis: Long,
        maximumSnapshotAgeMillis: Long,
    ): SystemDoctorProbeRequirement = SystemDoctorProbeRequirement(
        capability,
        probeId,
        required,
        contractVersion,
        configurationDigest,
        timeoutMillis,
        maximumSnapshotAgeMillis,
    )

    override fun toString(): String =
        "JdbcSystemDoctorProbeDescriptor(capability=$capability, <redacted>)"
}

internal object JdbcDoctorLimits {
    const val MAX_COUNT: Long = 1_000_000_000_000L
    const val MAX_AGE_MILLIS: Long = 31_536_000_000L
    const val MAX_MIGRATION_VERSIONS: Int = 256
    const val MAX_STATES: Int = 32
    const val MAX_DEFINITIONS: Int = 32
}

private fun immutableStates(
    states: Collection<JdbcTrustedValue>,
    required: Boolean,
    label: String,
): List<JdbcTrustedValue> {
    val snapshot = ArrayList(states)
    require(!required || snapshot.isNotEmpty()) { "JDBC $label state inventory is required." }
    require(snapshot.size <= JdbcDoctorLimits.MAX_STATES) { "JDBC $label state inventory is too large." }
    require(snapshot.map { value -> value.value }.distinct().size == snapshot.size) {
        "JDBC $label state inventory contains duplicates."
    }
    return Collections.unmodifiableList(snapshot)
}

internal fun jdbcDoctorCode(value: String, message: String): String = value.also {
    require(it.matches(Regex("[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*")) && it.length <= 256) { message }
}

internal fun jdbcDoctorDigest(value: String, message: String): String = value.lowercase().also {
    require(it.matches(Regex("[0-9a-f]{64}"))) { message }
}

internal class JdbcDoctorDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(domain)
    }

    fun add(value: String): JdbcDoctorDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): JdbcDoctorDigest = add(value.toString())

    fun addStates(label: String, values: Collection<JdbcTrustedValue>): JdbcDoctorDigest {
        add(label)
        values.map { value -> value.value }.sorted().forEach { value -> add(value) }
        return this
    }

    fun finish(): String = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
