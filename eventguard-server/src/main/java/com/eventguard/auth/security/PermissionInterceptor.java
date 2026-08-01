package com.eventguard.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 权限拦截器：读取 handler 方法（或类）上的 @RequirePermission，校验 AuthFilter 放入的 AuthPrincipal。
 * 无注解的方法放行（但认证仍由 AuthFilter 兜底，未认证请求到不了这里）。
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequirePermission rp = hm.getMethodAnnotation(RequirePermission.class);
        if (rp == null) {
            rp = hm.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (rp == null) {
            return true;
        }
        AuthPrincipal principal = (AuthPrincipal) request.getAttribute(AuthPrincipal.REQUEST_ATTR);
        if (principal == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing authentication");
            return false;
        }
        if (!principal.hasPermission(rp.value())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "权限不足：" + rp.value());
            return false;
        }
        return true;
    }
}
