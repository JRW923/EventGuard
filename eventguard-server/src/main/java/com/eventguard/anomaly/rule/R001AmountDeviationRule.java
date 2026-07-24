package com.eventguard.anomaly.rule;

import com.eventguard.anomaly.engine.RuleContext;
import com.eventguard.anomaly.model.AnomalyLevel;
import com.eventguard.anomaly.model.SimpleEvent;
import com.eventguard.event.model.DomainEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** R001：金额偏离规则 — |amount - userMean| > 3 * userStd */
@Component
public class R001AmountDeviationRule implements EventRule {

    @Override
    public String ruleId() { return "R001"; }

    @Override
    public AnomalyLevel level() { return AnomalyLevel.WARN; }

    @Override
    public boolean matches(DomainEvent event, RuleContext ctx) {
        if (!"OrderCreatedEvent".equals(event.getEventType())) return false;
        if (!(event instanceof SimpleEvent se)) return false;

        BigDecimal amount = se.getBigDecimal("totalAmount");
        if (amount == null) return false;

        BigDecimal mean = ctx.getUserMeanAmount();
        BigDecimal std = ctx.getUserStdAmount();
        if (mean == null || std == null || std.compareTo(BigDecimal.ZERO) == 0) return false;

        BigDecimal deviation = amount.subtract(mean).abs();
        BigDecimal threshold = new BigDecimal("3").multiply(std);
        return deviation.compareTo(threshold) > 0;
    }
}
