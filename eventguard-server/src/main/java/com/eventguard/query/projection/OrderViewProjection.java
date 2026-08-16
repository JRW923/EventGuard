package com.eventguard.query.projection;

import com.eventguard.common.metrics.EventGuardMetrics;
import com.eventguard.event.model.*;
import com.eventguard.event.store.EventDeserializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

    @Autowired(required = false)
    private EventGuardMetrics metrics;

    public OrderViewProjection(@Qualifier("projectionJdbcTemplate") JdbcTemplate jdbc,
                               EventDeserializer deserializer) {
        this.jdbc = jdbc;
        this.deserializer = deserializer;
    }

    // ponytail: 仅 JDBC 事务（spring-jdbc PlatformTransactionManager），未引入 KafkaTransactionManager，
    // 故 Kafka 偏移提交独立于 DB 事务；at-least-once 由 idempotent_consumers 表保证，重投幂等。
    @KafkaListener(topics = "domain-events", groupId = "order-view-projection",
            concurrency = "${EG_PROJECTION_CONCURRENCY:3}")
    @Transactional("projectionTransactionManager")
    public void on(ConsumerRecord<String, Object> record) {
        DomainEvent event;
        try {
            event = deserializer.deserializeFromKafka(record.value());
        } catch (Exception e) {
            log.error("[投影] 反序列化失败，offset={}", record.offset(), e);
            throw new IllegalStateException("投影事件反序列化失败", e);
        }
        // 与下方读模型更新同处投影数据库事务：占位成功才投影，异常时两者一起回滚。
        if (!tryMarkProcessed(event.getEventId())) {
            log.debug("[投影] 事件已处理，跳过 eventId={}", event.getEventId());
            return;
        }
        try {
            handle(event);
            if (metrics != null) {
                metrics.counter("eventguard.projection.event.processed", "event_type", event.getEventType());
            }
        } catch (Exception e) {
            log.error("[投影] 处理事件失败 eventId={}", event.getEventId(), e);
            throw new IllegalStateException("投影事件处理失败", e);
        }
    }

    private boolean tryMarkProcessed(java.util.UUID eventId) {
        return jdbc.update(
                "INSERT INTO idempotent_consumers (consumer_group, event_id, processed_at) VALUES (?, ?, now()) " +
                        "ON CONFLICT (consumer_group, event_id) DO NOTHING",
                CONSUMER_GROUP, eventId) == 1;
    }

    @Override
    public void handle(DomainEvent event) {
        if (event instanceof OrderCreatedEvent e) {
            applyCreated(e);
        } else if (event instanceof PaymentCompletedEvent e) {
            applyNext(e, "status = 'PAID', payment_time = ?", Timestamp.from(e.getOccurredAt()));
        } else if (event instanceof PaymentFailedEvent e) {
            applyNext(e, "status = 'PAYMENT_FAILED'");
        } else if (event instanceof PaymentRetriedEvent e) {
            applyNext(e, "status = 'PENDING_PAYMENT'");
        } else if (event instanceof PaymentRequestedEvent e) {
            advanceVersion(e);
        } else if (event instanceof InventoryReservedEvent e) {
            advanceVersion(e);
        } else if (event instanceof InventoryReservationFailedEvent e) {
            advanceVersion(e);
        } else if (event instanceof CompensationExecutedEvent e) {
            advanceVersion(e);
        } else if (event instanceof OrderRefundRequestedEvent e) {
            advanceVersion(e);
        } else if (event instanceof OrderConfirmedEvent e) {
            applyNext(e, "status = 'CONFIRMED'");
        } else if (event instanceof ShippedEvent e) {
            applyNext(e, "status = 'SHIPPED', shipping_time = ?", Timestamp.from(e.getOccurredAt()));
        } else if (event instanceof DeliveredEvent e) {
            applyNext(e, "status = 'DELIVERED'");
        } else if (event instanceof OrderClosedEvent e) {
            applyNext(e, "status = 'CLOSED'");
        } else if (event instanceof OrderCancelledEvent e) {
            applyNext(e, "status = 'CANCELLED'");
        } else if (event instanceof OrderRefundedEvent e) {
            applyNext(e, "status = 'REFUNDED'");
        } else {
            throw new IllegalArgumentException("[投影] 未知事件类型: " + event.getEventType());
        }
    }

    private void applyCreated(OrderCreatedEvent event) {
        if (event.getVersion() != 1) {
            throw new IllegalStateException("创建事件版本必须为 1，实际为 " + event.getVersion());
        }
        int inserted = jdbc.update(
                "INSERT INTO order_view (order_id, status, total_amount, version, updated_at) VALUES (?, ?, ?, ?, now()) " +
                        "ON CONFLICT (order_id) DO NOTHING",
                event.getAggregateId(), "PENDING_PAYMENT", event.getTotalAmount(), event.getVersion());
        if (inserted == 0) assertAlreadyAppliedOrGap(event);
    }

    private void advanceVersion(DomainEvent event) {
        applyNext(event, "");
    }

    private void applyNext(DomainEvent event, String changes, Object... changeArgs) {
        String setClause = changes.isBlank() ? "" : changes + ", ";
        Object[] args = new Object[changeArgs.length + 3];
        System.arraycopy(changeArgs, 0, args, 0, changeArgs.length);
        args[changeArgs.length] = event.getVersion();
        args[changeArgs.length + 1] = event.getAggregateId();
        args[changeArgs.length + 2] = event.getVersion() - 1;
        int updated = jdbc.update(
                "UPDATE order_view SET " + setClause + "version = ?, updated_at = now() " +
                        "WHERE order_id = ? AND version = ?",
                args);
        if (updated == 0) assertAlreadyAppliedOrGap(event);
    }

    /** A lower/equal version is a harmless duplicate; a higher missing version must retry. */
    private void assertAlreadyAppliedOrGap(DomainEvent event) {
        java.util.List<Integer> versions = jdbc.queryForList(
                "SELECT version FROM order_view WHERE order_id = ?", Integer.class, event.getAggregateId());
        if (!versions.isEmpty() && versions.get(0) >= event.getVersion()) return;
        int current = versions.isEmpty() ? 0 : versions.get(0);
        throw new IllegalStateException("[投影] 版本缺口 orderId=" + event.getAggregateId()
                + " currentVersion=" + current + " incomingVersion=" + event.getVersion());
    }

    @Override
    public void reset() {
        jdbc.update("TRUNCATE TABLE order_view");
        jdbc.update("DELETE FROM idempotent_consumers WHERE consumer_group = ?", CONSUMER_GROUP);
    }
}
