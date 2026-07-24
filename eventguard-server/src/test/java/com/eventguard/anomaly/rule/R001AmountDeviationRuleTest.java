package com.eventguard.anomaly.rule;

import com.eventguard.anomaly.engine.RuleContext;
import com.eventguard.anomaly.model.AnomalyLevel;
import com.eventguard.anomaly.model.SimpleEvent;
import com.eventguard.event.model.DomainEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class R001AmountDeviationRuleTest {

    @Test
    void matches_when_amount_exceeds_3_sigma() {
        R001AmountDeviationRule rule = new R001AmountDeviationRule();
        SimpleEvent event = new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), "OrderCreatedEvent", 1,
                Instant.now(), Map.of("userId", "user-1"),
                Map.of("totalAmount", 500.0)
        );
        // 用户历史均值 100，标准差 50 → 500 偏离 8σ
        RuleContext ctx = RuleContext.builder()
                .userMeanAmount(new BigDecimal("100"))
                .userStdAmount(new BigDecimal("50"))
                .build();

        boolean result = rule.matches(event, ctx);

        assertThat(result).isTrue();
        assertThat(rule.ruleId()).isEqualTo("R001");
        assertThat(rule.level()).isEqualTo(AnomalyLevel.WARN);
    }

    @Test
    void does_not_match_when_amount_within_normal_range() {
        R001AmountDeviationRule rule = new R001AmountDeviationRule();
        SimpleEvent event = new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), "OrderCreatedEvent", 1,
                Instant.now(), Map.of("userId", "user-1"),
                Map.of("totalAmount", 120.0)
        );
        RuleContext ctx = RuleContext.builder()
                .userMeanAmount(new BigDecimal("100"))
                .userStdAmount(new BigDecimal("50"))
                .build();

        assertThat(rule.matches(event, ctx)).isFalse();
    }

    @Test
    void does_not_match_for_non_order_created_event() {
        R001AmountDeviationRule rule = new R001AmountDeviationRule();
        SimpleEvent event = new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), "PaymentCompletedEvent", 2,
                Instant.now(), Map.of(), Map.of("amount", 999999.0)
        );
        RuleContext ctx = RuleContext.builder().build();

        assertThat(rule.matches(event, ctx)).isFalse();
    }
}
