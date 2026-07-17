package ai.icen.fw.workflow.web.spring.boot2;

import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowWebBoot2AdapterCompatibilityTest {
    @Test
    void exposesPublicJavaCallableControllerAndConfigurationTypes() throws Exception {
        assertTrue(Modifier.isPublic(FlowWeftWorkflowWebBoot2Controller.class.getModifiers()));
        assertTrue(Modifier.isPublic(FlowWeftWorkflowWebBoot2AutoConfiguration.class.getModifiers()));
        assertNotNull(FlowWeftWorkflowWebBoot2Controller.class.getMethod(
            "capabilities",
            HttpServletRequest.class
        ));
        assertNotNull(FlowWeftWorkflowWebBoot2JsonCodec.class.getConstructor(
            com.fasterxml.jackson.databind.ObjectMapper.class
        ));
    }
}
