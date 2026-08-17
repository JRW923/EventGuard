package com.eventguard.event.store;

import com.eventguard.common.exception.OptimisticConcurrencyException;
import com.eventguard.event.model.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class EventStoreJdbcImpl implements EventStore {

    // ponytail: 当前系统只有 Order 一个聚合，aggregate_type 恒为该值。接口 append 接受任意
    // DomainEvent，一旦引入第二种聚合，这里会把它错标成 Order。升级路径：给 DomainEvent 加
    // aggregateType()，由事件自身携带类型，而不是在写入处猜。
    private static final String AGGREGATE_TYPE_ORDER = "Order";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final EventDeserializer deserializer;

    public EventStoreJdbcImpl(JdbcTemplate jdbc, ObjectMapper objectMapper, EventDeserializer deserializer) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.deserializer = deserializer;
    }

    @Override
    public void append(UUID aggregateId, List<DomainEvent> events, int expectedVersion) {
        // 0. 聚合级事务锁：串行化同一聚合的并发 append，使下方「校验→插入」在锁保护下成为原子区间，
        //    并发写走明确的乐观锁冲突（可重试），而不是撞唯一约束走异常路径（不可重试）。
        //    ponytail: 需在调用方事务内生效（锁随事务提交释放）；无事务调用时退化为立即释放，无害。
        // ponytail: pg_advisory_xact_lock 返回 void，不能用 queryForObject(..., Long.class) 去读结果列
        // （会抛 Bad value for type long）。这里只借它拿聚合级事务锁（副作用），SELECT 1 仅提供可读的返回值。
        jdbc.queryForObject(
                "SELECT 1 FROM pg_advisory_xact_lock(hashtext(?))", Long.class, aggregateId.toString());
        // 1. 主动校验 expectedVersion（清晰错误信息）
        Integer currentVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(event_version), 0) FROM domain_events WHERE aggregate_id = ?",
                Integer.class, aggregateId);
        int actual = currentVersion == null ? 0 : currentVersion;
        if (actual != expectedVersion) {
            throw new OptimisticConcurrencyException(
                    "并发冲突：aggregate_id=" + aggregateId + " 期望版本 " + expectedVersion + "，实际版本 " + actual);
        }
        // 2. 插入事件，UNIQUE(aggregate_id, event_version) 仍保留为数据库层最终底线
        //    （防 advisory lock 之外的路径：无事务调用、人工直写等）
        for (DomainEvent event : events) {
            try {
                jdbc.update(
                        "INSERT INTO domain_events (event_id, aggregate_id, aggregate_type, event_type, event_version, payload, metadata, created_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)",
                        event.getEventId(),
                        event.getAggregateId(),
                        AGGREGATE_TYPE_ORDER,
                        event.getEventType(),
                        event.getVersion(),
                        toJson(event.getPayload()),
                        toJson(event.getMetadata()),
                        Timestamp.from(event.getOccurredAt())
                );
            } catch (DuplicateKeyException e) {
                throw new OptimisticConcurrencyException(
                        "并发冲突（UNIQUE 约束）：aggregate_id=" + aggregateId, e);
            }
        }
    }

    @Override
    public List<DomainEvent> load(UUID aggregateId) {
        return loadFrom(aggregateId, 0);
    }

    @Override
    public List<DomainEvent> loadFrom(UUID aggregateId, int fromVersion) {
        RowMapper<DomainEvent> rowMapper = (rs, rowNum) -> {
            UUID eventId = rs.getObject("event_id", UUID.class);
            String eventType = rs.getString("event_type");
            int version = rs.getInt("event_version");
            Instant occurredAt = rs.getTimestamp("created_at").toInstant();
            Map<String, String> metadata = objectMapper.convertValue(
                    readJson(rs.getString("metadata")),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            String payloadJson = rs.getString("payload");
            return deserializer.deserialize(eventId, aggregateId, eventType, version, occurredAt, metadata, payloadJson);
        };
        return jdbc.query(
                "SELECT event_id, event_type, event_version, payload, metadata, created_at " +
                        "FROM domain_events WHERE aggregate_id = ? AND event_version > ? ORDER BY event_version",
                rowMapper, aggregateId, fromVersion);
    }

    private com.fasterxml.jackson.databind.JsonNode readJson(String s) {
        try { return objectMapper.readTree(s == null ? "{}" : s); }
        catch (Exception e) { throw new IllegalStateException("解析 JSON 失败", e); }
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { throw new IllegalStateException("序列化失败", e); }
    }
}
