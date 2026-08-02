package com.eventguard.anomaly.rule;

import com.eventguard.anomaly.engine.RuleContext;
import com.eventguard.anomaly.model.SimpleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class R005InventoryOverflowRuleTest {

    @Test
    void matches_when_reserved_qty_exceeds_stock() {
        R005InventoryOverflowRule rule = new R005InventoryOverflowRule();
        SimpleEvent event = new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), "InventoryReservedEvent", 3,
                Instant.now(), Map.of(), Map.of("quantity", 150)
        );
        RuleContext ctx = RuleContext.builder()
                .actualStock(100)
                .build();

        assertThat(rule.matches(event, ctx)).isTrue();
    }

    @Test
    void does_not_match_when_reserved_within_stock() {
        R005InventoryOverflowRule rule = new R005InventoryOverflowRule();
        SimpleEvent event = new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), "InventoryReservedEvent", 3,
                Instant.now(), Map.of(), Map.of("quantity", 50)
        );
        RuleContext ctx = RuleContext.builder()
                .actualStock(100)
                .build();

        assertThat(rule.matches(event, ctx)).isFalse();
    }

    @Test
    void matches_inventory_reservation_failed_event() {
        R005InventoryOverflowRule rule = new R005InventoryOverflowRule();
        SimpleEvent event = new SimpleEvent(
                UUID.randomUUID(), UUID.randomUUID(), "InventoryReservationFailedEvent", 3,
                Instant.now(), Map.of(), Map.of("quantity", 10, "reason", "库存不足")
        );
        RuleContext ctx = RuleContext.builder()
                .actualStock(0)
                .build();

        assertThat(rule.matches(event, ctx)).isTrue();
    }
}
