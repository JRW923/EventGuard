package com.eventguard.anomaly.consumer;

import com.eventguard.anomaly.model.AnomalyAlert;
import com.eventguard.common.websocket.AnomalyWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

import static org.mockito.Mockito.*;

class AnomalyAlertConsumerTest {

    @Test
    void on_alert_should_broadcast_to_websocket_handler() {
        AnomalyWebSocketHandler handler = mock(AnomalyWebSocketHandler.class);
        AnomalyAlertConsumer consumer = new AnomalyAlertConsumer(handler);

        AnomalyAlert alert = new AnomalyAlert(
                "a-1", "R001", UUID.randomUUID().toString(), "OrderCreatedEvent",
                "WARN", "RULE", "HIGH", "2026-07-21T10:00:00Z",
                "金额偏离", java.util.Map.of()
        );

        consumer.on(alert);

        verify(handler).broadcast(alert);
    }

    @Test
    void on_alert_continues_when_broadcast_throws() {
        AnomalyWebSocketHandler handler = mock(AnomalyWebSocketHandler.class);
        doThrow(new RuntimeException("ws error")).when(handler).broadcast(any());
        AnomalyAlertConsumer consumer = new AnomalyAlertConsumer(handler);

        AnomalyAlert alert = new AnomalyAlert(
                "a-2", "IF", UUID.randomUUID().toString(), "OrderCreatedEvent",
                "WARN", "IF", "LOW", "2026-07-21T10:00:00Z",
                "IF anomaly", java.util.Map.of()
        );

        // 不应抛异常（消费端不崩）
        consumer.on(alert);

        verify(handler).broadcast(alert);
    }
}
