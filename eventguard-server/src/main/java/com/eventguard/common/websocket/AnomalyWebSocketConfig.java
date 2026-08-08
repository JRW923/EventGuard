package com.eventguard.common.websocket;

import com.eventguard.auth.security.JwtHandshakeInterceptor;
import com.eventguard.auth.security.JwtService;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class AnomalyWebSocketConfig implements WebSocketConfigurer {

    private final AnomalyWebSocketHandler handler;
    private final JwtService jwtService;
    private final String allowedOrigins;

    public AnomalyWebSocketConfig(AnomalyWebSocketHandler handler, JwtService jwtService,
                                  @Value("${EG_WS_ALLOWED_ORIGINS:*}") String allowedOrigins) {
        this.handler = handler;
        this.jwtService = jwtService;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // ponytail: setAllowedOrigins("*") 仅限 MVP；生产需改具体前端域名
        registry.addHandler(handler, "/ws/anomalies")
                .setAllowedOrigins(allowedOrigins.split(","))
                .addInterceptors(new JwtHandshakeInterceptor(jwtService));
    }
}
