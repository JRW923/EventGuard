package com.eventguard.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyHandshakeInterceptorTest {

    @Test
    void missingApiKeyParam_rejectsHandshake() {
        ApiKeyValidator validator = new ApiKeyValidator("secret");
        ApiKeyHandshakeInterceptor interceptor = new ApiKeyHandshakeInterceptor(validator);

        MockHttpServletRequest noKey = new MockHttpServletRequest("GET", "/ws/anomalies");
        assertFalse(interceptor.beforeHandshake(new ServletServerHttpRequest(noKey), null, null, new HashMap<>()));
    }

    @Test
    void validApiKeyParam_acceptsHandshake() {
        ApiKeyValidator validator = new ApiKeyValidator("secret");
        ApiKeyHandshakeInterceptor interceptor = new ApiKeyHandshakeInterceptor(validator);

        MockHttpServletRequest withKey = new MockHttpServletRequest("GET", "/ws/anomalies?api_key=secret");
        assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(withKey), null, null, new HashMap<>()));
    }
}
