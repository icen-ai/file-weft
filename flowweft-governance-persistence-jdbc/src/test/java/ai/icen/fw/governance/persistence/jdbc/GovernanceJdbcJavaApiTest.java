package ai.icen.fw.governance.persistence.jdbc;

import ai.icen.fw.governance.runtime.GovernanceDeletionRepository;
import ai.icen.fw.governance.runtime.GovernanceOutboxRepository;
import ai.icen.fw.governance.runtime.GovernanceRuntimeDiagnosticSource;

final class GovernanceJdbcJavaApiTest {
    void consumesPublicCompositionSurface(JdbcGovernancePersistence persistence) {
        GovernanceDeletionRepository runs = persistence;
        GovernanceOutboxRepository outbox = persistence;
        GovernanceRuntimeDiagnosticSource doctor = persistence;
        String migration = GovernanceJdbcMigrations.resourcePath(GovernanceJdbcMigrationDialect.POSTGRESQL);
        String contract = JdbcGovernancePersistence.CONTRACT_VERSION;
        if (runs == null || outbox == null || doctor == null || migration.isEmpty() || contract.isEmpty()) {
            throw new AssertionError("unreachable");
        }
    }
}
