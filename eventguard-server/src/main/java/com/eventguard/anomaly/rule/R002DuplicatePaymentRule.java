package com.eventguard.anomaly.rule;

import com.eventguard.anomaly.engine.RuleContext;
import com.eventguard.anomaly.model.AnomalyLevel;
import com.eventguard.event.model.DomainEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** R002：重复支付规则 — 5 分钟内同订单多次 PaymentCompleted */
@Component
public class R002DuplicatePaymentRule implements EventRule {

    private static final Duration WINDOW = Duration.ofMinutes(5);

    @Override
    public String ruleId() { return "R002"; }

    @Override
    public AnomalyLevel level() { return AnomalyLevel.ERROR; }

    @Override
    public boolean matches(DomainEvent event, RuleContext ctx) {
        if (!"PaymentCompletedEvent".equals(event.getEventType())) return false;

        Instant now = event.getOccurredAt();
        List<Instant> recent = ctx.getRecentPaymentCompletions();
        if (recent == null || recent.isEmpty()) return false;

        return recent.stream().anyMatch(ts -> Duration.between(ts, now).abs().compareTo(WINDOW) < 0);
    }
}
