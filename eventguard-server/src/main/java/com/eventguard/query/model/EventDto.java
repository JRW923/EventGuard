package com.eventguard.query.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 事件 DTO（GET /orders/{id}/events 返回）。
 */
public class EventDto {

    private UUID eventId;
    private UUID aggregateId;
    private String eventType;
    private int version;
    private Map<String, Object> payload;
    private Instant createdAt;

    public EventDto() {}

    public EventDto(UUID eventId, UUID aggregateId, String eventType, int version,
                    Map<String, Object> payload, Instant createdAt) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.version = version;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public UUID getAggregateId() { return aggregateId; }
    public void setAggregateId(UUID aggregateId) { this.aggregateId = aggregateId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
