package com.eventguard.event.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class ShippedEvent extends DomainEvent {
    private final String trackingNo;

    public ShippedEvent(UUID orderId, int version, String trackingNo, Map<String, String> metadata) {
        super(orderId, version, metadata);
        this.trackingNo = trackingNo;
    }

    public ShippedEvent(UUID eventId, UUID aggregateId, int version, Instant occurredAt,
                        Map<String, String> metadata, String trackingNo) {
        super(eventId, aggregateId, "ShippedEvent", version, occurredAt, metadata);
        this.trackingNo = trackingNo;
    }

    @Override public Object getPayload() {
        return Map.of("orderId", getAggregateId(), "trackingNo", trackingNo);
    }

    public String getTrackingNo() { return trackingNo; }
}
