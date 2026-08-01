package com.eventguard.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WS 握手校验（替换原 ApiKeyHandshakeInterceptor）：浏览器无法在 WS 连接带自定义头，
 * JWT 经查询参数 token 传递；要求有效签名且权限含 anomaly:view。
 */
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwtService;

    public JwtHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                  WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = tokenFromQuery(request.getURI().getQuery());
        if (token == null) {
            return false;
        }
        try {
            Claims c = jwtService.parse(token);
            return JwtService.strings(c, "permissions").contains("anomaly:view");
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private String tokenFromQuery(String query) {
        if (query == null) {
            return null;
        }
        for (String kv : query.split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && "token".equals(p[0])) {
                return java.net.URLDecoder.decode(p[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception ex) {
        // 无需处理
    }
}
