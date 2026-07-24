package com.eventguard.common.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** WebSocket 配置：注册 /ws/anomalies 端点 */
@Configuration
@EnableWebSocket
public class AnomalyWebSocketConfig implements WebSocketConfigurer {

    private final AnomalyWebSocketHandler handler;

    public AnomalyWebSocketConfig(AnomalyWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/anomalies").setAllowedOrigins("*");
    }
}
