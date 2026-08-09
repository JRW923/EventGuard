package com.eventguard.common.websocket;

import com.eventguard.anomaly.model.AnomalyAlert;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

import static org.mockito.Mockito.*;

class AnomalyWebSocketHandlerTest {

    @Test
    void broadcast_should_send_message_to_all_open_sessions() throws Exception {
        AnomalyWebSocketHandler handler = new AnomalyWebSocketHandler(new ObjectMapper());

        WebSocketSession session1 = mock(WebSocketSession.class);
        WebSocketSession session2 = mock(WebSocketSession.class);
        when(session1.isOpen()).thenReturn(true);
        when(session2.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session1);
        handler.afterConnectionEstablished(session2);

        AnomalyAlert alert = new AnomalyAlert(
                "a-1", "R001", UUID.randomUUID().toString(), "OrderCreatedEvent",
                "WARN", "RULE", "HIGH", "2026-07-21T10:00:00Z",
                "金额偏离", java.util.Map.of()
        );
        handler.broadcast(alert);

        verify(session1).sendMessage(any(TextMessage.class));
        verify(session2).sendMessage(any(TextMessage.class));
    }

    @Test
    void broadcast_should_skip_closed_sessions() throws Exception {
        AnomalyWebSocketHandler handler = new AnomalyWebSocketHandler(new ObjectMapper());

        WebSocketSession open = mock(WebSocketSession.class);
        WebSocketSession closed = mock(WebSocketSession.class);
        when(open.isOpen()).thenReturn(true);
        when(closed.isOpen()).thenReturn(false);

        handler.afterConnectionEstablished(open);
        handler.afterConnectionEstablished(closed);

        AnomalyAlert alert = new AnomalyAlert(
                "a-1", "R001", UUID.randomUUID().toString(), "OrderCreatedEvent",
                "WARN", "RULE", "HIGH", "2026-07-21T10:00:00Z",
                "金额偏离", java.util.Map.of()
        );
        handler.broadcast(alert);

        verify(open).sendMessage(any(TextMessage.class));
        verify(closed, never()).sendMessage(any());
    }

    @Test
    void after_connection_closed_should_remove_session() throws Exception {
        AnomalyWebSocketHandler handler = new AnomalyWebSocketHandler(new ObjectMapper());
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, null);

        AnomalyAlert alert = new AnomalyAlert(
                "a-1", "R001", UUID.randomUUID().toString(), "OrderCreatedEvent",
                "WARN", "RULE", "HIGH", "2026-07-21T10:00:00Z",
                "金额偏离", java.util.Map.of()
        );
        handler.broadcast(alert);

        verify(session, never()).sendMessage(any());
    }

    @Test
    void broadcast_should_continue_other_sessions_when_one_send_fails() throws Exception {
        AnomalyWebSocketHandler handler = new AnomalyWebSocketHandler(new ObjectMapper());

        WebSocketSession ok = mock(WebSocketSession.class);
        WebSocketSession fail = mock(WebSocketSession.class);
        when(ok.isOpen()).thenReturn(true);
        when(fail.isOpen()).thenReturn(true);
        doThrow(new java.io.IOException("send failed")).when(fail).sendMessage(any(TextMessage.class));

        handler.afterConnectionEstablished(ok);
        handler.afterConnectionEstablished(fail);

        AnomalyAlert alert = new AnomalyAlert(
                "a-1", "R001", UUID.randomUUID().toString(), "OrderCreatedEvent",
                "WARN", "RULE", "HIGH", "2026-07-21T10:00:00Z",
                "金额偏离", java.util.Map.of()
        );
        handler.broadcast(alert);

        // 单会话发送失败不影响其余会话
        verify(ok).sendMessage(any(TextMessage.class));
        verify(fail).sendMessage(any(TextMessage.class));
    }
}
