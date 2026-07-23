package com.eventguard.event.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class OrderConfirmedEvent extends DomainEvent {

    public OrderConfirmedEvent(UUID orderId, int version, Map<String, String> metadata) {
        super(orderId, version, metadata);
    }

    public OrderConfirmedEvent(UUID eventId, UUID aggregateId, int version, Instant occurredAt,
                               Map<String, String> metadata) {
        super(eventId, aggregateId, "OrderConfirmedEvent", version, occurredAt, metadata);
    }

    @Override public Object getPayload() {
        return Map.of("orderId", getAggregateId());
    }
}
