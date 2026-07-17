package ai.icen.fw.agent.web.spring.boot3;

import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaAgentWebBoot3AdapterCompatibilityTest {
    @Test
    void exposesPublicJavaCallableControllerAndConfigurationTypes() throws Exception {
        assertTrue(Modifier.isPublic(FlowWeftAgentWebBoot3Controller.class.getModifiers()));
        assertTrue(Modifier.isPublic(FlowWeftAgentWebBoot3AutoConfiguration.class.getModifiers()));
        assertTrue(Modifier.isPublic(FlowWeftAgentWebBoot3ApplicationPorts.class.getModifiers()));
        assertNotNull(FlowWeftAgentWebBoot3Controller.class.getMethod(
            "listRunEvents",
            String.class,
            HttpServletRequest.class
        ));
        assertNotNull(FlowWeftAgentWebBoot3JsonCodec.class.getConstructor(
            com.fasterxml.jackson.databind.ObjectMapper.class
        ));
        assertNotNull(FlowWeftAgentWebBoot3Response.class.getConstructor(
            String.class,
            Object.class,
            boolean.class
        ));
    }
}
