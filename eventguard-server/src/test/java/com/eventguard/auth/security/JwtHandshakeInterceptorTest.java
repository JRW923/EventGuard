package com.eventguard.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JwtHandshakeInterceptor：有效 token + anomaly:view 才放行握手。 */
class JwtHandshakeInterceptorTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final JwtService jwt = new JwtService(SECRET, 60);
    private final HandshakeInterceptor interceptor = new JwtHandshakeInterceptor(jwt);

    private boolean handshake(String query) throws Exception {
        MockHttpServletRequest base = new MockHttpServletRequest("GET", "/ws/anomalies");
        if (query != null) {
            base.setQueryString(query);
        }
        ServletServerHttpRequest req = new ServletServerHttpRequest(base);
        ServletServerHttpResponse res = new ServletServerHttpResponse(new MockHttpServletResponse());
        return interceptor.beforeHandshake(req, res, null, new HashMap<>());
    }

    @Test
    void missingToken_rejects() throws Exception {
        assertFalse(handshake(null));
    }

    @Test
    void tokenWithAnomalyView_accepts() throws Exception {
        String token = jwt.issue(1L, "admin", null,
                List.of("ADMIN"), List.of("anomaly:view"), false);
        assertTrue(handshake("token=" + token));
    }

    @Test
    void tokenWithoutPermission_rejects() throws Exception {
        String token = jwt.issue(1L, "admin", null,
                List.of("ADMIN"), List.of("order:read"), false);
        assertFalse(handshake("token=" + token));
    }

    @Test
    void invalidToken_rejects() throws Exception {
        assertFalse(handshake("token=not-a-jwt"));
    }
}
