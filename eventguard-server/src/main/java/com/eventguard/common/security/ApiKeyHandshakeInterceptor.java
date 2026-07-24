package com.eventguard.common.security;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** WS 握手校验：浏览器无法在 WS 连接带自定义头，密钥经查询参数 api_key 传递。 */
public class ApiKeyHandshakeInterceptor implements HandshakeInterceptor {

    private final ApiKeyValidator validator;

    public ApiKeyHandshakeInterceptor(ApiKeyValidator validator) {
        this.validator = validator;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    org.springframework.web.socket.WebSocketHandler wsHandler,
                                    Map<String, Object> attributes) {
        String q = request.getURI().getQuery();
        String provided = null;
        if (q != null) {
            for (String kv : q.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2 && "api_key".equals(p[0])) {
                    provided = java.net.URLDecoder.decode(p[1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return validator.isValid(provided);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               org.springframework.web.socket.WebSocketHandler wsHandler, Exception ex) {
        // 无需处理
    }
}
