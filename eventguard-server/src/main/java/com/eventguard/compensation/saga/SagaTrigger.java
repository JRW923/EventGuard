package com.eventguard.compensation.saga;

import com.eventguard.common.metrics.EventGuardMetrics;
import com.eventguard.common.idempotent.IdempotentConsumer;
import com.eventguard.event.model.DomainEvent;
import com.eventguard.event.model.InventoryReservationFailedEvent;
import com.eventguard.event.model.OrderCancelledEvent;
import com.eventguard.event.store.EventDeserializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    private final IdempotentConsumer idempotentConsumer;

    @Autowired(required = false)
    private EventGuardMetrics metrics;

    public SagaTrigger(CompensationSaga compensationSaga, EventDeserializer deserializer, JdbcTemplate jdbc,
                       IdempotentConsumer idempotentConsumer) {
        this.compensationSaga = compensationSaga;
        this.deserializer = deserializer;
        this.jdbc = jdbc;
        this.idempotentConsumer = idempotentConsumer;
    }

    @KafkaListener(topics = "domain-events", groupId = "saga-trigger")
    @Transactional
    public void on(ConsumerRecord<String, Object> record) {
        DomainEvent event;
        try {
            event = deserializer.deserializeFromKafka(record.value());
        } catch (Exception e) {
            log.error("[Saga] 反序列化失败 offset={}", record.offset(), e);
            throw new IllegalStateException("Saga 事件反序列化失败", e);
        }
        if (!idempotentConsumer.tryMarkProcessed("saga-trigger", event.getEventId())) {
            log.debug("[Saga] 触发事件已处理，跳过 eventId={}", event.getEventId());
            return;
        }
        try {
            handle(event);
        } catch (Exception e) {
            log.error("[Saga] 处理事件失败 eventId={}", event.getEventId(), e);
            throw new IllegalStateException("Saga 事件处理失败", e);
        }
    }

    private void handle(DomainEvent event) {
        if (event instanceof OrderCancelledEvent e
                && e.getReason() != null && e.getReason().contains("重试超限")) {
            BigDecimal amount = loadAmount(e.getAggregateId());
            compensationSaga.start(e.getAggregateId(), List.of(
                    new SagaStep("REFUND", Map.of("amount", amount != null ? amount : BigDecimal.ZERO)),
                    new SagaStep("NOTIFY_DELAY", Map.of())));
            if (metrics != null) {
                metrics.counter("eventguard.saga.started", "trigger", "OrderCancelledEvent");
            }
            log.info("[Saga] 支付重试超限 → REFUND+NOTIFY 自动补偿 order={}", e.getAggregateId());
        } else if (event instanceof InventoryReservationFailedEvent e) {
            compensationSaga.start(e.getAggregateId(), List.of(
                    new SagaStep("MARK_OUT_OF_STOCK", Map.of("sku", e.getSkuId())),
                    new SagaStep("NOTIFY_DELAY", Map.of())));
            if (metrics != null) {
                metrics.counter("eventguard.saga.started", "trigger", "InventoryReservationFailedEvent");
            }
            log.info("[Saga] 库存预留失败 → MARK_OUT_OF_STOCK+NOTIFY 自动补偿 order={}", e.getAggregateId());
        }
    }

    /**
     * 读订单金额：从 domain_events（事件库）取 OrderCreatedEvent 的 totalAmount，
     * 而非 order_view 读模型——Saga 由后续事件（OrderCancelled v9）触发时，
     * 投影消费组可能尚未跟上，order_view 会漏读；事件库是 append-only 事实源，
     * 触发事件之前的事件必然已落库。
     * <p>
     * 读不到或读失败必须抛异常走 Kafka 重试/DLT：金额是「退款>100 需审批」规则的依据，
     * 退化为 0 元会让高风险退款静默绕过审批。
     */
    private BigDecimal loadAmount(UUID aggregateId) {
        List<BigDecimal> amounts = jdbc.queryForList(
                "SELECT (payload->>'totalAmount')::numeric FROM domain_events " +
                        "WHERE aggregate_id = ? AND event_type='OrderCreatedEvent' LIMIT 1",
                BigDecimal.class, aggregateId);
        if (amounts.isEmpty() || amounts.get(0) == null) {
            throw new IllegalStateException("[Saga] 订单创建事件缺失，无法确定退款金额 order=" + aggregateId);
        }
        return amounts.get(0);
    }
}
