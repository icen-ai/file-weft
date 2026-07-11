package com.fileweft.application.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class JavaApplicationAuthorizationExceptionInteropTest {

    @Test
    void exposesConcreteAuthenticationAndAuthorizationTypesToJava() {
        ApplicationAuthorizationException unauthenticated = new ApplicationUnauthenticatedException();
        ApplicationAuthorizationException forbidden = new ApplicationForbiddenException("policy denied");

        assertInstanceOf(ApplicationUnauthenticatedException.class, unauthenticated);
        assertInstanceOf(ApplicationForbiddenException.class, forbidden);
        assertEquals(ApplicationUnauthenticatedException.DEFAULT_MESSAGE, unauthenticated.getMessage());
        assertEquals("policy denied", forbidden.getMessage());
    }
}
