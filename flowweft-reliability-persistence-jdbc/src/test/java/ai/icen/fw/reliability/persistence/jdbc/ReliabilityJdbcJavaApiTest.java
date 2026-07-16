package ai.icen.fw.reliability.persistence.jdbc;

import ai.icen.fw.reliability.runtime.ReliabilityOutboxRepository;
import ai.icen.fw.reliability.runtime.ReliabilityRunRepository;
import ai.icen.fw.reliability.runtime.ReliabilitySloScheduleRepository;

final class ReliabilityJdbcJavaApiTest {
    void consumesPublicCompositionSurface(JdbcReliabilityPersistence persistence) {
        ReliabilityRunRepository runs = persistence.getRunRepository();
        ReliabilityOutboxRepository outbox = persistence.getOutboxRepository();
        ReliabilitySloScheduleRepository slo = persistence.getSloRepository();
        String migration = ReliabilityJdbcMigrations.resourcePath(ReliabilityJdbcMigrationDialect.POSTGRESQL);
        String contract = JdbcReliabilityPersistence.CONTRACT_VERSION;
        if (runs == null || outbox == null || slo == null || migration.isEmpty() || contract.isEmpty()) {
            throw new AssertionError("unreachable");
        }
    }
}
