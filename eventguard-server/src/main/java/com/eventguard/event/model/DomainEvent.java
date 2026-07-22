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

    protected DomainEvent(UUID aggregateId, int version, Map<String, String> metadata) {
        this.eventId = UUID.randomUUID();
        this.aggregateId = aggregateId;
        this.eventType = getClass().getSimpleName();
        this.version = version;
        this.occurredAt = Instant.now();
        this.metadata = metadata == null ? Map.of() : metadata;
    }

    public UUID getEventId() { return eventId; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public int getVersion() { return version; }
    public Instant getOccurredAt() { return occurredAt; }
    public Map<String, String> getMetadata() { return metadata; }

    public abstract Object getPayload();
}
