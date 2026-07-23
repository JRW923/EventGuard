package com.eventguard.event.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SnapshotStoreJdbcImplTest {

    @Mock JdbcTemplate jdbc;
    ObjectMapper om = new ObjectMapper();
    @InjectMocks SnapshotStoreJdbcImpl store;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        store = new SnapshotStoreJdbcImpl(jdbc, om);
    }

    @Test
    void load_should_return_empty_when_no_snapshot() {
        UUID aggId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(aggId))).thenReturn(List.of());

        Optional<Snapshot> result = store.load(aggId);

        assertThat(result).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void save_should_upsert_snapshot() {
        UUID aggId = UUID.randomUUID();
        Snapshot snap = new Snapshot(aggId, "Order", 100, Map.of("k", "v"), Instant.now());

        store.save(snap);

        verify(jdbc).update(eq(
                "INSERT INTO aggregate_snapshots (aggregate_id, aggregate_type, version, state, created_at) " +
                        "VALUES (?, ?, ?, ?::jsonb, ?) ON CONFLICT (aggregate_id) DO UPDATE SET " +
                        "aggregate_type = EXCLUDED.aggregate_type, version = EXCLUDED.version, " +
                        "state = EXCLUDED.state, created_at = EXCLUDED.created_at"),
                eq(aggId), eq("Order"), eq(100), anyString(), any());
    }
}
