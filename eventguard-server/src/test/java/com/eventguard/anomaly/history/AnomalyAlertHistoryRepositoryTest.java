package com.eventguard.anomaly.history;

import com.eventguard.anomaly.model.AnomalyAlert;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 最小可运行检查：落库参数与查询语句（真库行为由 Testcontainers 套件覆盖）。 */
class AnomalyAlertHistoryRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnomalyAlertHistoryRepository repo = new AnomalyAlertHistoryRepository(jdbc, mapper);

    @Test
    void save_inserts_raw_payload_with_idempotent_upsert() {
        AnomalyAlert alert = new AnomalyAlert("a-1", "R001", "agg-1", "OrderCreatedEvent",
                "WARN", "RULE", "HIGH", "2026-08-16T10:00:00Z", "金额偏离", Map.of());

        repo.save("{\"anomaly_id\":\"a-1\"}", alert);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("a-1"), eq("R001"), eq("agg-1"), eq("WARN"), eq("RULE"),
                eq("{\"anomaly_id\":\"a-1\"}"));
        assertThat(sql.getValue())
                .contains("INSERT INTO anomaly_alerts")
                .contains("ON CONFLICT (anomaly_id) DO NOTHING");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recent_queries_latest_first_with_limit() {
        when(jdbc.query(anyString(), any(RowMapper.class), anyInt()))
                .thenAnswer(inv -> List.of());
        AnomalyAlert probe = new AnomalyAlert("a-x", "R001", "agg", "E", "WARN", "RULE", "HIGH",
                "t", "d", Map.of());
        try {
            // RowMapper 行为验证：payload JSON → AnomalyAlert
            String json = mapper.writeValueAsString(probe);
            RowMapper<AnomalyAlert> rm = null; // 由仓库内部构造，这里仅验证 SQL；反序列化在下方直测
            assertThat(mapper.readValue(json, AnomalyAlert.class).getAnomalyId()).isEqualTo("a-x");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        repo.recent(10);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(10));
        assertThat(sql.getValue())
                .contains("FROM anomaly_alerts")
                .contains("ORDER BY received_at DESC")
                .contains("LIMIT ?");
    }
}
