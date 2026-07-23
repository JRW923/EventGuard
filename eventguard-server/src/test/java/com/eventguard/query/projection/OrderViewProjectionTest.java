package com.eventguard.query.projection;

import com.eventguard.common.idempotent.IdempotentConsumer;
import com.eventguard.event.model.*;
import com.eventguard.event.store.EventDeserializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderViewProjectionTest {

    @Mock JdbcTemplate jdbc;
    @Mock EventDeserializer deserializer;
    @Mock IdempotentConsumer idempotentConsumer;

    OrderViewProjection projection;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        projection = new OrderViewProjection(jdbc, deserializer, idempotentConsumer);
    }

    @Test
    void handle_OrderCreatedEvent_should_insert_order_view() {
        UUID orderId = UUID.randomUUID();
        OrderCreatedEvent e = new OrderCreatedEvent(orderId, 1, "user-1", new BigDecimal("99.00"), null);
        projection.handle(e);
        verify(jdbc).update(
                eq("INSERT INTO order_view (order_id, status, total_amount, version, updated_at) VALUES (?, ?, ?, ?, now())"),
                eq(orderId), eq("PENDING_PAYMENT"), eq(new BigDecimal("99.00")), eq(1));
    }

    @Test
    void handle_PaymentCompletedEvent_should_update_status_to_PAID() {
        UUID orderId = UUID.randomUUID();
        PaymentCompletedEvent e = new PaymentCompletedEvent(orderId, 2, "pay-1", null);
        projection.handle(e);
        verify(jdbc).update(
                eq("UPDATE order_view SET status = 'PAID', payment_time = ?, version = ? WHERE order_id = ?"),
                any(), eq(2), eq(orderId));
    }

    @Test
    void handle_OrderConfirmedEvent_should_update_status_to_CONFIRMED() {
        UUID orderId = UUID.randomUUID();
        OrderConfirmedEvent e = new OrderConfirmedEvent(orderId, 4, null);
        projection.handle(e);
        verify(jdbc).update(
                eq("UPDATE order_view SET status = 'CONFIRMED', version = ? WHERE order_id = ?"),
                eq(4), eq(orderId));
    }

    @Test
    void handle_ShippedEvent_should_update_status_and_shipping_time() {
        UUID orderId = UUID.randomUUID();
        ShippedEvent e = new ShippedEvent(orderId, 5, "trk-1", null);
        projection.handle(e);
        verify(jdbc).update(
                eq("UPDATE order_view SET status = 'SHIPPED', shipping_time = ?, version = ? WHERE order_id = ?"),
                any(), eq(5), eq(orderId));
    }

    @Test
    void handle_OrderClosedEvent_should_update_status_to_CLOSED() {
        UUID orderId = UUID.randomUUID();
        OrderClosedEvent e = new OrderClosedEvent(orderId, 7, null);
        projection.handle(e);
        verify(jdbc).update(
                eq("UPDATE order_view SET status = 'CLOSED', version = ? WHERE order_id = ?"),
                eq(7), eq(orderId));
    }

    @Test
    void reset_should_truncate_order_view() {
        projection.reset();
        verify(jdbc).update("TRUNCATE TABLE order_view");
    }

    @Test
    void handle_should_skip_already_processed_event() {
        UUID orderId = UUID.randomUUID();
        OrderCreatedEvent e = new OrderCreatedEvent(orderId, 1, "u1", new BigDecimal("99"), null);
        when(deserializer.deserializeFromKafka(anyString())).thenReturn(e);
        when(idempotentConsumer.isProcessed("order-view", e.getEventId())).thenReturn(true);

        projection.on(new ConsumerRecord<>("domain-events", 0, 0, orderId.toString(), "{}"));

        verify(jdbc, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void handle_should_mark_processed_after_success() {
        UUID orderId = UUID.randomUUID();
        OrderCreatedEvent e = new OrderCreatedEvent(orderId, 1, "u1", new BigDecimal("99"), null);
        when(deserializer.deserializeFromKafka(anyString())).thenReturn(e);

        projection.on(new ConsumerRecord<>("domain-events", 0, 0, orderId.toString(), "{}"));

        verify(idempotentConsumer).markProcessed("order-view", e.getEventId());
    }
}
