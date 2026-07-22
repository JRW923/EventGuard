package com.eventguard.event.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public class OrderCreatedEvent extends DomainEvent {
    private final String userId;
    private final BigDecimal totalAmount;

    public OrderCreatedEvent(UUID orderId, int version, String userId, BigDecimal totalAmount, Map<String, String> metadata) {
        super(orderId, version, metadata);
        this.userId = userId;
        this.totalAmount = totalAmount;
    }

    @Override
    public Object getPayload() {
        return Map.of("orderId", getAggregateId(), "userId", userId, "totalAmount", totalAmount);
    }

    public String getUserId() { return userId; }
    public BigDecimal getTotalAmount() { return totalAmount; }
}
