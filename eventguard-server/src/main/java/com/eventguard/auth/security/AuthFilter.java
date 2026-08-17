package com.eventguard.auth.security;

import com.eventguard.auth.config.ProductionSecurityGuard;
import com.eventguard.auth.model.AppUser;
import com.eventguard.auth.repository.UserRepository;
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
import java.util.Optional;

/**
 * 入站认证过滤器（替换原 ApiKeyAuthFilter）：
 *  1. Authorization: Bearer <JWT> → 用户主体（权限取自 claims）；
 *  2. X-API-Key == EG_MACHINE_API_KEY → 机器主体（内部服务调用）；
 *  3. 均不合法 → 401。
 * 认证通过后把 AuthPrincipal 放入 request attribute，供 PermissionInterceptor / 控制器取用。
 *
 * P2-16 令牌吊销：JWT 带 tokenVersion（tv），与 auth_user.token_version 比对；
 * 不一致（登出所有设备/改密后递增）则视为已吊销 → 401。
 * ponytail: 每次请求多一次按主键查 token_version，管理台量级可接受；极端规模可缓存到本地 TTL。
 *
 * /auth/login、/actuator、/health、/ws、/gateway 放行：login 为公开端点，actuator/health 为运维端点，
 * /ws 交由 JwtHandshakeInterceptor 按 ?token= 校验（浏览器 WS 无法带自定义头），
 * /gateway 由回调端点内自行校验机器密钥。
 * /site-profile GET 放行：个人主页公开可读（PUT 仍需认证+user:manage）。
 */
@Component
@Order(1)
public class AuthFilter implements Filter {

    private final JwtService jwtService;
    private final String machineApiKey;
    private final UserRepository userRepository;

    public AuthFilter(JwtService jwtService,
                      @Value("${EG_MACHINE_API_KEY:" + ProductionSecurityGuard.DEFAULT_MACHINE_KEY + "}")
                      String machineApiKey,
                      UserRepository userRepository) {
        this.jwtService = jwtService;
        this.machineApiKey = machineApiKey;
        this.userRepository = userRepository;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getServletPath();

        if (path.startsWith("/actuator") || path.equals("/health")
                || path.startsWith("/ws") || path.equals("/auth/login")
                || path.startsWith("/gateway")
                // 个人主页内容只读公开（GET）；写接口由 @RequirePermission(user:manage) 在控制器层拦截
                || (path.equals("/site-profile") && "GET".equalsIgnoreCase(req.getMethod()))) {
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
                Long uid = JwtService.uid(c);
                // P2-16 令牌吊销校验：库中 token_version 与 JWT 携带的 tv 不一致 → 拒绝
                if (!tokenVersionValid(uid, JwtService.tokenVersion(c))) {
                    return null;
                }
                return AuthPrincipal.user(
                        uid,
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

    private boolean tokenVersionValid(long uid, int jwtTokenVersion) {
        Optional<AppUser> user = userRepository.findById(uid);
        return user.isPresent() && user.get().getTokenVersion() == jwtTokenVersion;
    }
}
