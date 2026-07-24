package com.eventguard.anomaly.rule;

import com.eventguard.anomaly.engine.RuleContext;
import com.eventguard.anomaly.model.SimpleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class R002DuplicatePaymentRuleTest {

    @Test
    void matches_when_duplicate_payment_within_5min() {
        R002DuplicatePaymentRule rule = new R002DuplicatePaymentRule();
        Instant now = Instant.now();
        SimpleEvent event = new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), "PaymentCompletedEvent", 3,
                now, Map.of(), Map.of("amount", 99.0)
        );
        // 3 分钟前已有一次 PaymentCompleted
        RuleContext ctx = RuleContext.builder()
                .recentPaymentCompletions(List.of(now.minus(3, ChronoUnit.MINUTES)))
                .build();

        assertThat(rule.matches(event, ctx)).isTrue();
    }

    @Test
    void does_not_match_when_no_previous_payment() {
        R002DuplicatePaymentRule rule = new R002DuplicatePaymentRule();
        SimpleEvent event = new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), "PaymentCompletedEvent", 2,
                Instant.now(), Map.of(), Map.of("amount", 99.0)
        );
        RuleContext ctx = RuleContext.builder()
                .recentPaymentCompletions(List.of())
                .build();

        assertThat(rule.matches(event, ctx)).isFalse();
    }
}
