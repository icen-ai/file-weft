package ai.icen.fw.application.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.icen.fw.core.context.DoctorCheckContext;
import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.core.result.DoctorStatus;
import org.junit.jupiter.api.Test;

class JavaDeploymentSafetyDoctorCheckerInteropTest {
    @Test
    void exposesJavaFriendlyDefaultAndExplicitConstructors() {
        DoctorCheckContext context = new DoctorCheckContext(new Identifier("java-tenant"));

        assertEquals(DoctorStatus.HEALTHY, new DeploymentSafetyDoctorChecker().check(context).getStatus());
        assertEquals(
            DoctorStatus.WARNING,
            new DeploymentSafetyDoctorChecker(true, true).check(context).getStatus()
        );
    }
}
