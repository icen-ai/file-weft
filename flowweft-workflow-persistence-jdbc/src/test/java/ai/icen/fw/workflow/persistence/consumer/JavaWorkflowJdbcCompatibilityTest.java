package ai.icen.fw.workflow.persistence.consumer;

import ai.icen.fw.workflow.persistence.jdbc.JdbcWorkflowDefinitionStore;
import ai.icen.fw.workflow.persistence.jdbc.JdbcWorkflowRuntimePersistence;
import ai.icen.fw.workflow.persistence.jdbc.JdbcWorkflowReadyEffectJobQueue;
import ai.icen.fw.workflow.persistence.jdbc.JdbcWorkflowRuntimeComposition;
import ai.icen.fw.workflow.persistence.jdbc.WorkflowJdbcDialect;
import ai.icen.fw.workflow.persistence.migration.WorkflowFlywayMigrationRunner;
import ai.icen.fw.workflow.api.WorkflowParticipantResolver;
import ai.icen.fw.workflow.runtime.WorkflowEffectDispatchPort;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationPort;
import ai.icen.fw.workflow.runtime.WorkflowWorkerClock;
import ai.icen.fw.workflow.spi.WorkflowOrganizationAuthority;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaWorkflowJdbcCompatibilityTest {
    @Test
    void publicConstructorsRemainJava8Friendly() throws Exception {
        assertNotNull(JdbcWorkflowRuntimePersistence.class.getConstructor(DataSource.class));
        assertNotNull(JdbcWorkflowDefinitionStore.class.getConstructor(DataSource.class));
        assertNotNull(JdbcWorkflowReadyEffectJobQueue.class.getConstructor(DataSource.class));
        assertNotNull(JdbcWorkflowRuntimeComposition.class.getConstructor(
                DataSource.class,
                WorkflowRuntimeAuthorizationPort.class,
                WorkflowEffectDispatchPort.class,
                WorkflowParticipantResolver.class,
                WorkflowOrganizationAuthority.class,
                WorkflowWorkerClock.class,
                String.class,
                String.class));
        assertNotNull(WorkflowFlywayMigrationRunner.class.getConstructor(DataSource.class));
        assertEquals(WorkflowJdbcDialect.POSTGRESQL, WorkflowJdbcDialect.valueOf("POSTGRESQL"));
    }
}
