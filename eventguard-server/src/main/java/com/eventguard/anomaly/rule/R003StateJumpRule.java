package com.eventguard.anomaly.rule;

import com.eventguard.anomaly.engine.RuleContext;
import com.eventguard.anomaly.model.AnomalyLevel;
import com.eventguard.event.model.DomainEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** R003：状态跳跃规则 — 状态机非法迁移检测 */
@Component
public class R003StateJumpRule implements EventRule {

    // 合法的前序状态映射：eventType → 该事件允许的前序状态集合
    // ponytail: Map.of 仅支持最多 10 个键值对，此处 11 个故使用 Map.ofEntries
    private static final Map<String, Set<String>> LEGAL_PREV_STATES = Map.ofEntries(
            Map.entry("PaymentCompletedEvent", Set.of("PENDING_PAYMENT", "PAYMENT_FAILED")),
            Map.entry("PaymentFailedEvent", Set.of("PENDING_PAYMENT", "PAYMENT_FAILED")),
            Map.entry("PaymentRetriedEvent", Set.of("PAYMENT_FAILED")),
            Map.entry("InventoryReservedEvent", Set.of("PAID")),
            Map.entry("OrderConfirmedEvent", Set.of("PAID")),
            Map.entry("ShippedEvent", Set.of("CONFIRMED")),
            Map.entry("DeliveredEvent", Set.of("SHIPPED")),
            Map.entry("OrderClosedEvent", Set.of("DELIVERED", "REFUNDED")),
            Map.entry("OrderCancelledEvent", Set.of("PENDING_PAYMENT", "PAYMENT_FAILED", "PAID")),
            Map.entry("OrderRefundedEvent", Set.of("PAID")),
            Map.entry("OrderRefundRequestedEvent", Set.of("PAID"))
    );

    @Override
    public String ruleId() { return "R003"; }

    @Override
    public AnomalyLevel level() { return AnomalyLevel.ERROR; }

    @Override
    public boolean matches(DomainEvent event, RuleContext ctx) {
        String prevState = ctx.getPreviousState();
        if (prevState == null) return false; // 无前序状态（新订单首事件）

        Set<String> legal = LEGAL_PREV_STATES.get(event.getEventType());
        if (legal == null) return false; // 未知事件类型不报

        return !legal.contains(prevState);
    }
}
