package com.eventguard.event.store;

import com.eventguard.common.exception.OptimisticConcurrencyException;
import com.eventguard.event.model.DomainEvent;
import com.eventguard.event.model.OrderCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventStoreJdbcImplTest {

    @Mock JdbcTemplate jdbc;
    @Mock EventDeserializer deserializer;
    ObjectMapper om = new ObjectMapper();
    @InjectMocks EventStoreJdbcImpl eventStore;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        eventStore = new EventStoreJdbcImpl(jdbc, om, deserializer);
    }

    @Test
    void append_should_throw_OptimisticConcurrencyException_when_expectedVersion_mismatches() {
        UUID aggId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("SELECT 1 FROM pg_advisory_xact_lock(hashtext(?))"), eq(Long.class), eq(aggId.toString())))
                .thenReturn(1L);
        when(jdbc.queryForObject(eq("SELECT COALESCE(MAX(event_version), 0) FROM domain_events WHERE aggregate_id = ?"),
                eq(Integer.class), eq(aggId)))
                .thenReturn(5);
        OrderCreatedEvent event = new OrderCreatedEvent(aggId, 6, "u1", new BigDecimal("99"), null);

        assertThatThrownBy(() -> eventStore.append(aggId, List.of(event), 0))
                .isInstanceOf(OptimisticConcurrencyException.class)
                .hasMessageContaining("期望版本 0")
                .hasMessageContaining("实际版本 5");
    }

    @Test
    void append_should_throw_OptimisticConcurrencyException_on_unique_violation() {
        UUID aggId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("SELECT 1 FROM pg_advisory_xact_lock(hashtext(?))"), eq(Long.class), eq(aggId.toString())))
                .thenReturn(1L);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(aggId))).thenReturn(0);
        // 8 个占位参数：aggregate_type 已从 SQL 字面量改为绑定参数
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate"));
        OrderCreatedEvent event = new OrderCreatedEvent(aggId, 1, "u1", new BigDecimal("99"), null);

        assertThatThrownBy(() -> eventStore.append(aggId, List.of(event), 0))
                .isInstanceOf(OptimisticConcurrencyException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadFrom_should_return_events_ordered_by_version() {
        UUID aggId = UUID.randomUUID();
        OrderCreatedEvent e1 = new OrderCreatedEvent(UUID.randomUUID(), aggId, 1, Instant.now(), null, "u1", new BigDecimal("99"));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(aggId), eq(0)))
                .thenReturn(List.of(e1));

        List<DomainEvent> events = eventStore.loadFrom(aggId, 0);

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(OrderCreatedEvent.class);
    }
}
