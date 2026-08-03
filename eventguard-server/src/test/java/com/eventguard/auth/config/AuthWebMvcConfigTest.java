package com.eventguard.auth.config;

import com.eventguard.auth.security.PermissionInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

/** CORS 配置冒烟测试：空来源与配置来源均正常构造（注册逻辑由 Spring 容器在运行时执行）。 */
class AuthWebMvcConfigTest {

    @Test
    void constructs_with_blank_origins() {
        assertDoesNotThrow(() -> new AuthWebMvcConfig(mock(PermissionInterceptor.class), ""));
    }

    @Test
    void constructs_with_configured_origins() {
        assertDoesNotThrow(() -> new AuthWebMvcConfig(
                mock(PermissionInterceptor.class), "https://console.example.com,https://a.example.com"));
    }

    @Test
    void constructs_with_whitespace_origins() {
        assertDoesNotThrow(() -> new AuthWebMvcConfig(mock(PermissionInterceptor.class), "   "));
    }
}
