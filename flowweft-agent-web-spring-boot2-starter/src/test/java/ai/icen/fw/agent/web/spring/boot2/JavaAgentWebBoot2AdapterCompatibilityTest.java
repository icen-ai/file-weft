package ai.icen.fw.agent.web.spring.boot2;

import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaAgentWebBoot2AdapterCompatibilityTest {
    @Test
    void exposesPublicJavaCallableControllerAndConfigurationTypes() throws Exception {
        assertTrue(Modifier.isPublic(FlowWeftAgentWebBoot2Controller.class.getModifiers()));
        assertTrue(Modifier.isPublic(FlowWeftAgentWebBoot2AutoConfiguration.class.getModifiers()));
        assertTrue(Modifier.isPublic(FlowWeftAgentWebBoot2ApplicationPorts.class.getModifiers()));
        assertNotNull(FlowWeftAgentWebBoot2Controller.class.getMethod(
            "listRunEvents",
            String.class,
            HttpServletRequest.class
        ));
        assertNotNull(FlowWeftAgentWebBoot2JsonCodec.class.getConstructor(
            com.fasterxml.jackson.databind.ObjectMapper.class
        ));
        assertNotNull(FlowWeftAgentWebBoot2Response.class.getConstructor(
            String.class,
            Object.class,
            boolean.class
        ));
    }
}
