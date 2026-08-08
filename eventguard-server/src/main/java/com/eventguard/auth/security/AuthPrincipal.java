package com.eventguard.auth.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * 已认证主体统一抽象：用户主体（JWT）或机器主体（EG_MACHINE_API_KEY）。
 * 权限拦截统一调 hasPermission(code)，机器主体固定权限集使其天然无法写订单/管用户。
 */
public final class AuthPrincipal {

    public static final String REQUEST_ATTR = "EG_AUTH_PRINCIPAL";

    /** 机器主体固定权限集：内部服务（AI）所需——读订单 + 规则评估 + 发起补偿 Saga（Item 6b）。 */
    private static final Set<String> MACHINE_PERMISSIONS = Set.of("order:read", "anomaly:evaluate", "compensation:execute");

    private final boolean machine;
    private final Long userId;
    private final String username;
    private final Set<String> permissions;

    private AuthPrincipal(boolean machine, Long userId, String username, Set<String> permissions) {
        this.machine = machine;
        this.userId = userId;
        this.username = username;
        this.permissions = permissions;
    }

    public static AuthPrincipal user(Long userId, String username, Set<String> permissions) {
        return new AuthPrincipal(false, userId, username, permissions);
    }

    public static AuthPrincipal machine() {
        return new AuthPrincipal(true, null, "machine", MACHINE_PERMISSIONS);
    }

    public boolean isMachine() {
        return machine;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public boolean hasPermission(String code) {
        return permissions.contains(code);
    }

    /** 从请求中取 AuthFilter 放入的当前主体（未认证时不会到达需要它的控制器）。 */
    public static AuthPrincipal from(HttpServletRequest req) {
        return (AuthPrincipal) req.getAttribute(REQUEST_ATTR);
    }
}
