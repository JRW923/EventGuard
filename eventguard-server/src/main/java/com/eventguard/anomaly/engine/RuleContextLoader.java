package com.eventguard.anomaly.engine;

import com.eventguard.event.model.DomainEvent;
import com.eventguard.event.model.InventoryReservedEvent;
import com.eventguard.gateway.InventoryGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加载规则上下文：从 domain_events 与 order_view 表查询聚合数据。
 * 对无数据场景返回默认值；查询失败记 warn 并降级，但绝不静默——规则失效必须可在日志中定位。
 */
@Component
public class RuleContextLoader {

    private static final Logger log = LoggerFactory.getLogger(RuleContextLoader.class);

    /** 金额基线统计窗口，与事件归档周期（retain-events.sh 90 天）对齐。 */
    private static final String AMOUNT_WINDOW = "90 days";

    private final JdbcTemplate jdbc;
    private final InventoryGateway inventoryGateway;
    // ponytail: 进程内 TTL 缓存，单实例有效；多副本各自持有基线（可接受——规则评估本来就在各自实例发生）。
    private final Map<String, CachedStats> statsCache = new ConcurrentHashMap<>();

    private record CachedStats(BigDecimal mean, BigDecimal std, long expiresAtMillis) {}

    public RuleContextLoader(JdbcTemplate jdbc, InventoryGateway inventoryGateway) {
        this.jdbc = jdbc;
        this.inventoryGateway = inventoryGateway;
    }

    public RuleContext load(DomainEvent event) {
        String userId = event.getMetadata() != null ? event.getMetadata().get("userId") : null;
        CachedStats stats = loadUserAmountStats(userId);
        return RuleContext.builder()
                .userMeanAmount(stats.mean())
                .userStdAmount(stats.std())
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

    /**
     * 用户金额基线（R001）：窗口内 OrderCreatedEvent 的均值与总体标准差，单条 SQL 由数据库统计。
     * std 为 null（无样本）或 0（单样本）时 R001 自行跳过，不会误报。
     */
    private CachedStats loadUserAmountStats(String userId) {
        if (userId == null) return new CachedStats(BigDecimal.ZERO, null, 0);
        long now = System.currentTimeMillis();
        CachedStats cached = statsCache.get(userId);
        if (cached != null && cached.expiresAtMillis() > now) return cached;

        CachedStats fresh;
        try {
            List<java.math.BigDecimal[]> rows = jdbc.query(
                    "SELECT avg((payload->>'totalAmount')::numeric), stddev_pop((payload->>'totalAmount')::numeric) " +
                            "FROM domain_events WHERE event_type='OrderCreatedEvent' AND metadata->>'userId'=? " +
                            "AND created_at >= now() - interval '" + AMOUNT_WINDOW + "'",
                    (rs, i) -> new BigDecimal[]{rs.getBigDecimal(1), rs.getBigDecimal(2)},
                    userId);
            BigDecimal mean = rows.isEmpty() || rows.get(0)[0] == null ? BigDecimal.ZERO : rows.get(0)[0];
            BigDecimal std = rows.isEmpty() ? null : rows.get(0)[1];
            fresh = new CachedStats(mean, std, now + 30_000);
        } catch (Exception e) {
            log.warn("[规则] 读取用户金额基线失败 userId={}: {}", userId, e.getMessage());
            fresh = new CachedStats(BigDecimal.ZERO, null, now + 30_000);
        }
        statsCache.put(userId, fresh);
        return fresh;
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
            log.warn("[规则] 读取重复支付上下文失败 order={}: {}", aggregateId, e.getMessage());
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
            log.warn("[规则] 读取前置状态失败 order={}: {}", aggregateId, e.getMessage());
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
            log.warn("[规则] 读取高频下单上下文失败 userId={}: {}", userId, e.getMessage());
            return List.of();
        }
    }
}
