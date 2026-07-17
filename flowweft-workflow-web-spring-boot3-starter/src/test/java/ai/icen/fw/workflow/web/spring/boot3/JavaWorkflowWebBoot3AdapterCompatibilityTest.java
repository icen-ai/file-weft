package ai.icen.fw.workflow.web.spring.boot3;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowWebBoot3AdapterCompatibilityTest {
    @Test
    void exposesPublicJavaCallableControllerAndConfigurationTypes() throws Exception {
        assertTrue(Modifier.isPublic(FlowWeftWorkflowWebBoot3Controller.class.getModifiers()));
        assertTrue(Modifier.isPublic(FlowWeftWorkflowWebBoot3AutoConfiguration.class.getModifiers()));
        assertNotNull(FlowWeftWorkflowWebBoot3Controller.class.getMethod(
            "capabilities",
            HttpServletRequest.class
        ));
        assertNotNull(FlowWeftWorkflowWebBoot3JsonCodec.class.getConstructor(
            com.fasterxml.jackson.databind.ObjectMapper.class
        ));
    }
}
