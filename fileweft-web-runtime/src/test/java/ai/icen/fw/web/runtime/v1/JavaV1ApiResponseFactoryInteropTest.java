package ai.icen.fw.web.runtime.v1;

import ai.icen.fw.application.security.ApplicationForbiddenException;
import ai.icen.fw.domain.document.DocumentNumberAlreadyExistsException;
import ai.icen.fw.web.api.ApiResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaV1ApiResponseFactoryInteropTest {

    @Test
    void exposesJavaFriendlyResponseAndFailureFactories() {
        V1ApiResponseFactory factory = new V1ApiResponseFactory();

        ApiResponse<String> success = factory.success("payload", "trace-java");
        ApiHttpFailure failure = factory.failure(new ApplicationForbiddenException("host policy detail"), "trace-java");
        ApiHttpFailure conflict = factory.failure(new DocumentNumberAlreadyExistsException("private-number"), "trace-java");
        ApiHttpFailure method = factory.failure(new V1MethodNotAllowedException(), "trace-java");
        ApiHttpFailure range = factory.failure(new V1RangeNotSupportedException(), "trace-java");

        assertEquals("payload", success.getData());
        assertEquals("trace-java", success.getTraceId());
        assertNull(success.getError());
        assertEquals(403, failure.getStatus().getStatusCode());
        assertEquals("FORBIDDEN", failure.getResponse().getCode());
        assertEquals("Access denied.", failure.getResponse().getMessage());
        assertEquals(409, conflict.getStatus().getStatusCode());
        assertEquals("CONFLICT", conflict.getResponse().getCode());
        assertEquals(405, method.getStatus().getStatusCode());
        assertEquals("METHOD_NOT_ALLOWED", method.getResponse().getCode());
        assertEquals(416, range.getStatus().getStatusCode());
        assertEquals("RANGE_NOT_SUPPORTED", range.getResponse().getCode());
        assertEquals("Method is not allowed.", new V1MethodNotAllowedException().getMessage());
        assertEquals("Range requests are not supported.", new V1RangeNotSupportedException().getMessage());
    }
}
