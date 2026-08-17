package com.eventguard.anomaly.consumer;

import com.eventguard.anomaly.history.AnomalyAlertHistoryRepository;
import com.eventguard.anomaly.model.AnomalyAlert;
import com.eventguard.common.websocket.AnomalyWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnomalyAlertConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnomalyWebSocketHandler handler = mock(AnomalyWebSocketHandler.class);
    private final AnomalyAlertHistoryRepository repository = mock(AnomalyAlertHistoryRepository.class);
    private final AnomalyAlertConsumer consumer = new AnomalyAlertConsumer(handler, objectMapper, repository);

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
    void on_alert_is_persisted_before_broadcast_for_ws_backfill() throws Exception {
        String raw = alertJson("a-db", "R002", "RULE", "HIGH");

        consumer.on(raw);

        verify(repository).save(eq(raw), any(AnomalyAlert.class));
        verify(handler).broadcast(any(AnomalyAlert.class));
    }

    @Test
    void on_alert_persist_failure_propagates_before_broadcast() throws Exception {
        doThrow(new RuntimeException("db down")).when(repository).save(anyString(), any());

        assertThrows(RuntimeException.class, () -> consumer.on(alertJson("a-3", "IF", "IF", "LOW")));
        verify(handler, org.mockito.Mockito.never()).broadcast(any());
    }

    @Test
    void on_alert_propagates_broadcast_failure_for_kafka_retry() throws Exception {
        doThrow(new RuntimeException("ws error")).when(handler).broadcast(any(AnomalyAlert.class));

        assertThrows(IllegalStateException.class, () -> consumer.on(alertJson("a-2", "IF", "IF", "LOW")));

        verify(handler).broadcast(any(AnomalyAlert.class));
    }

    @Test
    void malformed_json_is_propagated_for_dead_letter_recovery() {
        assertThrows(IllegalStateException.class, () -> consumer.on("{not-json"));
        assertThrows(IllegalStateException.class, () -> consumer.on("null"));
    }
}
