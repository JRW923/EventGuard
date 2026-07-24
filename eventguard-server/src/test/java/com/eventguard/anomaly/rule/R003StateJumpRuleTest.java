package com.eventguard.anomaly.rule;

import com.eventguard.anomaly.engine.RuleContext;
import com.eventguard.anomaly.model.SimpleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class R003StateJumpRuleTest {

    @Test
    void matches_when_jump_from_pending_payment_to_shipped() {
        R003StateJumpRule rule = new R003StateJumpRule();
        // 当前事件是 ShippedEvent，但前序状态是 PENDING_PAYMENT（跳过了 PAID/CONFIRMED）
        SimpleEvent event = new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ShippedEvent", 2,
                Instant.now(), Map.of(), Map.of()
        );
        RuleContext ctx = RuleContext.builder()
                .previousState("PENDING_PAYMENT")
                .build();

        assertThat(rule.matches(event, ctx)).isTrue();
    }

    @Test
    void does_not_match_when_legal_transition() {
        R003StateJumpRule rule = new R003StateJumpRule();
        SimpleEvent event = new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), "PaymentCompletedEvent", 2,
                Instant.now(), Map.of(), Map.of()
        );
        RuleContext ctx = RuleContext.builder()
                .previousState("PENDING_PAYMENT")
                .build();

        assertThat(rule.matches(event, ctx)).isFalse();
    }
}
