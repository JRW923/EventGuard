package com.eventguard.auth.config;

import com.eventguard.auth.security.PermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册权限拦截器，对所有 Controller 方法执行 @RequirePermission 校验。 */
@Configuration
public class AuthWebMvcConfig implements WebMvcConfigurer {

    private final PermissionInterceptor interceptor;

    public AuthWebMvcConfig(PermissionInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
