package com.eventguard.compensation.saga;

import com.eventguard.common.idempotent.IdempotentConsumer;
import com.eventguard.event.model.OrderCancelledEvent;
import com.eventguard.event.store.EventDeserializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SagaTriggerTest {

    @Test
    void duplicate_trigger_event_does_not_start_saga_again() {
        CompensationSaga saga = mock(CompensationSaga.class);
        EventDeserializer deserializer = mock(EventDeserializer.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        IdempotentConsumer idempotent = mock(IdempotentConsumer.class);
        UUID eventId = UUID.randomUUID();
        OrderCancelledEvent event = new OrderCancelledEvent(eventId, UUID.randomUUID(), 2,
                java.time.Instant.now(), null, "支付重试超限");
        when(deserializer.deserializeFromKafka(any(Object.class))).thenReturn(event);
        when(idempotent.isProcessed("saga-trigger", eventId)).thenReturn(true);

        new SagaTrigger(saga, deserializer, jdbc, idempotent)
                .on(new ConsumerRecord<>("domain-events", 0, 0, "order", "payload"));

        verify(saga, never()).start(any(), any());
        verify(idempotent, never()).markProcessed(any(), any());
    }
}
