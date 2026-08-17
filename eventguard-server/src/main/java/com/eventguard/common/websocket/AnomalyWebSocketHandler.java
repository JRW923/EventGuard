package com.eventguard.common.websocket;

import com.eventguard.anomaly.model.AnomalyAlert;
import com.eventguard.common.metrics.EventGuardMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    // ponytail: 无心跳/空闲剔除，靠 isOpen() + 发送失败剔除兜底；多实例广播需 Redis Pub/Sub（升级路径）
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    // 注入 Spring 托管实例，与 REST 响应共用同一套序列化配置（JSR310、命名策略）；
    // 自建 new ObjectMapper() 会让 WS 推送的告警字段与 /alerts/recent 补拉的不一致。
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private EventGuardMetrics metrics;

    public AnomalyWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        if (metrics != null) {
            metrics.gauge("eventguard.anomaly.ws.connections", sessions::size);
        }
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
                if (!session.isOpen()) {
                    // 半开死会话：静默剔除，避免长期运行累积与每次广播的无效尝试
                    sessions.remove(session);
                    continue;
                }
                try {
                    session.sendMessage(message);
                } catch (IOException e) {
                    // 发送失败的会话大概率已死：剔除并关闭，其余会话继续（一会话失败不中断其余）
                    sessions.remove(session);
                    closeQuietly(session);
                    log.warn("WebSocket 发送失败，已剔除会话={}: {}", session.getId(), e.getMessage());
                } catch (IllegalStateException e) {
                    sessions.remove(session);
                    log.warn("WebSocket 会话状态异常，已剔除={}: {}", session.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("广播异常告警失败: {}", e.getMessage(), e);
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理：会话已从集合移除，不会再被广播
        }
    }
}
