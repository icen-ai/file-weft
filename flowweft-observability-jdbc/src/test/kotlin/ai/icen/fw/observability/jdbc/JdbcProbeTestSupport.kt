package ai.icen.fw.observability.jdbc

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.observability.ProductionSystemDoctor
import ai.icen.fw.observability.SystemDoctorAuthorization
import ai.icen.fw.observability.SystemDoctorAuthorizationPort
import ai.icen.fw.observability.SystemDoctorCapability
import ai.icen.fw.observability.SystemDoctorClock
import ai.icen.fw.observability.SystemDoctorObservationSink
import ai.icen.fw.observability.SystemDoctorProbeExecutionPort
import ai.icen.fw.observability.SystemDoctorProbeRequirement
import ai.icen.fw.observability.SystemDoctorRequest
import ai.icen.fw.observability.SystemDoctorScope
import ai.icen.fw.observability.SystemDoctorTopology
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID

internal class JdbcProbeTestFixture(
    val now: Long = 10_000L,
) {
    val clock: SystemDoctorClock = SystemDoctorClock { now }
    val dataSource: JdbcDataSource = JdbcDataSource().also { source ->
        source.setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
        source.user = "sa"
        source.password = ""
    }

    fun access(
        source: javax.sql.DataSource = dataSource,
        dialect: JdbcSystemDoctorDialect = JdbcSystemDoctorDialect.H2,
    ): JdbcSystemDoctorAccess = JdbcSystemDoctorAccess(source, dialect, "test-source-v1", 1_000L, clock)

    fun request(scope: SystemDoctorScope, tenant: String? = "tenant-a"): SystemDoctorRequest =
        SystemDoctorRequest(
            Identifier("request-${scope.name.lowercase()}"),
            scope,
            if (scope == SystemDoctorScope.TENANT) Identifier(requireNotNull(tenant)) else null,
            Identifier("operator-a"),
            "human",
            "authorization-v1",
            now - 1_000L,
            now + 5_000L,
        )

    fun doctor(
        request: SystemDoctorRequest,
        probes: Collection<JdbcSystemDoctorProbe>,
        requiredCapability: SystemDoctorCapability,
    ): ProductionSystemDoctor {
        val registry = JdbcSystemDoctorProbeRegistry(probes)
        val descriptors = registry.descriptors.associateBy { descriptor -> descriptor.capability }
        val topology = SystemDoctorTopology(
            SystemDoctorCapability.values().map { capability ->
                descriptors[capability]?.requirement(
                    capability == requiredCapability,
                    1_000L,
                    5_000L,
                ) ?: SystemDoctorProbeRequirement(
                    capability,
                    "absent-${capability.name.lowercase().replace('_', '-')}",
                    capability == requiredCapability,
                    "v1",
                    ABSENT_DIGEST,
                    1_000L,
                    5_000L,
                )
            },
        )
        return ProductionSystemDoctor(
            SystemDoctorAuthorizationPort { current -> allowed(current) },
            topology,
            registry,
            SystemDoctorProbeExecutionPort.DIRECT,
            SystemDoctorObservationSink.NOOP,
            clock,
        )
    }

    fun execute(vararg statements: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach { sql -> statement.execute(sql) }
            }
        }
    }

    private fun allowed(request: SystemDoctorRequest): SystemDoctorAuthorization = SystemDoctorAuthorization(
        true,
        request.bindingDigest,
        request.scope,
        request.tenantId,
        request.principalId,
        request.principalType,
        request.authorizationRevision,
        request.requestedAt,
        request.deadlineAt,
    )

    private companion object {
        const val ABSENT_DIGEST: String =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    }
}
