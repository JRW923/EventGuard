package com.eventguard.anomaly.engine;

import com.eventguard.anomaly.model.Anomaly;
import com.eventguard.anomaly.model.AnomalyLevel;
import com.eventguard.anomaly.model.SimpleEvent;
import com.eventguard.anomaly.rule.EventRule;
import com.eventguard.event.model.DomainEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RuleEngineTest {

    @Test
    void evaluate_returns_first_matching_rule_anomaly() {
        EventRule matchingRule = mock(EventRule.class);
        when(matchingRule.ruleId()).thenReturn("R001");
        when(matchingRule.level()).thenReturn(AnomalyLevel.WARN);
        when(matchingRule.matches(any(DomainEvent.class), any(RuleContext.class)))
                .thenReturn(true);

        EventRule nonMatchingRule = mock(EventRule.class);
        when(nonMatchingRule.matches(any(), any())).thenReturn(false);

        RuleContextLoader loader = mock(RuleContextLoader.class);
        when(loader.load(any())).thenReturn(RuleContext.builder().build());

        RuleEngine engine = new RuleEngine(List.of(nonMatchingRule, matchingRule), loader);
        SimpleEvent event = newSimpleEvent("OrderCreatedEvent");

        Optional<Anomaly> result = engine.evaluate(event);

        assertThat(result).isPresent();
        assertThat(result.get().getRuleId()).isEqualTo("R001");
        assertThat(result.get().getLevel()).isEqualTo(AnomalyLevel.WARN);
    }

    @Test
    void evaluate_returns_empty_when_no_rule_matches() {
        EventRule rule = mock(EventRule.class);
        when(rule.matches(any(), any())).thenReturn(false);

        RuleContextLoader loader = mock(RuleContextLoader.class);
        when(loader.load(any())).thenReturn(RuleContext.builder().build());

        RuleEngine engine = new RuleEngine(List.of(rule), loader);
        SimpleEvent event = newSimpleEvent("OrderCreatedEvent");

        Optional<Anomaly> result = engine.evaluate(event);

        assertThat(result).isEmpty();
    }

    private SimpleEvent newSimpleEvent(String eventType) {
        return new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), eventType, 1,
                Instant.now(), Map.of(), Map.of("totalAmount", 100.0)
        );
    }
}
