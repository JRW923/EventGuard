package com.eventguard.anomaly.history;

import com.eventguard.anomaly.model.AnomalyAlert;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 最近告警环形缓冲单测：最新在前、容量上限、null 安全。 */
class RecentAlertsBufferTest {

    private static AnomalyAlert alert(String id) {
        return new AnomalyAlert(id, "R001", "agg-1", "OrderCreatedEvent",
                "WARN", "RULE", "HIGH", "2026-07-21T10:00:00Z", "d", Map.of());
    }

    @Test
    void recent_returns_newest_first() {
        RecentAlertsBuffer buffer = new RecentAlertsBuffer(100);
        buffer.add(alert("a-1"));
        buffer.add(alert("a-2"));

        assertEquals(2, buffer.recent().size());
        assertEquals("a-2", buffer.recent().get(0).getAnomalyId());
        assertEquals("a-1", buffer.recent().get(1).getAnomalyId());
    }

    @Test
    void capacity_evicts_oldest() {
        RecentAlertsBuffer buffer = new RecentAlertsBuffer(3);
        buffer.add(alert("a-1"));
        buffer.add(alert("a-2"));
        buffer.add(alert("a-3"));
        buffer.add(alert("a-4"));

        assertEquals(3, buffer.recent().size());
        assertEquals("a-4", buffer.recent().get(0).getAnomalyId());
        assertTrue(buffer.recent().stream().noneMatch(a -> a.getAnomalyId().equals("a-1")),
                "最旧的 a-1 应被淘汰");
    }

    @Test
    void add_null_is_ignored() {
        RecentAlertsBuffer buffer = new RecentAlertsBuffer(10);
        buffer.add(null);
        assertTrue(buffer.recent().isEmpty());
    }
}
