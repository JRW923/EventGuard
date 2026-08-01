package com.eventguard.common.websocket;

import com.eventguard.auth.security.JwtHandshakeInterceptor;
import com.eventguard.auth.security.JwtService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class AnomalyWebSocketConfig implements WebSocketConfigurer {

    private final AnomalyWebSocketHandler handler;
    private final JwtService jwtService;

    public AnomalyWebSocketConfig(AnomalyWebSocketHandler handler, JwtService jwtService) {
        this.handler = handler;
        this.jwtService = jwtService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // ponytail: setAllowedOrigins("*") 仅限 MVP；生产需改具体前端域名
        registry.addHandler(handler, "/ws/anomalies")
                .setAllowedOrigins("*")
                .addInterceptors(new JwtHandshakeInterceptor(jwtService));
    }
}
