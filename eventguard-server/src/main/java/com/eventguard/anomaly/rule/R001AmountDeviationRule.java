package com.eventguard.anomaly.rule;

import com.eventguard.anomaly.engine.RuleContext;
import com.eventguard.anomaly.model.AnomalyLevel;
import com.eventguard.anomaly.model.SimpleEvent;
import com.eventguard.event.model.DomainEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** R001：金额偏离规则 — |amount - userMean| > N * userStd（N 默认 3，可经 eg.anomaly.r001.sigma 配置实验 2.5σ） */
@Component
public class R001AmountDeviationRule implements EventRule {

    private final BigDecimal sigma;

    public R001AmountDeviationRule(
            @Value("${eg.anomaly.r001.sigma:3}") double sigma) {
        this.sigma = BigDecimal.valueOf(sigma);
    }

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
        BigDecimal threshold = sigma.multiply(std);
        return deviation.compareTo(threshold) > 0;
    }
}
