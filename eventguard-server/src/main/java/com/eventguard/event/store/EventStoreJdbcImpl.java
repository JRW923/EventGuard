package com.eventguard.event.store;

import com.eventguard.event.model.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class EventStoreJdbcImpl implements EventStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EventStoreJdbcImpl(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(UUID aggregateId, List<DomainEvent> events, int expectedVersion) {
        for (DomainEvent event : events) {
            jdbc.update(
                    "INSERT INTO domain_events (event_id, aggregate_id, aggregate_type, event_type, event_version, payload, metadata, created_at) " +
                            "VALUES (?, ?, 'Order', ?, ?, ?::jsonb, ?::jsonb, ?)",
                    event.getEventId(),
                    event.getAggregateId(),
                    event.getEventType(),
                    event.getVersion(),
                    toJson(event.getPayload()),
                    toJson(event.getMetadata()),
                    java.sql.Timestamp.from(event.getOccurredAt())
            );
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }
}
