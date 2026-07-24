package com.eventguard.query.service;

import com.eventguard.query.model.OrderStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderStatsServiceTest {

    @Mock
    JdbcTemplate jdbc;

    @InjectMocks
    OrderStatsService service;

    @Test
    void getStats_should_group_by_status_and_filter_by_time_window() {
        Instant from = Instant.parse("2026-07-20T00:00:00Z");
        Instant to = Instant.parse("2026-07-21T00:00:00Z");

        OrderStats row = new OrderStats("PAID", 5L, new java.math.BigDecimal("495.00"));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(from), eq(to)))
                .thenReturn(List.of(row));

        List<OrderStats> result = service.getStats(null, from, to);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("PAID");
        assertThat(result.get(0).getOrderCount()).isEqualTo(5L);
    }

    @Test
    void getStats_with_status_should_filter_by_status() {
        Instant from = Instant.parse("2026-07-20T00:00:00Z");
        Instant to = Instant.parse("2026-07-21T00:00:00Z");

        when(jdbc.query(anyString(), any(RowMapper.class), eq("PAID"), eq(from), eq(to)))
                .thenReturn(List.of(new OrderStats("PAID", 3L, new java.math.BigDecimal("300.00"))));

        List<OrderStats> result = service.getStats("PAID", from, to);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrderCount()).isEqualTo(3L);
    }

    @Test
    void getStats_empty_result_when_no_data() {
        Instant from = Instant.parse("2026-07-20T00:00:00Z");
        Instant to = Instant.parse("2026-07-21T00:00:00Z");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(from), eq(to)))
                .thenReturn(List.of());

        List<OrderStats> result = service.getStats(null, from, to);

        assertThat(result).isEmpty();
    }
}
