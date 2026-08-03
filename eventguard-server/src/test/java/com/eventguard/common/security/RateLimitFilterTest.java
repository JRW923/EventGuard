package com.eventguard.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/** 通用限流 Filter 单测：阈值内放行、超阈值 429、放行白名单路径、可禁用。 */
class RateLimitFilterTest {

    @Test
    void allows_requests_within_window() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 3, 10000);
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse res = doRequest(filter, "/orders");
            assertEquals(200, res.getStatus(), "第 " + (i + 1) + " 次应在窗口内");
        }
    }

    @Test
    void rejects_when_exceeding_threshold() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 3, 10000);
        for (int i = 0; i < 3; i++) doRequest(filter, "/orders");
        MockHttpServletResponse res = doRequest(filter, "/orders");
        assertEquals(429, res.getStatus());
    }

    @Test
    void bypasses_whitelisted_paths() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 10000);
        for (int i = 0; i < 5; i++) {
            assertEquals(200, doRequest(filter, "/health").getStatus());
            assertEquals(200, doRequest(filter, "/actuator/prometheus").getStatus());
            assertEquals(200, doRequest(filter, "/gateway/callback/payment").getStatus());
        }
    }

    @Test
    void disabled_filter_passes_through() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(false, 1, 10000);
        for (int i = 0; i < 5; i++) {
            assertEquals(200, doRequest(filter, "/orders").getStatus());
        }
    }

    @Test
    void different_ips_are_independent() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 2, 10000);
        doRequest(filter, "/orders");
        doRequest(filter, "/orders");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        req.setServletPath("/orders");
        req.setRemoteAddr("10.0.0.2");
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(200, res.getStatus(), "不同 IP 独立计数");
    }

    private static MockHttpServletResponse doRequest(RateLimitFilter filter, String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path); // 生产由 Spring 设置；mock 需手动指定才能触发白名单判断
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }
}
