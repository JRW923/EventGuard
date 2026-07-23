package com.eventguard.event.snapshot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class SnapshotStoreJdbcImpl implements SnapshotStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SnapshotStoreJdbcImpl(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Snapshot> load(UUID aggregateId) {
        RowMapper<Snapshot> mapper = (rs, rowNum) -> {
            try {
                String stateJson = rs.getString("state");
                Map<String, Object> state = objectMapper.convertValue(
                        objectMapper.readTree(stateJson), new TypeReference<Map<String, Object>>() {});
                Instant createdAt = rs.getTimestamp("created_at").toInstant();
                return new Snapshot(
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getInt("version"),
                        state,
                        createdAt
                );
            } catch (Exception e) {
                throw new IllegalStateException("解析快照失败", e);
            }
        };
        List<Snapshot> list = jdbc.query(
                "SELECT aggregate_id, aggregate_type, version, state, created_at " +
                        "FROM aggregate_snapshots WHERE aggregate_id = ?",
                mapper, aggregateId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public void save(Snapshot snapshot) {
        try {
            String stateJson = objectMapper.writeValueAsString(snapshot.getState());
            jdbc.update(
                    "INSERT INTO aggregate_snapshots (aggregate_id, aggregate_type, version, state, created_at) " +
                            "VALUES (?, ?, ?, ?::jsonb, ?) ON CONFLICT (aggregate_id) DO UPDATE SET " +
                            "aggregate_type = EXCLUDED.aggregate_type, version = EXCLUDED.version, " +
                            "state = EXCLUDED.state, created_at = EXCLUDED.created_at",
                    snapshot.getAggregateId(),
                    snapshot.getAggregateType(),
                    snapshot.getVersion(),
                    stateJson,
                    Timestamp.from(snapshot.getCreatedAt())
            );
        } catch (Exception e) {
            throw new IllegalStateException("快照保存失败", e);
        }
    }
}
