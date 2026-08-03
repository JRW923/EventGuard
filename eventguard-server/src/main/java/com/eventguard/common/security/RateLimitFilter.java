package com.eventguard.common.security;

import com.eventguard.common.metrics.EventGuardMetrics;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用请求限流（P0-4）：per-IP 滑动窗口计数，超过阈值返回 429。
 * <p>
 * 放在 AuthFilter 之前（@Order(0)），无论是否登录都限流；登录防爆破仍由
 * LoginAttemptGuard 独立负责。默认每个 IP 每 10 秒最多 60 次请求。
 * <p>
 * 放行路径：/actuator、/health、/gateway（外部网关回调可能高频且来自固定 IP，
 * 不应被普通用户限流规则误伤）、/ws（WebSocket 握手，不走 REST 限流）。
 * ponytail: 单实例内存窗口，多副本部署需共享存储（Redis），记入已知上限。
 */
@Component
@Order(0)
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final int maxRequests;
    private final long windowMs;
    private final boolean enabled;
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private EventGuardMetrics metrics;

    public RateLimitFilter(
            @Value("${eg.rate-limit.enabled:true}") boolean enabled,
            @Value("${eg.rate-limit.max-requests:60}") int maxRequests,
            @Value("${eg.rate-limit.window-ms:10000}") long windowMs) {
        this.enabled = enabled;
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getServletPath();
        if (path.startsWith("/actuator") || path.equals("/health")
                || path.startsWith("/gateway") || path.startsWith("/ws")) {
            chain.doFilter(request, response);
            return;
        }

        String ip = clientIp(req);
        if (allow(ip)) {
            chain.doFilter(request, response);
        } else {
            log.warn("[限流] IP {} 请求过频，拒绝", ip);
            if (metrics != null) {
                metrics.counter("eventguard.ratelimit.rejected");
            }
            HttpServletResponse res = (HttpServletResponse) response;
            res.setStatus(429); // HttpServletResponse.SC_TOO_MANY_REQUESTS（servlet 版本无此常量，用字面量）
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试\"}");
        }
    }

    /** 滑动窗口判断：窗口内计数 < maxRequests 则放行并 +1，否则拒绝。 */
    private boolean allow(String ip) {
        long now = System.currentTimeMillis();
        long[] window = windows.compute(ip, (k, v) -> {
            if (v == null || now - v[0] >= windowMs) {
                return new long[]{now, 1}; // 新窗口
            }
            v[1]++;
            return v;
        });
        return window[1] <= maxRequests;
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
