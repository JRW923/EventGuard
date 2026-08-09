package com.eventguard.anomaly.engine;

import com.eventguard.event.model.DomainEvent;
import com.eventguard.event.model.InventoryReservedEvent;
import com.eventguard.gateway.InventoryGateway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 加载规则上下文：从 domain_events 与 order_view 表查询聚合数据。
 * MVP 版本简化处理：对无数据场景返回默认值。
 */
@Component
public class RuleContextLoader {

    private final JdbcTemplate jdbc;
    private final InventoryGateway inventoryGateway;

    public RuleContextLoader(JdbcTemplate jdbc, InventoryGateway inventoryGateway) {
        this.jdbc = jdbc;
        this.inventoryGateway = inventoryGateway;
    }

    public RuleContext load(DomainEvent event) {
        String userId = event.getMetadata() != null ? event.getMetadata().get("userId") : null;
        BigDecimal userMean = loadUserMeanAmount(userId);
        return RuleContext.builder()
                .userMeanAmount(userMean)
                .userStdAmount(estimateStdAmount(userMean))
                .recentPaymentCompletions(loadRecentPaymentCompletions(event.getAggregateId().toString(), event.getEventId()))
                .previousState(loadPreviousState(event.getAggregateId().toString(), event.getVersion()))
                .recentCreateOrders(loadRecentCreateOrders(userId))
                .actualStock(loadActualStock(event))
                .build();
    }

    /** R005 实际库存：从库存网关读真实库存；无 skuId 的普通事件回退为 0（不触发越界判断）。 */
    private int loadActualStock(DomainEvent event) {
        if (event instanceof InventoryReservedEvent inv) {
            return inventoryGateway.currentStock(inv.getSkuId());
        }
        return 0;
    }

    private BigDecimal loadUserMeanAmount(String userId) {
        if (userId == null) return BigDecimal.ZERO;
        try {
            List<BigDecimal> amounts = jdbc.queryForList(
                    "SELECT (payload->>'totalAmount')::numeric FROM domain_events " +
                            "WHERE event_type='OrderCreatedEvent' AND metadata->>'userId'=?",
                    BigDecimal.class, userId);
            if (amounts.isEmpty()) return BigDecimal.ZERO;
            return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(amounts.size()), RoundingMode.HALF_UP);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal estimateStdAmount(BigDecimal mean) {
        // MVP 简化：返回均值的 10% 作为标准差估计
        if (mean.compareTo(BigDecimal.ZERO) == 0) return null;
        return mean.multiply(new BigDecimal("0.1"));
    }

    /**
     * R002 上下文：同订单的 PaymentCompleted 时间戳。
     * 排除当前事件自身（AI 桥接评估时该事件已落库，若不排除则单次支付也会被 recent 计为一次，
     * 导致「任意一次支付都命中重复支付」的假阳性）。
     */
    private List<Instant> loadRecentPaymentCompletions(String aggregateId, UUID excludeEventId) {
        try {
            List<Timestamp> ts = jdbc.queryForList(
                    "SELECT created_at FROM domain_events " +
                            "WHERE aggregate_id=? AND event_type='PaymentCompletedEvent' AND event_id<>? " +
                            "ORDER BY created_at DESC LIMIT 5",
                    Timestamp.class, java.util.UUID.fromString(aggregateId), excludeEventId);
            return ts.stream().map(Timestamp::toInstant).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * R003 上下文：当前事件应用**之前**的聚合状态（order_view）。
     * 按 version < 当前事件版本取最近一条：order_view 反映的是最新已应用状态，若不按版本过滤，
     * 评估该事件时（AI 桥接调用）order_view 已含其自身效果，导致任何合法迁移（如 PENDING_PAYMENT→PAID）
     * 都误报「状态跳跃」。
     */
    private String loadPreviousState(String aggregateId, int currentVersion) {
        try {
            List<String> states = jdbc.queryForList(
                    "SELECT status FROM order_view WHERE order_id=? AND version < ? " +
                            "ORDER BY version DESC LIMIT 1",
                    String.class, java.util.UUID.fromString(aggregateId), currentVersion);
            return states.isEmpty() ? null : states.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Instant> loadRecentCreateOrders(String userId) {
        if (userId == null) return List.of();
        try {
            List<Timestamp> ts = jdbc.queryForList(
                    "SELECT created_at FROM domain_events " +
                            "WHERE event_type='OrderCreatedEvent' AND metadata->>'userId'=? " +
                            "ORDER BY created_at DESC LIMIT 30",
                    Timestamp.class, userId);
            return ts.stream().map(Timestamp::toInstant).toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
