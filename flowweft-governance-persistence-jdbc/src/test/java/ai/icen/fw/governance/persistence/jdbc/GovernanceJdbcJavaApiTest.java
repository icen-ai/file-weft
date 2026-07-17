package ai.icen.fw.governance.persistence.jdbc;

import ai.icen.fw.governance.runtime.GovernanceDeletionRepository;
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOperationRepository;
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetManifestRepository;
import ai.icen.fw.governance.runtime.GovernanceOutboxRepository;
import ai.icen.fw.governance.runtime.GovernanceRuntimeDiagnosticSource;

import java.util.List;

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

    void consumesTargetLedgerSurface(JdbcGovernanceDeletionTargetLedger persistence) {
        GovernanceDeletionTargetManifestRepository manifests = persistence;
        GovernanceDeletionTargetItemOperationRepository operations = persistence;
        List<String> migrations = GovernanceJdbcMigrations.resourcePaths(
            GovernanceJdbcMigrationDialect.POSTGRESQL
        );
        if (manifests == null || operations == null || migrations.size() != 2) {
            throw new AssertionError("unreachable");
        }
    }
}
