package com.eventguard.common.websocket;

import com.eventguard.common.security.ApiKeyHandshakeInterceptor;
import com.eventguard.common.security.ApiKeyValidator;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class AnomalyWebSocketConfig implements WebSocketConfigurer {

    private final AnomalyWebSocketHandler handler;
    private final ApiKeyValidator validator;

    public AnomalyWebSocketConfig(AnomalyWebSocketHandler handler, ApiKeyValidator validator) {
        this.handler = handler;
        this.validator = validator;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // ponytail: setAllowedOrigins("*") 仅限 MVP；生产需改具体前端域名
        registry.addHandler(handler, "/ws/anomalies")
                .setAllowedOrigins("*")
                .addInterceptors(new ApiKeyHandshakeInterceptor(validator));
    }
}
