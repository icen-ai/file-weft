package com.fileweft.agent;

import com.fileweft.application.agent.AgentResultRepository;
import com.fileweft.application.task.BackgroundTaskLease;
import com.fileweft.application.task.LeasedTaskHandler;
import com.fileweft.application.task.TaskMutationRepository;
import com.fileweft.application.transaction.ApplicationTransaction;
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
