package com.fileweft.web.runtime.v1;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaIdempotencyKeyParserInteropTest {
    @Test
    void parsesExactSingleValuesAndRejectsRepeatedHeadersFromJava() {
        assertEquals(
            "java-request-1",
            IdempotencyKeyParser.parse(Collections.singletonList("java-request-1"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> IdempotencyKeyParser.parse(Arrays.asList("java-request-1", "java-request-2"))
        );
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKeyParser.parse(null));
    }
}
