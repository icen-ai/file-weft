package ai.icen.fw.workflow.persistence.consumer;

import ai.icen.fw.workflow.persistence.jdbc.JdbcWorkflowHumanInputStore;
import ai.icen.fw.workflow.persistence.jdbc.WorkflowJdbcDialect;
import ai.icen.fw.workflow.runtime.WorkflowHumanInputIdempotencyPort;
import ai.icen.fw.workflow.runtime.WorkflowMentionNotificationCheckpointPort;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowHumanInputJdbcCompatibilityTest {
    @Test
    void exposesJavaFriendlyConstructorsAndRuntimePort() throws Exception {
        assertNotNull(JdbcWorkflowHumanInputStore.class.getConstructor(DataSource.class));
        assertNotNull(JdbcWorkflowHumanInputStore.class.getConstructor(
                DataSource.class,
                WorkflowJdbcDialect.class
        ));
        assertTrue(WorkflowHumanInputIdempotencyPort.class.isAssignableFrom(JdbcWorkflowHumanInputStore.class));
        assertTrue(WorkflowMentionNotificationCheckpointPort.class.isAssignableFrom(
                JdbcWorkflowHumanInputStore.class
        ));
    }
}
