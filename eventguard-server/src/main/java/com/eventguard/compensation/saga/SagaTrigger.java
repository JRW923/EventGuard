package com.eventguard.compensation.saga;

import com.eventguard.event.model.DomainEvent;
import com.eventguard.event.model.InventoryReservationFailedEvent;
import com.eventguard.event.model.OrderCancelledEvent;
import com.eventguard.event.store.EventDeserializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saga 触发器：消费 domain-events，把失败类事件映射为补偿步骤序列自动执行。
 * <p>
 * 映射（对齐计划 + 设计文档 7.4.3 闭环）：
 * <ul>
 *   <li>OrderCancelledEvent（支付重试超限）→ REFUND + NOTIFY_DELAY</li>
 *   <li>InventoryReservationFailedEvent → MARK_OUT_OF_STOCK + NOTIFY_DELAY</li>
 * </ul>
 * 用 {@code eg.saga.enabled=false} 关闭（测试/安全演示用）。幂等：saga 步骤最终走
 * CompensationService 的白名单 + CompensationExecutedEvent，命令侧无重复副作用。
 */
@Component
@ConditionalOnProperty(name = "eg.saga.enabled", havingValue = "true", matchIfMissing = true)
public class SagaTrigger {

    private static final Logger log = LoggerFactory.getLogger(SagaTrigger.class);

    private final CompensationSaga compensationSaga;
    private final EventDeserializer deserializer;
    private final JdbcTemplate jdbc;

    public SagaTrigger(CompensationSaga compensationSaga, EventDeserializer deserializer, JdbcTemplate jdbc) {
        this.compensationSaga = compensationSaga;
        this.deserializer = deserializer;
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = "domain-events", groupId = "saga-trigger")
    public void on(ConsumerRecord<String, Object> record) {
        DomainEvent event;
        try {
            event = deserializer.deserializeFromKafka(record.value());
        } catch (Exception e) {
            log.error("[Saga] 反序列化失败 offset={}", record.offset(), e);
            return;
        }
        try {
            handle(event);
        } catch (Exception e) {
            log.error("[Saga] 处理事件失败 eventId={}", event.getEventId(), e);
        }
    }

    private void handle(DomainEvent event) {
        if (event instanceof OrderCancelledEvent e
                && e.getReason() != null && e.getReason().contains("重试超限")) {
            BigDecimal amount = loadAmount(e.getAggregateId());
            compensationSaga.start(e.getAggregateId(), List.of(
                    new SagaStep("REFUND", Map.of("amount", amount != null ? amount : BigDecimal.ZERO)),
                    new SagaStep("NOTIFY_DELAY", Map.of())));
            log.info("[Saga] 支付重试超限 → REFUND+NOTIFY 自动补偿 order={}", e.getAggregateId());
        } else if (event instanceof InventoryReservationFailedEvent e) {
            compensationSaga.start(e.getAggregateId(), List.of(
                    new SagaStep("MARK_OUT_OF_STOCK", Map.of("sku", e.getSkuId())),
                    new SagaStep("NOTIFY_DELAY", Map.of())));
            log.info("[Saga] 库存预留失败 → MARK_OUT_OF_STOCK+NOTIFY 自动补偿 order={}", e.getAggregateId());
        }
    }

    private BigDecimal loadAmount(UUID aggregateId) {
        try {
            List<BigDecimal> amounts = jdbc.queryForList(
                    "SELECT total_amount FROM order_view WHERE order_id = ?",
                    BigDecimal.class, aggregateId);
            return amounts.isEmpty() ? BigDecimal.ZERO : amounts.get(0);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
