package ai.icen.fw.observability.jdbc

import ai.icen.fw.observability.SystemDoctorProbe
import ai.icen.fw.observability.SystemDoctorProbeRequest
import ai.icen.fw.observability.SystemDoctorProbeResult
import ai.icen.fw.observability.SystemDoctorProbeSignal
import ai.icen.fw.observability.SystemDoctorProbeState
import ai.icen.fw.observability.SystemDoctorScope
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLTimeoutException
import java.util.Collections

interface JdbcSystemDoctorProbe : SystemDoctorProbe {
    fun descriptor(): JdbcSystemDoctorProbeDescriptor
}

/** Shared fail-closed lifecycle for every JDBC probe. */
abstract class AbstractJdbcSystemDoctorProbe protected constructor(
    protected val access: JdbcSystemDoctorAccess,
    overrideDescriptor: JdbcSystemDoctorProbeDescriptor,
) : JdbcSystemDoctorProbe {
    private val descriptor: JdbcSystemDoctorProbeDescriptor = overrideDescriptor

    final override fun descriptor(): JdbcSystemDoctorProbeDescriptor = descriptor

    final override fun inspect(request: SystemDoctorProbeRequest): SystemDoctorProbeResult {
        val now = access.clock.currentTimeMillis()
        if (request.capability != descriptor.capability || request.probeId != descriptor.probeId ||
            now < request.issuedAt || now >= request.deadlineAt
        ) {
            return unavailable(request, now)
        }
        return try {
            val snapshot = access.withValidatedConnection(request) { connection ->
                inspect(connection, request)
            }
            val observedAt = access.clock.currentTimeMillis()
            if (observedAt >= request.deadlineAt) unavailable(request, observedAt) else SystemDoctorProbeResult(
                request.probeBindingDigest,
                descriptor.capability,
                snapshot.state,
                descriptor.contractVersion,
                descriptor.configurationDigest,
                observedAt,
                snapshot.signals,
                snapshot.truncated,
            )
        } catch (failure: JdbcDoctorFailure) {
            when (failure.kind) {
                JdbcDoctorFailureKind.UNSUPPORTED -> unsupported(request, access.clock.currentTimeMillis())
                JdbcDoctorFailureKind.UNAVAILABLE -> unavailable(request, access.clock.currentTimeMillis())
            }
        } catch (_: SQLException) {
            unavailable(request, access.clock.currentTimeMillis())
        } catch (_: Exception) {
            unavailable(request, access.clock.currentTimeMillis())
        }
    }

    protected abstract fun inspect(
        connection: Connection,
        request: SystemDoctorProbeRequest,
    ): JdbcProbeSnapshot

    private fun unsupported(request: SystemDoctorProbeRequest, observedAt: Long): SystemDoctorProbeResult =
        SystemDoctorProbeResult(
            request.probeBindingDigest,
            descriptor.capability,
            SystemDoctorProbeState.UNSUPPORTED,
            descriptor.contractVersion,
            descriptor.configurationDigest,
            observedAt.coerceAtLeast(0L),
        )

    private fun unavailable(request: SystemDoctorProbeRequest, observedAt: Long): SystemDoctorProbeResult =
        SystemDoctorProbeResult(
            request.probeBindingDigest,
            descriptor.capability,
            SystemDoctorProbeState.UNAVAILABLE,
            descriptor.contractVersion,
            descriptor.configurationDigest,
            observedAt.coerceAtLeast(0L),
        )
}

class JdbcProbeSnapshot internal constructor(
    val state: SystemDoctorProbeState,
    signals: Collection<SystemDoctorProbeSignal>,
    val truncated: Boolean = false,
) {
    val signals: List<SystemDoctorProbeSignal> = Collections.unmodifiableList(ArrayList(signals))
}

internal enum class JdbcDoctorFailureKind {
    UNSUPPORTED,
    UNAVAILABLE,
}

internal class JdbcDoctorFailure(val kind: JdbcDoctorFailureKind) : RuntimeException(null, null, false, false)

internal fun unsupportedJdbcDoctor(): Nothing = throw JdbcDoctorFailure(JdbcDoctorFailureKind.UNSUPPORTED)

internal fun unavailableJdbcDoctor(): Nothing = throw JdbcDoctorFailure(JdbcDoctorFailureKind.UNAVAILABLE)

internal fun <T> JdbcSystemDoctorAccess.withValidatedConnection(
    request: SystemDoctorProbeRequest,
    block: (Connection) -> T,
): T {
    requireDeadline(request)
    val connection = try {
        dataSource.connection
    } catch (failure: SQLException) {
        throw classifyJdbcFailure(failure)
    }
    connection.use { current ->
        requireDialect(current)
        requireDeadline(request)
        return try {
            block(current)
        } catch (failure: JdbcDoctorFailure) {
            throw failure
        } catch (failure: SQLException) {
            throw classifyJdbcFailure(failure)
        }
    }
}

internal fun JdbcSystemDoctorAccess.prepare(
    connection: Connection,
    request: SystemDoctorProbeRequest,
    sql: String,
): PreparedStatement {
    requireDeadline(request)
    val statement = try {
        connection.prepareStatement(sql)
    } catch (failure: SQLException) {
        throw classifyJdbcFailure(failure)
    }
    try {
        statement.queryTimeout = queryTimeoutSeconds(request)
    } catch (failure: SQLException) {
        try {
            statement.close()
        } catch (_: SQLException) {
            // Closing a failed statement cannot change the stable result.
        }
        throw classifyJdbcFailure(failure)
    }
    return statement
}

internal fun JdbcSystemDoctorAccess.requireDeadline(request: SystemDoctorProbeRequest) {
    val now = clock.currentTimeMillis()
    if (now < request.issuedAt || now >= request.deadlineAt) unavailableJdbcDoctor()
}

internal fun JdbcSystemDoctorAccess.requireAfterQuery(request: SystemDoctorProbeRequest) {
    requireDeadline(request)
}

private fun JdbcSystemDoctorAccess.queryTimeoutSeconds(request: SystemDoctorProbeRequest): Int {
    val remaining = request.deadlineAt - clock.currentTimeMillis()
    if (remaining <= 0L) unavailableJdbcDoctor()
    val bounded = minOf(remaining, maximumQueryTimeoutMillis)
    return ((bounded + 999L) / 1_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}

private fun JdbcSystemDoctorAccess.requireDialect(connection: Connection) {
    val product = try {
        connection.metaData.databaseProductName
    } catch (failure: SQLException) {
        throw classifyJdbcFailure(failure)
    }
    val actual = product?.let(::detectDialect) ?: unsupportedJdbcDoctor()
    if (actual != expectedDialect) unsupportedJdbcDoctor()
}

private fun detectDialect(productName: String): JdbcSystemDoctorDialect? {
    val normalized = productName.trim().lowercase()
    return when {
        normalized.contains("kingbase") -> JdbcSystemDoctorDialect.KINGBASE
        normalized == "postgresql" || normalized.startsWith("postgresql ") ->
            JdbcSystemDoctorDialect.POSTGRESQL
        normalized == "mysql" || normalized.startsWith("mysql ") -> JdbcSystemDoctorDialect.MYSQL
        normalized == "h2" || normalized.startsWith("h2 ") -> JdbcSystemDoctorDialect.H2
        else -> null
    }
}

private fun classifyJdbcFailure(failure: SQLException): JdbcDoctorFailure {
    if (failure is SQLTimeoutException) return JdbcDoctorFailure(JdbcDoctorFailureKind.UNAVAILABLE)
    if (failure is SQLFeatureNotSupportedException) return JdbcDoctorFailure(JdbcDoctorFailureKind.UNSUPPORTED)
    val state = failure.sqlState.orEmpty().uppercase()
    return when {
        state == "42501" || state.startsWith("28") || state.startsWith("08") ->
            JdbcDoctorFailure(JdbcDoctorFailureKind.UNAVAILABLE)
        state.startsWith("42") || state.startsWith("0A") ->
            JdbcDoctorFailure(JdbcDoctorFailureKind.UNSUPPORTED)
        else -> JdbcDoctorFailure(JdbcDoctorFailureKind.UNAVAILABLE)
    }
}

internal fun tenantBinding(
    request: SystemDoctorProbeRequest,
    bindingPort: JdbcTenantBindingPort,
): JdbcTenantBinding? = when (request.scope) {
    SystemDoctorScope.TENANT -> {
        val tenantId = request.tenantId ?: unavailableJdbcDoctor()
        try {
            bindingPort.bind(tenantId)
        } catch (_: Exception) {
            unavailableJdbcDoctor()
        }
    }
    SystemDoctorScope.SYSTEM -> null
}

internal fun placeholders(size: Int): String {
    require(size > 0) { "JDBC placeholder inventory must not be empty." }
    return List(size) { "?" }.joinToString(",")
}

internal fun PreparedStatement.bindTrustedValues(
    startIndex: Int,
    values: Collection<JdbcTrustedValue>,
): Int {
    var index = startIndex
    values.forEach { value -> setString(index++, value.value) }
    return index
}

internal fun safeJdbcCount(value: Long): Long = value.coerceIn(0L, JdbcDoctorLimits.MAX_COUNT)

internal fun safeJdbcAdd(left: Long, right: Long): Long =
    if (JdbcDoctorLimits.MAX_COUNT - left < right) JdbcDoctorLimits.MAX_COUNT else left + right

internal fun jdbcProbeDescriptor(
    access: JdbcSystemDoctorAccess,
    capability: ai.icen.fw.observability.SystemDoctorCapability,
    probeId: String,
    kind: String,
    configurationDigests: Collection<String> = emptyList(),
): JdbcSystemDoctorProbeDescriptor {
    val digest = JdbcDoctorDigest("flowweft.system-doctor.jdbc-probe.v1")
        .add(access.configurationDigest)
        .add(capability.name)
        .add(kind)
    configurationDigests.sorted().forEach { configurationDigest -> digest.add(configurationDigest) }
    return JdbcSystemDoctorProbeDescriptor(capability, probeId, "jdbc-v1", digest.finish())
}
