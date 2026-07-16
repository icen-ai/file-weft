package ai.icen.fw.workflow.notification.persistence.jdbc;

import ai.icen.fw.workflow.runtime.WorkflowNotificationStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowNotificationJdbcJavaCompatibilityTest {
    @Test
    void publicBoundaryIsUsableFromJava8() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:workflow-notification-java;MODE=MySQL");

        WorkflowNotificationStore store = new JdbcWorkflowNotificationStore(
            dataSource,
            WorkflowNotificationJdbcDialect.MYSQL
        );

        assertNotNull(store);
        assertTrue(WorkflowNotificationStore.class.isAssignableFrom(store.getClass()));
        assertEquals("035", WorkflowNotificationJdbcMigrations.VERSION);
        assertEquals(
            "classpath:ai/icen/fw/workflow/notification/db/migration/mysql",
            WorkflowNotificationJdbcMigrations.location(WorkflowNotificationJdbcDialect.MYSQL)
        );
    }
}
