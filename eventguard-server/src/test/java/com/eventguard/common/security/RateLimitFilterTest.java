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

    @Test
    void trusts_x_real_ip_behind_proxy() throws Exception {
        // 反代场景：所有请求 remoteAddr 都是 nginx 容器 IP，但 X-Real-IP 是真实用户 IP，
        // 限流必须按 X-Real-IP 分桶，否则一人刷爆全站 429。
        RateLimitFilter filter = new RateLimitFilter(true, 2, 10000);
        for (int i = 0; i < 2; i++) doRequest(filter, "/orders", "1.2.3.4");
        assertEquals(429, doRequest(filter, "/orders", "1.2.3.4").getStatus(), "同 X-Real-IP 超阈值应 429");
        assertEquals(200, doRequest(filter, "/orders", "5.6.7.8").getStatus(), "不同 X-Real-IP 独立计数，应放行");
    }

    @Test
    void x_real_ip_takes_priority_over_remote_addr() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(true, 1, 10000);
        // remoteAddr 相同、X-Real-IP 不同 → 按 X-Real-IP 分桶，均放行
        for (int i = 0; i < 5; i++) {
            assertEquals(200, doRequest(filter, "/orders", "user-" + i).getStatus());
        }
        // 同一 X-Real-IP 二次请求 → 429
        assertEquals(429, doRequest(filter, "/orders", "user-0").getStatus());
    }

    private static MockHttpServletResponse doRequest(RateLimitFilter filter, String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path); // 生产由 Spring 设置；mock 需手动指定才能触发白名单判断
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    private static MockHttpServletResponse doRequest(RateLimitFilter filter, String path, String realIp)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setServletPath(path);
        req.setRemoteAddr("172.18.0.1"); // 反代容器 IP 恒定
        req.addHeader("X-Real-IP", realIp); // nginx 每请求覆盖写入
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }
}
