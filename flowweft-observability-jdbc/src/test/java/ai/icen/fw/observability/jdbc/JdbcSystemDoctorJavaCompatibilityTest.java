package ai.icen.fw.observability.jdbc;

import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.observability.ProductionSystemDoctor;
import ai.icen.fw.observability.SystemDoctorAuthorization;
import ai.icen.fw.observability.SystemDoctorAuthorizationPort;
import ai.icen.fw.observability.SystemDoctorCapability;
import ai.icen.fw.observability.SystemDoctorObservationSink;
import ai.icen.fw.observability.SystemDoctorProbeExecutionPort;
import ai.icen.fw.observability.SystemDoctorProbeRequirement;
import ai.icen.fw.observability.SystemDoctorReadiness;
import ai.icen.fw.observability.SystemDoctorReport;
import ai.icen.fw.observability.SystemDoctorRequest;
import ai.icen.fw.observability.SystemDoctorScope;
import ai.icen.fw.observability.SystemDoctorTopology;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JdbcSystemDoctorJavaCompatibilityTest {
    private static final String ABSENT_DIGEST =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    @Test
    void wiresTrustedJdbcConfigurationAndTenantScopedProbeFromJava8() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:java-observability-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        source.setPassword("");
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE fw_java_queue(" +
                    "tenant_id varchar(64), queue_status varchar(32), ready_time bigint, created_time bigint)");
            statement.execute("INSERT INTO fw_java_queue VALUES ('tenant-java', 'READY', 1, 900)");
        }

        JdbcSystemDoctorAccess access = new JdbcSystemDoctorAccess(
                source,
                JdbcSystemDoctorDialect.H2,
                "java-source-v1",
                1_000L,
                () -> 1_000L
        );
        JdbcQueueDefinition definition = new JdbcQueueDefinition(
                JdbcQueueWorkload.OUTBOX,
                JdbcTrustedTable.of("fw_java_queue"),
                JdbcTrustedSqlIdentifier.of("tenant_id"),
                JdbcTrustedSqlIdentifier.of("queue_status"),
                JdbcTrustedSqlIdentifier.of("created_time"),
                JdbcTrustedSqlIdentifier.of("ready_time"),
                Collections.singletonList(JdbcTrustedValue.of("READY")),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                10L,
                5_000L,
                "plain-java-v1",
                JdbcTenantBindingPort.IDENTIFIER_VALUE
        );
        JdbcQueueSystemDoctorProbe probe = new JdbcQueueSystemDoctorProbe(
                access,
                "java-jdbc-outbox",
                Collections.singletonList(definition)
        );
        JdbcSystemDoctorProbeRegistry registry = new JdbcSystemDoctorProbeRegistry(
                Collections.singletonList(probe)
        );
        JdbcSystemDoctorProbeDescriptor descriptor = probe.descriptor();
        List<SystemDoctorProbeRequirement> requirements = new ArrayList<>();
        for (SystemDoctorCapability capability : SystemDoctorCapability.values()) {
            if (capability == descriptor.getCapability()) {
                requirements.add(descriptor.requirement(true, 1_000L, 5_000L));
            } else {
                requirements.add(new SystemDoctorProbeRequirement(
                        capability,
                        "absent-" + capability.name().toLowerCase().replace('_', '-'),
                        false,
                        "v1",
                        ABSENT_DIGEST,
                        1_000L,
                        5_000L
                ));
            }
        }
        SystemDoctorRequest request = new SystemDoctorRequest(
                new Identifier("java-jdbc-request"),
                SystemDoctorScope.TENANT,
                new Identifier("tenant-java"),
                new Identifier("operator-java"),
                "human",
                "authorization-java-v1",
                900L,
                2_000L
        );
        SystemDoctorAuthorizationPort authorization = current -> new SystemDoctorAuthorization(
                true,
                current.getBindingDigest(),
                current.getScope(),
                current.getTenantId(),
                current.getPrincipalId(),
                current.getPrincipalType(),
                current.getAuthorizationRevision(),
                current.getRequestedAt(),
                current.getDeadlineAt()
        );
        ProductionSystemDoctor doctor = new ProductionSystemDoctor(
                authorization,
                new SystemDoctorTopology(requirements),
                registry,
                SystemDoctorProbeExecutionPort.DIRECT,
                SystemDoctorObservationSink.NOOP,
                () -> 1_000L
        );

        SystemDoctorReport report = doctor.inspectTenant(request);

        assertEquals(SystemDoctorReadiness.READY, report.getReadiness());
        assertEquals(1, report.getHealthyRequiredProbeCount());
        assertNotNull(registry.getDescriptors());
        assertEquals(SystemDoctorCapability.OUTBOX_QUEUE, descriptor.getCapability());
    }
}
