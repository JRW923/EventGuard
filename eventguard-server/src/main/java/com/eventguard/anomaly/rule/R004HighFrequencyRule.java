package com.eventguard.anomaly.rule;

import com.eventguard.anomaly.engine.RuleContext;
import com.eventguard.anomaly.model.AnomalyLevel;
import com.eventguard.event.model.DomainEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** R004：高频操作规则 — 同一用户 1 分钟内创建 >20 个订单 */
@Component
public class R004HighFrequencyRule implements EventRule {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int THRESHOLD = 20;

    @Override
    public String ruleId() { return "R004"; }

    @Override
    public AnomalyLevel level() { return AnomalyLevel.WARN; }

    @Override
    public boolean matches(DomainEvent event, RuleContext ctx) {
        if (!"OrderCreatedEvent".equals(event.getEventType())) return false;

        Instant now = event.getOccurredAt();
        List<Instant> recent = ctx.getRecentCreateOrders();
        if (recent == null) return false;

        long count = recent.stream()
                .filter(ts -> Duration.between(ts, now).abs().compareTo(WINDOW) < 0)
                .count();
        // 当前事件本身也算一个，所以 recent 中超过 THRESHOLD 即触发
        return count >= THRESHOLD;
    }
}
