package com.eventguard.event.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class PaymentFailedEvent extends DomainEvent {
    private final String reason;

    public PaymentFailedEvent(UUID orderId, int version, String reason, Map<String, String> metadata) {
        super(orderId, version, metadata);
        this.reason = reason;
    }

    public PaymentFailedEvent(UUID eventId, UUID aggregateId, int version, Instant occurredAt,
                              Map<String, String> metadata, String reason) {
        super(eventId, aggregateId, "PaymentFailedEvent", version, occurredAt, metadata);
        this.reason = reason;
    }

    @Override public Object getPayload() {
        return Map.of("orderId", getAggregateId(), "reason", reason);
    }

    public String getReason() { return reason; }
}
