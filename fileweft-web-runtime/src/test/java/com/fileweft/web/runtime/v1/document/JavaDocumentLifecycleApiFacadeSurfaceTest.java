package com.fileweft.web.runtime.v1.document;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDocumentLifecycleApiFacadeSurfaceTest {
    @Test
    void exposesOnlyTheSafeCandidateResolvingConstructorToJavaHosts() {
        List<Constructor<?>> callable = Arrays.stream(DocumentLifecycleApiFacade.class.getConstructors())
            .filter(constructor -> !constructor.isSynthetic())
            .collect(Collectors.toList());

        assertEquals(1, callable.size());
        assertArrayEquals(
            new Class<?>[]{int.class, List.class, List.class, List.class, List.class},
            callable.get(0).getParameterTypes()
        );
        assertFalse(Arrays.stream(DocumentLifecycleApiFacade.class.getDeclaredConstructors())
            .filter(constructor -> Modifier.isPublic(constructor.getModifiers()) && !constructor.isSynthetic())
            .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
            .anyMatch(type -> type.getSimpleName().endsWith("Commands")));
    }

    @Test
    void keepsTestFactoriesAndCommandPortsOutOfTheJavaSourceSurface() {
        assertFalse(Arrays.stream(DocumentLifecycleApiFacade.class.getMethods())
            .filter(method -> !method.isSynthetic())
            .map(Method::getName)
            .anyMatch(name -> name.startsWith("forTesting")));
        Arrays.stream(DocumentLifecycleApiFacade.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("LifecycleCommands") ||
                type.getSimpleName().equals("ReviewCommands"))
            .forEach(type -> assertTrue(Modifier.isPrivate(type.getModifiers())));
    }
}
