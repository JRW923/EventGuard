package com.eventguard.anomaly.consumer;

import com.eventguard.anomaly.model.AnomalyAlert;
import com.eventguard.common.websocket.AnomalyWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnomalyAlertConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnomalyWebSocketHandler handler = mock(AnomalyWebSocketHandler.class);
    private final AnomalyAlertConsumer consumer = new AnomalyAlertConsumer(handler, objectMapper);

    private String alertJson(String anomalyId, String ruleId, String source, String priority) throws Exception {
        return objectMapper.writeValueAsString(new AnomalyAlert(
                anomalyId, ruleId, UUID.randomUUID().toString(), "OrderCreatedEvent",
                "WARN", source, priority, "2026-07-21T10:00:00Z",
                "金额偏离", java.util.Map.of()));
    }

    @Test
    void on_json_alert_should_broadcast_to_websocket_handler() throws Exception {
        consumer.on(alertJson("a-1", "R001", "RULE", "HIGH"));

        verify(handler).broadcast(any(AnomalyAlert.class));
    }

    @Test
    void on_alert_continues_when_broadcast_throws() throws Exception {
        doThrow(new RuntimeException("ws error")).when(handler).broadcast(any(AnomalyAlert.class));

        // 不应抛异常（消费端不崩）
        consumer.on(alertJson("a-2", "IF", "IF", "LOW"));

        verify(handler).broadcast(any(AnomalyAlert.class));
    }

    @Test
    void malformed_json_is_skipped_without_throwing() {
        consumer.on("{not-json");
        consumer.on("null");
    }
}
