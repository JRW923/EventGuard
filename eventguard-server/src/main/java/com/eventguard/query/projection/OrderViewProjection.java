package com.eventguard.query.projection;

import com.eventguard.event.model.*;
import com.eventguard.event.store.EventDeserializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * 读模型投影器：消费 Kafka domain-events topic，将事件投影到 order_view 表。
 * 设计文档 7.2.1。
 */
@Component
public class OrderViewProjection implements Projection {

    private static final Logger log = LoggerFactory.getLogger(OrderViewProjection.class);

    private final JdbcTemplate jdbc;
    private final EventDeserializer deserializer;

    public OrderViewProjection(JdbcTemplate jdbc, EventDeserializer deserializer) {
        this.jdbc = jdbc;
        this.deserializer = deserializer;
    }

    @KafkaListener(topics = "domain-events", groupId = "order-view-projection")
    public void on(ConsumerRecord<String, String> record) {
        DomainEvent event;
        try {
            event = deserializer.deserializeFromKafka(record.value());
        } catch (Exception e) {
            log.error("[投影] 反序列化失败，offset={}", record.offset(), e);
            return;
        }
        try {
            handle(event);
        } catch (Exception e) {
            log.error("[投影] 处理事件失败 eventId={}", event.getEventId(), e);
        }
    }

    // 注：原计划使用 switch pattern matching（JEP 406, Java 17 PREVIEW，Java 21 才定型）。
    // 项目 pom 未启用 --enable-preview，编译会失败。改为 if-else instanceof（Java 16+ 正式语法）。
    @Override
    public void handle(DomainEvent event) {
        if (event instanceof OrderCreatedEvent e) {
            jdbc.update(
                    "INSERT INTO order_view (order_id, status, total_amount, version, updated_at) VALUES (?, ?, ?, ?, now())",
                    e.getAggregateId(), "PENDING_PAYMENT", e.getTotalAmount(), e.getVersion());
        } else if (event instanceof PaymentCompletedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'PAID', payment_time = ?, version = ? WHERE order_id = ?",
                    Timestamp.from(e.getOccurredAt()), e.getVersion(), e.getAggregateId());
        } else if (event instanceof PaymentFailedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'PAYMENT_FAILED', version = ? WHERE order_id = ?",
                    e.getVersion(), e.getAggregateId());
        } else if (event instanceof PaymentRetriedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'PENDING_PAYMENT', version = ? WHERE order_id = ?",
                    e.getVersion(), e.getAggregateId());
        } else if (event instanceof InventoryReservedEvent) {
            // 不改读模型状态
        } else if (event instanceof OrderConfirmedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'CONFIRMED', version = ? WHERE order_id = ?",
                    e.getVersion(), e.getAggregateId());
        } else if (event instanceof ShippedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'SHIPPED', shipping_time = ?, version = ? WHERE order_id = ?",
                    Timestamp.from(e.getOccurredAt()), e.getVersion(), e.getAggregateId());
        } else if (event instanceof DeliveredEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'DELIVERED', version = ? WHERE order_id = ?",
                    e.getVersion(), e.getAggregateId());
        } else if (event instanceof OrderClosedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'CLOSED', version = ? WHERE order_id = ?",
                    e.getVersion(), e.getAggregateId());
        } else if (event instanceof OrderCancelledEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'CANCELLED', version = ? WHERE order_id = ?",
                    e.getVersion(), e.getAggregateId());
        } else if (event instanceof OrderRefundedEvent e) {
            jdbc.update(
                    "UPDATE order_view SET status = 'REFUNDED', version = ? WHERE order_id = ?",
                    e.getVersion(), e.getAggregateId());
        } else {
            log.warn("[投影] 未知事件类型: {}", event.getEventType());
        }
    }

    @Override
    public void reset() {
        jdbc.update("TRUNCATE TABLE order_view");
    }
}
