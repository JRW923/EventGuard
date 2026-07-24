package com.eventguard.common.websocket;

import com.eventguard.anomaly.model.AnomalyAlert;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** WebSocket 处理器：维护会话集合，收到异常告警时广播 */
@Component
public class AnomalyWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AnomalyWebSocketHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket 连接建立: {}（当前 {} 个连接）", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket 连接关闭: {}（当前 {} 个连接）", session.getId(), sessions.size());
    }

    /** 广播异常告警到所有活跃会话 */
    public void broadcast(AnomalyAlert alert) {
        try {
            String json = objectMapper.writeValueAsString(alert);
            TextMessage message = new TextMessage(json);
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(message);
                    } catch (IOException e) {
                        log.warn("WebSocket 发送失败 session={}: {}", session.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("广播异常告警失败: {}", e.getMessage(), e);
        }
    }
}
