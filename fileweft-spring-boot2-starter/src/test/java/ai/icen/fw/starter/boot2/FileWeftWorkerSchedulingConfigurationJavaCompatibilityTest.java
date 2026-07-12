package ai.icen.fw.starter.boot2;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class FileWeftWorkerSchedulingConfigurationJavaCompatibilityTest {
    @Test
    void retainsTheOriginalFourArgumentFactoryMethodForCompiledHosts() throws Exception {
        assertNotNull(FileWeftWorkerSchedulingConfiguration.class.getMethod(
                "fileWeftWorkerScheduler",
                FileWeftProperties.class,
                ObjectProvider.class,
                ObjectProvider.class,
                ObjectProvider.class));
    }
}
