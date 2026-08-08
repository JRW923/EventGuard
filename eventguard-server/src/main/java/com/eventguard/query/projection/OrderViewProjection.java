package com.eventguard.query.projection;

import com.eventguard.common.idempotent.IdempotentConsumer;
import com.eventguard.common.metrics.EventGuardMetrics;
import com.eventguard.event.model.*;
import com.eventguard.event.store.EventDeserializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

/**
 * 读模型投影器：消费 Kafka domain-events topic，将事件投影到 order_view 表。
 * 幂等保证：通过 idempotent_consumers 表去重（设计文档 7.2.5）。
 */
@Component
public class OrderViewProjection implements Projection {

    private static final Logger log = LoggerFactory.getLogger(OrderViewProjection.class);
    private static final String CONSUMER_GROUP = "order-view";

    private final JdbcTemplate jdbc;
    private final EventDeserializer deserializer;
    private final IdempotentConsumer idempotentConsumer;

    @Autowired(required = false)
    private EventGuardMetrics metrics;

    public OrderViewProjection(JdbcTemplate jdbc, EventDeserializer deserializer,
                               IdempotentConsumer idempotentConsumer) {
        this.jdbc = jdbc;
        this.deserializer = deserializer;
        this.idempotentConsumer = idempotentConsumer;
    }

    // ponytail: 仅 JDBC 事务（spring-jdbc PlatformTransactionManager），未引入 KafkaTransactionManager，
    // 故 Kafka 偏移提交独立于 DB 事务；at-least-once 由 idempotent_consumers 表保证，重投幂等。
    @KafkaListener(topics = "domain-events", groupId = "order-view-projection")
    @Transactional
    public void on(ConsumerRecord<String, Object> record) {
        DomainEvent event;
        try {
            event = deserializer.deserializeFromKafka(record.value());
        } catch (Exception e) {
            log.error("[投影] 反序列化失败，offset={}", record.offset(), e);
            throw new IllegalStateException("投影事件反序列化失败", e);
        }
        if (idempotentConsumer.isProcessed(CONSUMER_GROUP, event.getEventId())) {
            log.debug("[投影] 事件已处理，跳过 eventId={}", event.getEventId());
            return;
        }
        try {
            handle(event);
            if (metrics != null) {
                metrics.counter("eventguard.projection.event.processed", "event_type", event.getEventType());
            }
            idempotentConsumer.markProcessed(CONSUMER_GROUP, event.getEventId());
        } catch (Exception e) {
            log.error("[投影] 处理事件失败 eventId={}", event.getEventId(), e);
            throw new IllegalStateException("投影事件处理失败", e);
        }
    }

    @Override
    public void handle(DomainEvent event) {
        if (event instanceof OrderCreatedEvent e) {
            jdbc.update(
                    "INSERT INTO order_view (order_id, status, total_amount, version, updated_at) VALUES (?, ?, ?, ?, now()) " +
                    "ON CONFLICT (order_id) DO UPDATE SET status = EXCLUDED.status, total_amount = EXCLUDED.total_amount, version = EXCLUDED.version, updated_at = now() " +
                    "WHERE order_view.version IS NULL OR order_view.version < EXCLUDED.version",
                    e.getAggregateId(), "PENDING_PAYMENT", e.getTotalAmount(), e.getVersion());
        } else if (event instanceof PaymentCompletedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'PAID', payment_time = ?, version = ?, updated_at = now() " +
                    "WHERE order_id = ? AND (version IS NULL OR version < ?)",
                    Timestamp.from(e.getOccurredAt()), e.getVersion(), e.getAggregateId(), e.getVersion());
        } else if (event instanceof PaymentFailedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'PAYMENT_FAILED', version = ?, updated_at = now() " +
                    "WHERE order_id = ? AND (version IS NULL OR version < ?)",
                    e.getVersion(), e.getAggregateId(), e.getVersion());
        } else if (event instanceof PaymentRetriedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'PENDING_PAYMENT', version = ?, updated_at = now() " +
                    "WHERE order_id = ? AND (version IS NULL OR version < ?)",
                    e.getVersion(), e.getAggregateId(), e.getVersion());
        } else if (event instanceof PaymentRequestedEvent) {
            // 支付意图事件不改读模型状态（仍 PENDING_PAYMENT，待网关回调）
        } else if (event instanceof InventoryReservedEvent) {
            // 不改读模型状态
        } else if (event instanceof InventoryReservationFailedEvent) {
            // 库存预留失败不改读模型状态（仍 PAID，触发 R005/Saga）
        } else if (event instanceof CompensationExecutedEvent) {
            // 补偿事件不改读模型状态，仅留痕
        } else if (event instanceof OrderRefundRequestedEvent) {
            // 退款意图事件不改读模型状态（订单仍 PAID，待退款结果确认）
        } else if (event instanceof OrderConfirmedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'CONFIRMED', version = ?, updated_at = now() " +
                    "WHERE order_id = ? AND (version IS NULL OR version < ?)",
                    e.getVersion(), e.getAggregateId(), e.getVersion());
        } else if (event instanceof ShippedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'SHIPPED', shipping_time = ?, version = ?, updated_at = now() " +
                    "WHERE order_id = ? AND (version IS NULL OR version < ?)",
                    Timestamp.from(e.getOccurredAt()), e.getVersion(), e.getAggregateId(), e.getVersion());
        } else if (event instanceof DeliveredEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'DELIVERED', version = ?, updated_at = now() " +
                    "WHERE order_id = ? AND (version IS NULL OR version < ?)",
                    e.getVersion(), e.getAggregateId(), e.getVersion());
        } else if (event instanceof OrderClosedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'CLOSED', version = ?, updated_at = now() " +
                    "WHERE order_id = ? AND (version IS NULL OR version < ?)",
                    e.getVersion(), e.getAggregateId(), e.getVersion());
        } else if (event instanceof OrderCancelledEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'CANCELLED', version = ?, updated_at = now() " +
                    "WHERE order_id = ? AND (version IS NULL OR version < ?)",
                    e.getVersion(), e.getAggregateId(), e.getVersion());
        } else if (event instanceof OrderRefundedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'REFUNDED', version = ?, updated_at = now() " +
                    "WHERE order_id = ? AND (version IS NULL OR version < ?)",
                    e.getVersion(), e.getAggregateId(), e.getVersion());
        } else {
            log.warn("[投影] 未知事件类型: {}", event.getEventType());
        }
    }

    @Override
    public void reset() {
        jdbc.update("TRUNCATE TABLE order_view");
    }
}
