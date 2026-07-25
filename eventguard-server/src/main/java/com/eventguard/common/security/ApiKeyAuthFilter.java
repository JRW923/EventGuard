package com.eventguard.common.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 校验入站请求的 X-API-Key 头；缺失/不匹配返回 401。WS 升级请求（/ws/**）放行，
 *  交由 ApiKeyHandshakeInterceptor 按 ?api_key= 查询参数鉴权（浏览器 WS 无法带自定义头）。 */
@Component
@Order(1)
public class ApiKeyAuthFilter implements Filter {

    private final ApiKeyValidator validator;

    public ApiKeyAuthFilter(ApiKeyValidator validator) {
        this.validator = validator;
    }

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request,
                          jakarta.servlet.ServletResponse response,
                          FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getServletPath();
        // ponytail: /ws 放行给握手拦截器查 api_key；/health、/actuator 为运维端点免鉴权
        if (path.startsWith("/actuator") || path.equals("/health") || path.startsWith("/ws")) {
            chain.doFilter(request, response);
            return;
        }
        if (!validator.isValid(req.getHeader("X-API-Key"))) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid X-API-Key");
            return;
        }
        chain.doFilter(request, response);
    }
}
