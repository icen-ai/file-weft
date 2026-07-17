package ai.icen.fw.agent.persistence.consumer;

import ai.icen.fw.agent.persistence.jdbc.AgentJdbcDialect;
import ai.icen.fw.agent.persistence.jdbc.JdbcAgentDurableRunStore;
import ai.icen.fw.agent.runtime.AgentDurableMementoCodec;
import ai.icen.fw.agent.runtime.AgentPendingOperation;
import ai.icen.fw.agent.runtime.AgentPendingOperationMemento;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaAgentDurableJdbcCompatibilityTest {
    @Test
    void javaCanConstructTheStoreAndUseExplicitOperationMementos() throws Exception {
        assertNotNull(JdbcAgentDurableRunStore.class.getConstructor(DataSource.class));
        assertNotNull(JdbcAgentDurableRunStore.class.getConstructor(
                DataSource.class,
                AgentJdbcDialect.class));
        assertNotNull(JdbcAgentDurableRunStore.class.getConstructor(
                DataSource.class,
                AgentJdbcDialect.class,
                AgentDurableMementoCodec.class));
        assertNotNull(AgentDurableMementoCodec.class.getMethod(
                "encodeOperation",
                AgentPendingOperation.class));
        assertNotNull(AgentDurableMementoCodec.class.getMethod(
                "decodeOperation",
                AgentPendingOperationMemento.class));
        assertNotNull(AgentPendingOperationMemento.class.getMethod(
                "restore",
                byte[].class,
                String.class));
    }
}
