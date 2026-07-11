package com.fileweft.web.runtime.v1;

import com.fileweft.application.security.ApplicationForbiddenException;
import com.fileweft.web.api.ApiResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaV1ApiResponseFactoryInteropTest {

    @Test
    void exposesJavaFriendlyResponseAndFailureFactories() {
        V1ApiResponseFactory factory = new V1ApiResponseFactory();

        ApiResponse<String> success = factory.success("payload", "trace-java");
        ApiHttpFailure failure = factory.failure(new ApplicationForbiddenException("host policy detail"), "trace-java");

        assertEquals("payload", success.getData());
        assertEquals("trace-java", success.getTraceId());
        assertNull(success.getError());
        assertEquals(403, failure.getStatus().getStatusCode());
        assertEquals("FORBIDDEN", failure.getResponse().getCode());
        assertEquals("Access denied.", failure.getResponse().getMessage());
    }
}
