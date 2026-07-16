package ai.icen.fw.capacity.persistence.jdbc;

import ai.icen.fw.capacity.api.CapacityProviderSpi;
import ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationPort;
import ai.icen.fw.capacity.runtime.CapacityPolicySource;

final class CapacityJdbcJavaApiTest {
    void consumesPublicCompositionSurface(JdbcCapacityProvider provider) {
        CapacityProviderSpi spi = provider;
        CapacityPolicySource policies = provider;
        CapacityOutcomeReconciliationPort reconciliation = provider;
        String migration = CapacityJdbcMigrations.resourcePath(CapacityJdbcMigrationDialect.POSTGRESQL);
        String contract = JdbcCapacityProvider.CONTRACT_VERSION;
        if (spi == null || policies == null || reconciliation == null || migration.isEmpty() || contract.isEmpty()) {
            throw new AssertionError("unreachable");
        }
    }
}
