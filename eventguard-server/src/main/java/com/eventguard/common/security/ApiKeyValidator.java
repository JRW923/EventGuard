package com.eventguard.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 共享 API Key 校验：供 REST 过滤器与 WS 握手拦截器复用。 */
@Component
public class ApiKeyValidator {

    private final String expectedKey;

    public ApiKeyValidator(@Value("${EG_API_KEY:changeme}") String expectedKey) {
        this.expectedKey = expectedKey;
    }

    public boolean isValid(String provided) {
        // ponytail: 等值比较即可，MVP 不引入常量时间比较/多密钥轮换；升级路径=换 JWT/OPA
        return provided != null && provided.equals(expectedKey);
    }
}
