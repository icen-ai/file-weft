package ai.icen.fw.agent.persistence.consumer;

import ai.icen.fw.agent.persistence.jdbc.AgentEvaluationJdbcTestFixture;
import ai.icen.fw.agent.persistence.jdbc.AgentEvaluationStateMemento;
import ai.icen.fw.agent.persistence.jdbc.AgentEvaluationStateMementoCodec;
import ai.icen.fw.agent.persistence.jdbc.AgentJdbcDialect;
import ai.icen.fw.agent.persistence.jdbc.JdbcAgentEvaluationDurableStore;
import ai.icen.fw.agent.persistence.migration.AgentFlywayMigrationRunner;
import ai.icen.fw.agent.runtime.AgentEvaluationRunState;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaAgentEvaluationJdbcCompatibilityTest {
    @Test
    void javaCanUsePublicConstructorsAndTheExplicitMementoCodec() throws Exception {
        assertNotNull(JdbcAgentEvaluationDurableStore.class.getConstructor(DataSource.class));
        assertNotNull(JdbcAgentEvaluationDurableStore.class.getConstructor(
                DataSource.class,
                AgentJdbcDialect.class,
                AgentEvaluationStateMementoCodec.class));
        assertNotNull(AgentFlywayMigrationRunner.class.getConstructor(DataSource.class));

        AgentEvaluationStateMementoCodec codec = new AgentEvaluationStateMementoCodec();
        AgentEvaluationRunState initial = AgentEvaluationJdbcTestFixture.initial(
                "evaluation-java",
                "tenant-java",
                "idempotency-java");
        AgentEvaluationStateMemento memento = codec.encode(initial);
        AgentEvaluationRunState restored = codec.decode(memento);

        assertEquals(initial.getRequestBindingDigest(), restored.getRequestBindingDigest());
        assertEquals(initial.getSuite().getSuiteDigest(), restored.getSuite().getSuiteDigest());
        assertEquals(AgentEvaluationStateMementoCodec.FORMAT_VERSION, memento.getFormatVersion());
        assertEquals(AgentJdbcDialect.POSTGRESQL, AgentJdbcDialect.valueOf("POSTGRESQL"));
    }
}
