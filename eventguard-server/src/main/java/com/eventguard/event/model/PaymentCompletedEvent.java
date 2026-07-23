package com.eventguard.event.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class PaymentCompletedEvent extends DomainEvent {
    private final String paymentId;

    public PaymentCompletedEvent(UUID orderId, int version, String paymentId, Map<String, String> metadata) {
        super(orderId, version, metadata);
        this.paymentId = paymentId;
    }

    public PaymentCompletedEvent(UUID eventId, UUID aggregateId, int version, Instant occurredAt,
                                 Map<String, String> metadata, String paymentId) {
        super(eventId, aggregateId, "PaymentCompletedEvent", version, occurredAt, metadata);
        this.paymentId = paymentId;
    }

    @Override public Object getPayload() {
        return Map.of("orderId", getAggregateId(), "paymentId", paymentId);
    }

    public String getPaymentId() { return paymentId; }
}
