package com.eventguard.event.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class OrderClosedEvent extends DomainEvent {

    public OrderClosedEvent(UUID orderId, int version, Map<String, String> metadata) {
        super(orderId, version, metadata);
    }

    public OrderClosedEvent(UUID eventId, UUID aggregateId, int version, Instant occurredAt,
                            Map<String, String> metadata) {
        super(eventId, aggregateId, "OrderClosedEvent", version, occurredAt, metadata);
    }

    @Override public Object getPayload() {
        return Map.of("orderId", getAggregateId());
    }
}
