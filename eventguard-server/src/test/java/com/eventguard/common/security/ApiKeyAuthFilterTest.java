package com.eventguard.common.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthFilterTest {

    private final ApiKeyValidator validator = new ApiKeyValidator("secret-key");
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(validator);

    @Test
    void missingKey_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        req.setServletPath("/orders");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void validKey_proceedsChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        req.setServletPath("/orders");
        req.addHeader("X-API-Key", "secret-key");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void healthEndpoint_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/health");
        req.setServletPath("/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }
}
