package com.eventguard.auth.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** AuthFilter：JWT / 机器密钥 / 公开端点放行 / 未认证 401。 */
class AuthFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final JwtService jwt = new JwtService(SECRET, 60);
    private final AuthFilter filter = new AuthFilter(jwt, "machine-key");

    private MockHttpServletRequest req;
    private MockHttpServletResponse res;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        req = new MockHttpServletRequest();
        res = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @Test
    void missingAuth_returns401() throws Exception {
        filter.doFilter(req, res, chain);
        assertEquals(401, res.getStatus());
        assertNull(req.getAttribute(AuthPrincipal.REQUEST_ATTR));
    }

    @Test
    void validJwt_setsPrincipal() throws Exception {
        String token = jwt.issue(1L, "admin", "管理员",
                List.of("ADMIN"), List.of("order:read"), false);
        req.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(req, res, chain);
        assertEquals(200, res.getStatus());
        AuthPrincipal p = (AuthPrincipal) req.getAttribute(AuthPrincipal.REQUEST_ATTR);
        assertNotNull(p);
        assertEquals("admin", p.getUsername());
    }

    @Test
    void invalidJwt_returns401() throws Exception {
        req.addHeader("Authorization", "Bearer not-a-jwt");
        filter.doFilter(req, res, chain);
        assertEquals(401, res.getStatus());
    }

    @Test
    void machineKey_setsMachinePrincipal() throws Exception {
        req.addHeader("X-API-Key", "machine-key");
        filter.doFilter(req, res, chain);
        assertEquals(200, res.getStatus());
        AuthPrincipal p = (AuthPrincipal) req.getAttribute(AuthPrincipal.REQUEST_ATTR);
        assertNotNull(p);
        assertEquals(true, p.isMachine());
        assertEquals(true, p.hasPermission("order:read"));
        assertEquals(false, p.hasPermission("order:write"));
    }

    @Test
    void loginEndpoint_passesThrough() throws Exception {
        req.setServletPath("/auth/login");
        filter.doFilter(req, res, chain);
        assertEquals(200, res.getStatus());
        assertNull(req.getAttribute(AuthPrincipal.REQUEST_ATTR));
    }

    @Test
    void healthEndpoint_passesThrough() throws Exception {
        req.setServletPath("/health");
        filter.doFilter(req, res, chain);
        assertEquals(200, res.getStatus());
    }

    @Test
    void wsEndpoint_passesThrough() throws Exception {
        // WS 认证交给握手拦截器按 ?token= 校验
        req.setServletPath("/ws/anomalies");
        filter.doFilter(req, res, chain);
        assertEquals(200, res.getStatus());
        assertNull(req.getAttribute(AuthPrincipal.REQUEST_ATTR));
    }
}
