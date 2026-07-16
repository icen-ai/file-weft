package ai.icen.fw.workflow.persistence.consumer;

import ai.icen.fw.workflow.persistence.jdbc.JdbcWorkflowNotificationStore;
import ai.icen.fw.workflow.persistence.jdbc.WorkflowJdbcDialect;
import ai.icen.fw.workflow.runtime.WorkflowNotificationStore;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowNotificationJdbcCompatibilityTest {
    @Test
    void exposesJavaFriendlyConstructorsAndRuntimePort() throws Exception {
        assertNotNull(JdbcWorkflowNotificationStore.class.getConstructor(DataSource.class));
        assertNotNull(JdbcWorkflowNotificationStore.class.getConstructor(
            DataSource.class,
            WorkflowJdbcDialect.class
        ));
        assertTrue(WorkflowNotificationStore.class.isAssignableFrom(JdbcWorkflowNotificationStore.class));
    }
}
