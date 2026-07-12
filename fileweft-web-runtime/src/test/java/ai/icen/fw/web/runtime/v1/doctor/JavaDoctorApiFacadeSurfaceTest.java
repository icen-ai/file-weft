package ai.icen.fw.web.runtime.v1.doctor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDoctorApiFacadeSurfaceTest {
    @Test
    void exposesOnlyOrdinaryJavaFriendlyFormalDoctorMethods() throws Exception {
        assertNotNull(DoctorApiFacade.class.getConstructor(
            int.class,
            List.class,
            List.class,
            List.class,
            List.class,
            List.class
        ));
        assertEquals(String.class, DoctorApiFacade.class.getMethod("inspectDocument", String.class).getParameterTypes()[0]);
        assertEquals(2, DoctorApiFacade.class.getMethod("scheduleDocument", String.class, String.class).getParameterCount());
        assertEquals(2, DoctorApiFacade.class.getMethod("task", String.class, String.class).getParameterCount());
        assertEquals(0, DoctorApiFacade.class.getMethod("inspectSystem").getParameterCount());
        assertTrue(
            java.util.Arrays.stream(DoctorApiFacade.class.getMethods())
                .filter(method -> !method.isSynthetic())
                .noneMatch(method ->
                    method.getReturnType().getName().startsWith("kotlin.") ||
                        java.util.Arrays.stream(method.getParameterTypes())
                            .anyMatch(type -> type.getName().startsWith("kotlin."))
                )
        );
    }
}
