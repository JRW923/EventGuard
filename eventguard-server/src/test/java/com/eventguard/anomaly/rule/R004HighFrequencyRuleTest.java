package com.eventguard.anomaly.rule;

import com.eventguard.anomaly.engine.RuleContext;
import com.eventguard.anomaly.model.SimpleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class R004HighFrequencyRuleTest {

    @Test
    void matches_when_user_creates_more_than_20_orders_in_1min() {
        R004HighFrequencyRule rule = new R004HighFrequencyRule();
        Instant now = Instant.now();
        SimpleEvent event = new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), "OrderCreatedEvent", 1,
                now, Map.of("userId", "user-1"), Map.of("totalAmount", 50.0)
        );
        // 用户过去 1 分钟内已有 21 个下单事件
        List<Instant> recent = IntStream.range(0, 21)
                .mapToObj(i -> now.minus(i * 2, ChronoUnit.SECONDS))
                .toList();
        RuleContext ctx = RuleContext.builder()
                .recentCreateOrders(recent)
                .build();

        assertThat(rule.matches(event, ctx)).isTrue();
    }

    @Test
    void does_not_match_when_below_threshold() {
        R004HighFrequencyRule rule = new R004HighFrequencyRule();
        SimpleEvent event = new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), "OrderCreatedEvent", 1,
                Instant.now(), Map.of("userId", "user-1"), Map.of("totalAmount", 50.0)
        );
        RuleContext ctx = RuleContext.builder()
                .recentCreateOrders(List.of(Instant.now().minus(30, ChronoUnit.SECONDS)))
                .build();

        assertThat(rule.matches(event, ctx)).isFalse();
    }
}
