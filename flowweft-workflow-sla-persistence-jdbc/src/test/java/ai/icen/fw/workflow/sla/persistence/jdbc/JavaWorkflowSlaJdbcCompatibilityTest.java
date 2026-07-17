package ai.icen.fw.workflow.sla.persistence.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaWorkflowSlaJdbcCompatibilityTest {
    @Test
    void publicSurfaceIsJava8Friendly() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:sla-java;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");

        assertNotNull(new JdbcWorkflowSlaDurableStore(dataSource));
        assertNotNull(new JdbcWorkflowSlaDurableStore(dataSource, WorkflowSlaJdbcDialect.POSTGRESQL));
        assertEquals(
            "classpath:ai/icen/fw/workflow/sla/db/migration/postgres",
            WorkflowSlaJdbcMigrations.location(WorkflowSlaJdbcMigrationDialect.POSTGRESQL)
        );
    }
}
