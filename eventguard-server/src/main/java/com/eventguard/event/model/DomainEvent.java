package com.eventguard.event.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public abstract class DomainEvent {
    private final UUID eventId;
    private final UUID aggregateId;
    private final String eventType;
    private final int version;
    private final Instant occurredAt;
    private final Map<String, String> metadata;

    // 新事件用：自动生成 eventId 与 occurredAt
    protected DomainEvent(UUID aggregateId, int version, Map<String, String> metadata) {
        this(UUID.randomUUID(), aggregateId, null, version, Instant.now(), metadata);
    }

    // 重建事件用：所有字段都指定（从 DB / Kafka 还原）
    protected DomainEvent(UUID eventId, UUID aggregateId, String eventType, int version,
                          Instant occurredAt, Map<String, String> metadata) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType != null ? eventType : getClass().getSimpleName();
        this.version = version;
        this.occurredAt = occurredAt;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public UUID getEventId() { return eventId; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public int getVersion() { return version; }
    public Instant getOccurredAt() { return occurredAt; }
    public Map<String, String> getMetadata() { return metadata; }

    public abstract Object getPayload();
}
