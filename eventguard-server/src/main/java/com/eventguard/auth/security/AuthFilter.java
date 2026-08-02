package com.eventguard.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;

/**
 * 入站认证过滤器（替换原 ApiKeyAuthFilter）：
 *  1. Authorization: Bearer <JWT> → 用户主体（权限取自 claims）；
 *  2. X-API-Key == EG_MACHINE_API_KEY → 机器主体（内部服务调用）；
 *  3. 均不合法 → 401。
 * 认证通过后把 AuthPrincipal 放入 request attribute，供 PermissionInterceptor / 控制器取用。
 *
 * /auth/login、/actuator、/health、/ws 放行：login 为公开端点，actuator/health 为运维端点，
 * /ws 交由 JwtHandshakeInterceptor 按 ?token= 校验（浏览器 WS 无法带自定义头）。
 */
@Component
@Order(1)
public class AuthFilter implements Filter {

    private final JwtService jwtService;
    private final String machineApiKey;

    public AuthFilter(JwtService jwtService, @Value("${EG_MACHINE_API_KEY:dev-machine-key}") String machineApiKey) {
        this.jwtService = jwtService;
        this.machineApiKey = machineApiKey;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getServletPath();

        if (path.startsWith("/actuator") || path.equals("/health")
                || path.startsWith("/ws") || path.equals("/auth/login")
                || path.startsWith("/gateway")) {
            chain.doFilter(request, response);
            return;
        }

        AuthPrincipal principal = resolve(req);
        if (principal == null) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid token");
            return;
        }
        req.setAttribute(AuthPrincipal.REQUEST_ATTR, principal);
        chain.doFilter(request, response);
    }

    private AuthPrincipal resolve(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                Claims c = jwtService.parse(auth.substring(7));
                return AuthPrincipal.user(
                        JwtService.uid(c),
                        c.get("username", String.class),
                        new HashSet<>(JwtService.strings(c, "permissions")));
            } catch (JwtException | IllegalArgumentException e) {
                return null;
            }
        }
        String key = req.getHeader("X-API-Key");
        if (key != null && key.equals(machineApiKey)) {
            return AuthPrincipal.machine();
        }
        return null;
    }
}
