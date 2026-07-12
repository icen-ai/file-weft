package ai.icen.fw.agent;

import ai.icen.fw.application.agent.AgentResultRepository;
import ai.icen.fw.application.task.BackgroundTaskLease;
import ai.icen.fw.application.task.LeasedTaskHandler;
import ai.icen.fw.application.task.TaskMutationRepository;
import ai.icen.fw.application.transaction.ApplicationTransaction;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaAgentTaskHandlerInteropTest {
    @Test
    void retainsLegacyAndExposesFencedJavaConstructors() throws Exception {
        assertNotNull(AgentTaskHandler.class.getConstructor(
                AgentTaskOrchestrator.class,
                AgentResultRepository.class,
                ApplicationTransaction.class,
                Clock.class
        ));
        assertNotNull(AgentTaskHandler.class.getConstructor(
                AgentTaskOrchestrator.class,
                AgentResultRepository.class,
                ApplicationTransaction.class,
                Clock.class,
                TaskMutationRepository.class
        ));
        assertTrue(LeasedTaskHandler.class.isAssignableFrom(AgentTaskHandler.class));
        assertNotNull(AgentTaskHandler.class.getMethod("handle", BackgroundTaskLease.class));
        assertNotNull(AgentTaskHandler.class.getMethod("onExhausted", BackgroundTaskLease.class, String.class));
    }
}
