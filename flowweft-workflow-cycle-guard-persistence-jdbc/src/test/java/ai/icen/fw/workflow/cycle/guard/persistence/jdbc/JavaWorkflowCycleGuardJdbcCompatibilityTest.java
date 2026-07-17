package ai.icen.fw.workflow.cycle.guard.persistence.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaWorkflowCycleGuardJdbcCompatibilityTest {
    @Test
    void javaHostCanConstructStoreAndLocateOwnedSchemaContracts() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:cycle-guard-java;MODE=PostgreSQL");

        JdbcWorkflowCycleGuardPersistence store =
            new JdbcWorkflowCycleGuardPersistence(dataSource);

        assertNotNull(store);
        assertEquals(
            "/ai/icen/fw/workflow/cycle/guard/persistence/schema/postgres.sql",
            WorkflowCycleGuardJdbcSchema.resourcePath(
                WorkflowCycleGuardJdbcSchemaDialect.POSTGRESQL
            )
        );
    }
}
