package com.eventguard.auth.config;

import com.eventguard.auth.security.PermissionInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册权限拦截器 + 显式 CORS 策略（P2-17）。
 * <p>
 * CORS 默认关闭跨域（同源部署 + nginx 反代，无需跨域头）；如需对第三方开放 API，
 * 用 {@code EG_CORS_ALLOWED_ORIGINS}（逗号分隔）显式配置允许来源。鉴权走 Authorization 头
 * （非 cookie），故不发送 allow-credentials，避免与通配来源冲突。
 */
@Configuration
public class AuthWebMvcConfig implements WebMvcConfigurer {

    private final PermissionInterceptor interceptor;
    private final String allowedOrigins;

    public AuthWebMvcConfig(PermissionInterceptor interceptor,
                            @Value("${EG_CORS_ALLOWED_ORIGINS:}") String allowedOrigins) {
        this.interceptor = interceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return; // 未配置允许来源 → 保持同源策略，不开放跨域
        }
        String[] origins = allowedOrigins.split(",");
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
