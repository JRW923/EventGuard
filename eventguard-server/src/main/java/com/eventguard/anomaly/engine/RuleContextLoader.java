package com.eventguard.anomaly.engine;

import com.eventguard.event.model.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 加载规则上下文：从 domain_events 与 order_view 表查询聚合数据。
 * MVP 版本简化处理：对无数据场景返回默认值。
 */
@Component
public class RuleContextLoader {

    private final JdbcTemplate jdbc;

    public RuleContextLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RuleContext load(DomainEvent event) {
        String userId = event.getMetadata() != null ? event.getMetadata().get("userId") : null;
        return RuleContext.builder()
                .userMeanAmount(loadUserMeanAmount(userId))
                .userStdAmount(loadUserStdAmount(userId))
                .recentPaymentCompletions(loadRecentPaymentCompletions(event.getAggregateId().toString()))
                .previousState(loadPreviousState(event.getAggregateId().toString()))
                .recentCreateOrders(loadRecentCreateOrders(userId))
                .actualStock(1000) // MVP 默认库存
                .build();
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

    private BigDecimal loadUserStdAmount(String userId) {
        // MVP 简化：返回均值的 10% 作为标准差估计
        BigDecimal mean = loadUserMeanAmount(userId);
        if (mean.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ONE;
        return mean.multiply(new BigDecimal("0.1"));
    }

    private List<Instant> loadRecentPaymentCompletions(String aggregateId) {
        try {
            List<Timestamp> ts = jdbc.queryForList(
                    "SELECT created_at FROM domain_events " +
                            "WHERE aggregate_id=? AND event_type='PaymentCompletedEvent' " +
                            "ORDER BY created_at DESC LIMIT 5",
                    Timestamp.class, java.util.UUID.fromString(aggregateId));
            return ts.stream().map(Timestamp::toInstant).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String loadPreviousState(String aggregateId) {
        try {
            List<String> states = jdbc.queryForList(
                    "SELECT status FROM order_view WHERE order_id=?",
                    String.class, java.util.UUID.fromString(aggregateId));
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
