package com.eventguard.common.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 校验入站请求的 X-API-Key 头；缺失/不匹配返回 401。 */
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
        if (path.startsWith("/actuator") || path.equals("/health")) {
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
